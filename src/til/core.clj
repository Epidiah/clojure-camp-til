(ns til.core
  (:require
   [til.oauth :as oauth]
   [org.httpkit.server :as http]
   [huff2.core :as h]
   [datalevin.core :as d]
   [girouette.processor]
   [nextjournal.markdown :as md]
   [nextjournal.markdown.transform :as md.transform]
   [ring.middleware.defaults :as rmd]))

(def css-opts
  {:css {:output-file "target/twstyles.css"}
   :input {:file-filters [".clj" ".cljs" ".cljc"]}
   :verbose? false
   :garden-fn 'girouette.tw.default-api/tw-v3-class-name->garden
   :base-css-rules ['girouette.tw.preflight/preflight-v3_0_24]})

(defn compile-css! [] (girouette.processor/process css-opts))

(defonce server (atom nil))

(def schema {:post/content    {:db/valueType :db.type/string}
             :post/created-at {:db/valueType :db.type/instant}
             :post/id         {:db/valueType :db.type/uuid
                               :db/unique    :db.unique/identity}
             :user/id         {:db/valueType :db.type/uuid
                               :db/unique    :db.unique/identity}
             :user/post       {:db/valueType   :db.type/ref
                               :db/cardinality :db.cardinality/many}
             :user/email      {:db/valueType :db.type/string
                               :db/unique    :db.unique/identity}})

(def conn (d/get-conn "./db" schema))

(defn index-page [user-id]
  (let [user (when user-id
               (d/q '[:find (pull ?user [*]) .
                     :in $ ?id
                     :where
                     [?user :user/id ?id]]
                   (d/db conn)
                   user-id))]
    [:html
     [:head
      [:link {:rel "stylesheet" :href "/styles.css"}]]
     [:body
      (if user
        [:div.flex.gap-1
         [:div (:user/email user)]
         [:form {:method "post"
                 :action "/session/delete"}
          [:button "×"]]]
        [:form {:method "get"
                :action "/session/new"}
         [:input {:hidden true
                  :name   "email"
                  :value  "alice@example.com"}]
         [:button "Log In"]])
      (when user
        [:form {:method "post"}
         [:textarea {:name "content"}]
         [:button "Submit"]])
      [:div.space-y-4.mx-4
       (for [{:post/keys [content created-at] :as post}
             (->> (d/q '[:find [(pull ?e [* {:user/_post [:user/email]}]) ...]
                         :where
                         [?e :post/content _]]
                       (d/db conn))
                  (sort-by :post/created-at)
                  reverse)
             :let [user-email (get-in post [:user/_post 0 :user/email])]]
              [:section.p-4.rounded.border
               [:div (-> content
                         md/parse
                         md.transform/->hiccup)]
               [:div (str created-at)]
               [:div user-email]])]]]))

(defn create-post! [content user-id]
  (d/transact! conn [{:post/content    content
                      :post/id         (random-uuid)
                      :user/_post      [:user/id user-id]
                      :post/created-at (java.util.Date.)}]))

(defn handler [req]
  (or
   (oauth/handler (assoc req :db-conn conn))
   (cond
     (and (= :get (:request-method req))
          (= "/styles.css" (:uri req)))
     {:status  200
      :headers {"Content-Type" "text/css"}
      :body    (slurp (get-in css-opts [:css :output-file]))}

     (and (= :post (:request-method req))
          (= "/" (:uri req)))
     (let [content (get-in req [:form-params "content"])
           user-id (get-in req [:session :user-id])]
       (create-post! content user-id)
       {:status  302
        :headers {"Location" "/"}})

     (and (= :post (:request-method req))
          (= "/session/delete" (:uri req)))
     {:status  302
      :headers {"Location" "/"}
      :session nil}

     (and (= :get (:request-method req))
          (= "/" (:uri req)))
     {:status  200
      :headers {"Content-Type" "text/html"}
      :body    (str (h/html (index-page (get-in req [:session :user-id]))))}

     :else
     {:status 404
      :body "This is not the page you're looking for."})))

(def app
  (rmd/wrap-defaults handler
                     (-> rmd/site-defaults
                         ;; same-site should be sufficient to deal with csrf risks
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:session :cookie-attrs :same-site] :lax)
                         (assoc-in [:session :cookie-name] "clojure-camp-til"))))

(defn start! []
  (compile-css!)
  (when @server (@server))
  (reset! server (http/run-server #'app {:port 8123})))

#_(compile-css!)

(comment

  (compile-css!)
  (start!)
  (d/q '[:find [(pull ?e [*]) ...]
                     :where
                     [?e :post/content _]]
                   (d/db conn))
  )
