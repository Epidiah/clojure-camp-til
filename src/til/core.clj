(ns til.core
  (:require
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

(def schema {:post/content {:db/valueType :db.type/string}
             :user/id      {:db/valueType :db.type/uuid
                            :db/unique    :db.unique/identity}
             :user/email   {:db/valueType :db.type/string
                            :db/unique    :db.unique/identity}})

(def conn (d/get-conn "./db" schema))

(defn index-page [user-id]
  (let [user (d/q '[:find (pull ?user [*]) .
                    :in $ ?id
                    :where
                    [?user :user/id ?id]]
                  (d/db conn)
                  user-id)]
    [:html
     [:head
      [:link {:rel "stylesheet" :href "/styles.css"}]]
     [:body
      (if user
        [:div (:user/email user)]
        [:form {:method "post"
                :action "/session/new"}
         [:input {:hidden true
                  :name   "email"
                  :value  "alice@example.com"}]
         [:button "Log In"]])
      [:form {:method "post"}
       [:textarea {:name "content"}]
       [:button "Submit"]]
      [:div.space-y-4.mx-4
       (for [{:keys [post/content]} (d/q '[:find [(pull ?e [*]) ...]
                                           :where
                                           [?e :post/content _]]
                                         (d/db conn))]
              [:section.p-4.rounded.border
               (-> content
                   md/parse
                   md.transform/->hiccup)])]]]))

(defn create-post! [content]
  (d/transact! conn [{:post/content content}]))

(defn handler [req]
  (cond
    (and (= :get (:request-method req))
         (= "/styles.css" (:uri req)))
    {:status  200
     :headers {"Content-Type" "text/css"}
     :body    (slurp (get-in css-opts [:css :output-file]))}

    (and (= :post (:request-method req))
         (= "/" (:uri req)))
    (let [content (get-in req [:form-params "content"])]
      (create-post! content)
      {:status  302
       :headers {"Location" "/"}})

    (and (= :post (:request-method req))
         (= "/session/new" (:uri req)))
    (let [email   (get-in req [:form-params "email"])
          user-id (or (d/q '[:find ?id .
                             :in $ ?email
                             :where
                             [?user :user/email ?email]
                             [?user :user/id ?id]]
                           (d/db conn)
                           email)
                      (let [id (random-uuid)]
                        (d/transact! conn [{:user/id    id
                                            :user/email email}])
                        id))]
      {:status  302
       :headers {"Location" "/"}
       :session {:user-id user-id}})

    (and (= :get (:request-method req))
         (= "/" (:uri req)))
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (str (h/html (index-page (get-in req [:session :user-id]))))}

    :else
    {:status 404
     :body "This is not the page you're looking for."}))

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
