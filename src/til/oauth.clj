(ns til.oauth
 (:require
  [til.config :as config]
  [clojure.data.json :as json]
  [clojure.edn :as edn]
  [datalevin.core :as d]
  [taoensso.tempel :as tempel]
  [org.httpkit.client :as http]
  [ring.util.codec :refer [form-encode]])
 (:import
  [java.util Base64]))

(def redirect-uri "/session/new")

(def nonce (str (random-uuid)))

(defn oauth-config []
  (let [config (config/get :oauth)]
    (assoc config
           :redirect-uri (str (:domain config) redirect-uri)
           :nonce-password nonce)))

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
    (:nonce-password (oauth-config)))))

(defn nonce-check [encrypted]
  (try (boolean
        (tempel/decrypt-with-password
         (.decode (Base64/getUrlDecoder)
                  (.getBytes encrypted))
         (:nonce-password (oauth-config))))
       (catch Exception _e
         nil)))

(comment
  (nonce-generate)
  (nonce-check (nonce-generate))
  (nonce-check "random"))
 

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

(defn get-user [oauth-config code]
  (let [token (get-access-token oauth-config code)]
    (-> @(http/request
          {:method :get
           :headers {"Authorization" (str "Bearer " token)}
           :url "https://api.github.com/user/user"})
        :body
        (json/read-str :key-fn keyword))))

(defn get-or-create-user! [conn {:keys [email name avatar-url]}]
  (or (d/q '[:find ?id .
             :in $ ?email
             :where
             [?user :user/email ?email]
             [?user :user/id ?id]]
           (d/db conn)
           email)
      (let [id (random-uuid)]
        (d/transact! conn [{:user/id    id
                            :user/email email
                            :user/name name
                            :user/avatar-url avatar-url}])
        id)))

(defn prod-handler [req]
  (when (and (= :get (:request-method req))
             (= redirect-uri (:uri req)))
    (if-let [code (get-in req [:params :code])]
      (if (nonce-check (get-in req [:params :state]))
        (if-let [email (get-email (oauth-config) code)]
          (let [{:keys [login avatar_url]} (get-user (oauth-config) code)]
            (if-let [user-id (get-or-create-user!
                              (:db-conn req)
                              {:email email
                               :name login
                               :avatar-url avatar_url})]
              {:status  302
               :headers {"Location" "/"}
               :session {:user-id user-id}}
              {:status  401
               :headers {"Content-Type" "text/plain"}
               :body    "User denied"}))
          {:status  401
           :headers {"Content-Type" "text/plain"}
           :body    "User could not be authenticated with Google"})
        {:status  401
         :headers {"Content-Type" "text/plain"}
         :body    "Oauth state incorrect"})
      {:status  302
       :headers {"Location" (request-token-url (oauth-config))}})))

(defn dev-handler [req]
 (when (and (= :get (:request-method req))
            (= redirect-uri (:uri req)))
   (let [user-id (get-or-create-user! (:db-conn req) (:dummy-user (oauth-config)))]
     {:status 302
      :headers {"Location" "/"}
      :session {:user-id user-id}})))

(defn handler [req]
  (if (:dummy-user (oauth-config))
    (dev-handler req)
    (prod-handler req)))
