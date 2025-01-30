(ns til.oauth
 (:require
  [clojure.data.json :as json]
  [clojure.edn :as edn]
  [datalevin.core :as d]
  [taoensso.tempel :as tempel]
  [malli.core :as m]
  [org.httpkit.client :as http]
  [ring.util.codec :refer [form-encode]])
 (:import
  [java.util Base64]))

(def redirect-uri "/session/new")

(def config-schema [:or
                    [:map
                     [:client-id :string]
                     [:client-secret :string]
                     [:domain :string]]
                    [:map
                     [:dummy-email :string]]])

(def oauth-config
  (let [config (-> (slurp "config.edn")
                   edn/read-string
                   :oauth)]
    (when config
      (m/assert config-schema config))
    (assoc config
           :redirect-uri (str (:domain config) redirect-uri)
           :nonce-password (str (random-uuid)))))

;; Oauth requires a state parameter.
;; We will encrypt a value and check if it can be decrypted.
;; The value itself doesn't matter.
;; (The encryption we're using generates a unique message each time,
;; which prevents replay attacks.)

(defn nonce-generate []
  (.encodeToString
   (Base64/getUrlEncoder)
   (tempel/encrypt-with-password
    (byte-array [0])
    (:nonce-password oauth-config))))

(defn nonce-check [encrypted]
  (try (boolean
        (tempel/decrypt-with-password
         (.decode (Base64/getUrlDecoder)
                  (.getBytes encrypted))
         (:nonce-password oauth-config)))
       (catch Exception _e
         nil)))

(comment
  (nonce-generate)
  (nonce-check (nonce-generate))
  (nonce-check "random")
 )

(defn request-token-url [{:keys [client-id redirect-uri]}]
  (str "https://github.com/login/oauth/authorize?"
       (form-encode {:state        (nonce-generate)
                     :client_id    client-id
                     :redirect_uri redirect-uri
                     :scope        "user:email"})))

(defn get-access-token [{:keys [client-id redirect-uri client-secret]} code]
  (-> @(http/request
        {:method       :get
         :headers      {"Accept" "application/json"}
         :query-params {:client_id     client-id
                        :client_secret client-secret
                        :code          code
                        :redirect_uri  redirect-uri}
         :url          (str "https://github.com/login/oauth/access_token")})
      :body
      (json/read-str :key-fn keyword)
      :access_token))

(defn get-email [oauth-config code]
  (let [token (get-access-token oauth-config code)]
    (-> @(http/request
          {:method :get
           :headers {"Authorization" (str "Bearer " token)}
           :url "https://api.github.com/user/emails"})
        :body
        (json/read-str :key-fn keyword)
        (->> (filter :primary)
             first
             :email))))

(defn get-or-create-user! [conn email]
  (or (d/q '[:find ?id .
             :in $ ?email
             :where
             [?user :user/email ?email]
             [?user :user/id ?id]]
           (d/db conn)
           email)
      (let [id (random-uuid)]
        (d/transact! conn [{:user/id    id
                            :user/email email}])
        id)))

(defn prod-handler [req]
  (when (and (= :get (:request-method req))
             (= redirect-uri (:uri req)))
    (if-let [code (get-in req [:params :code])]
      (if (nonce-check (get-in req [:params :state]))
        (if-let [email (get-email oauth-config code)]
          (if-let [user-id (get-or-create-user!
                            (:db-conn req)
                            email)]
            {:status  302
             :headers {"Location" "/"}
             :session {:user-id user-id}}
            {:status  401
             :headers {"Content-Type" "text/plain"}
             :body    "User denied"})
          {:status  401
           :headers {"Content-Type" "text/plain"}
           :body    "User could not be authenticated with Google"})
        {:status  401
         :headers {"Content-Type" "text/plain"}
         :body    "Oauth state incorrect"})
      {:status  302
       :headers {"Location" (request-token-url oauth-config)}})))

(defn dev-handler [req]
 (when (and (= :get (:request-method req))
            (= redirect-uri (:uri req)))
   (let [user-id (get-or-create-user! (:db-conn req) (:dummy-email oauth-config))]
     {:status 302
     :headers {"Location" "/"}
     :session {:user-id user-id}})))

(defn handler [req]
  (if (:dummy-email oauth-config)
    (dev-handler req)
    (prod-handler req)))
