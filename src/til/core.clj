(ns til.core
  (:gen-class)
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [til.config :as config]
   [til.oauth :as oauth]
   [org.httpkit.server :as http]
   [huff2.core :as h]
   [datalevin.core :as d]
   [girouette.processor]
   [nextjournal.beholder :as beholder]
   [nextjournal.markdown :as md]
   [nextjournal.markdown.transform :as md.transform]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [ring.middleware.defaults :as rmd])
  (:import
    [java.time.format DateTimeFormatter]
    [java.time ZoneId]))

(defn format-inst [inst]
  (.format (.atZone (.toInstant inst) (ZoneId/of "America/Toronto"))
         (DateTimeFormatter/ofPattern "yyyy-MM-dd")))

(def css-opts
  {:css {:output-file "target/public/twstyles.css"}
   :input {:file-filters [".clj" ".cljs" ".cljc"]}
   :verbose? false
   :garden-fn 'girouette.tw.default-api/tw-v3-class-name->garden
   :base-css-rules ['girouette.tw.preflight/preflight-v3_0_24]})

(defn compile-css! [] (girouette.processor/process css-opts))

(defn watch-css!
  []
  ;; New girouette uses a blocking watcher
  (with-redefs [beholder/watch-blocking #'beholder/watch]
    (girouette.processor/process (assoc css-opts :watch? true))))
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
                               :db/unique    :db.unique/identity}
             :user/name       {:db/valueType :db.type/string}
             :user/avatar-url {:db/valueType :db.type/string}})

(def conn (delay (d/get-conn "./db" schema)))

(defn rand-in-range [low high]
  (+ low (rand (- high low))))

(defn star-field-view [w h]
  (let [star-count 300
        base-star-size 12]
    [:svg.star-field
     {:xmlns "http://www.w3.org/2000/svg"
      :width "100%"
      :height "100%"
      :preserveAspectRatio "xMidYMid slice"
      :viewBox (str/join " " [0 0 w h])}
     (into [:g]
           (repeatedly
             star-count
             (fn []
               [:circle {:cx (rand-in-range 0 w)
                         :cy (rand-in-range 0 h)
                         :r (* base-star-size
                               (rand-nth (concat
                                           (repeat 5 0.05)
                                           (repeat 5 0.075)
                                           (repeat 4 0.01)
                                           (repeat 3 0.15)
                                           (repeat 2 0.02)
                                           (repeat 1 0.025))))
                         :fill "#fff"}])))]))

(defn index-page [user-id]
  (let [user (when user-id
               (d/q '[:find (pull ?user [*]) .
                      :in $ ?id
                      :where
                      [?user :user/id ?id]]
                   (d/db @conn)
                   user-id))]
    [:html
     [:head
      [:link {:rel "stylesheet" :href "/styles.css"}]]
     [:body.text-white
      {:class "bg-#181742"}
      [:div.absolute.inset-0
       {:style {:z-index -1}}
       (star-field-view 1000 1000)]
      (if user
        [:div.flex.gap-1
         [:div (:user/name user)]
         [:form {:method "post"
                 :action "/session/delete"}
          [:button "×"]]]
        [:form
         {:method "get"
           :action "/session/new"}
         [:input {:hidden true
                  :name   "email"
                  :value  "alice@example.com"}]
         [:button "Log In"]])
      (when user
        [:form.p-8
         {:method "post"}
         [:textarea.border.w-full.h-10em.p-2
          {:name "content"
           :class "bg-#0a0a2daa"}]
         [:div.text-right [:button "Submit"]]])
      [:div.space-y-4.mx-8
       (for [{:post/keys [content created-at] :as post}
             (->> (d/q '[:find [(pull ?e [* {:user/_post
                                             [:user/name :user/avatar-url]}]) ...]
                         :where
                         [?e :post/content _]]
                       (d/db @conn))
                  (sort-by :post/created-at)
                  reverse)
             :let [user (get-in post [:user/_post 0])]]
            [:section
             [:div.p-4.rounded.border.prose
              {:class "bg-#0a0a2daa"}
              (-> content
                  md/parse
                  md.transform/->hiccup)]
             [:div.flex.gap-2.justify-end.items-center
              [:div (format-inst created-at)]
              [:img {:src (:user/avatar-url user)
                     :style {:width "1em" :height "1em"}}]
              [:div (:user/name user)]]])]]]))

(defn create-post! [content user-id]
  (d/transact! @conn [{:post/content    content
                       :post/id         (random-uuid)
                       :user/_post      [:user/id user-id]
                       :post/created-at (java.util.Date.)}]))

(defn handler [req]
  (or
   (oauth/handler (assoc req :db-conn @conn))
   (cond
     (and (= :get (:request-method req))
          (= "/styles.css" (:uri req)))
     {:status  200
      :headers {"Content-Type" "text/css"}
      :body    (str (slurp (io/resource "twstyles.css"))
                    "\n"
                    (slurp (io/resource "public/markdown.css")))}

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
      :headers {"Content-Type" "text/html"}
      :body "This is not the page you're looking for."})))

(defn app []
  (rmd/wrap-defaults (fn [req] (#'handler req))
                     (-> (if (= :prod (config/get :environment))
                           rmd/secure-site-defaults
                           rmd/site-defaults)
                         ;; same-site should be sufficient to deal with csrf risks
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:session :cookie-attrs :same-site] :lax)
                         (assoc-in [:session :cookie-name] "clojure-camp-til")
                         (assoc-in [:session :store]
                                   (cookie-store {:key
                                                  (byte-array
                                                   (config/get :cookie-secret))}))
                         (assoc-in [:session :cookie-attrs :max-age] (* 60 60 24 365))
                         (assoc-in [:proxy] (= :prod (config/get :environment))))))

(defn start! []
  (when @server (@server))
  ;; Middleware will not be hot-reloaded. Need to call `start!` manually.
  (reset! server (http/run-server (app) {:port (config/get :http-port)})))

(defn -main [& _]
  (start!))

(comment

  (d/transact @conn
              (map (fn [e] [:db/retractEntity e])
                   (d/q '[:find [?e ...]
                          :where [?e  ]] (d/db @conn))))
  (compile-css!)

  (watch-css!)

  (start!)

  (d/q '[:find [(pull ?e [*]) ...]
               :where
               [?e _ _]]
        (d/db @conn))

  (d/q '[:find [(pull ?e [* {:user/_post [:user/email]}]) ...]
               :where
               [?e :post/content _]]
       (d/db @conn))

  (d/q '[:find (pull ?user [*]) .
                    :in $ ?id
                    :where
                    [?user :user/id ?id]]
        (d/db @conn)
        nil))
  
