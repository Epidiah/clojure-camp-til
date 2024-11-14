(ns til.core
  (:require
   [org.httpkit.server :as http]
   [huff2.core :as h]
   [datalevin.core :as d]
   [ring.middleware.defaults :as rmd]))

(defonce server (atom nil))

(def schema {:post/content {:db/valueType :db.type/string}})

(def conn (d/get-conn "./db" schema))

(defn index-page []
  [:<> [:form {:method "post"}
        [:textarea {:name "content"}]
        [:button "Submit"]]
   [:div (for [{:keys [post/content]} (d/q '[:find [(pull ?e [*]) ...]
                                             :where
                                             [?e :post/content _]]
                                           (d/db conn))]
           [:div content])]])

(defn create-post! [content]
  (d/transact! conn [{:post/content content}]))

(defn handler [req]
  (cond
    (= :post (:request-method req))
    (let [content (get-in req [:form-params "content"])]
      (create-post! content)
      {:status  302
       :headers {"Location" "/"}})
    :else
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body   (str (h/html (index-page)))}))

(def app
  (rmd/wrap-defaults handler
                     (assoc-in rmd/site-defaults [:security :anti-forgery] false)))

(defn start! []
  (when @server (@server))
  (reset! server (http/run-server #'app {:port 8123})))

(comment
  (start!)
  (d/q '[:find [(pull ?e [*]) ...]
                     :where
                     [?e :post/content _]]
                   (d/db conn))
  )
