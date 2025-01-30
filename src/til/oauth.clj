(ns til.oauth
 (:require
  [clojure.data.json :as json]
  [clojure.edn :as edn]
  [datalevin.core :as d]
  [malli.core :as m]
  [org.httpkit.client :as http]
  [ring.util.codec :refer [form-encode]]))

(def redirect-uri "/session/new")

(def config-schema [:map
                    [:client-id :string]
                    [:client-secret :string]
                    [:domain :string]])

(def oauth-config
  (let [config (-> (slurp "config.edn")
                   edn/read-string
                   :oauth)]
    (when config
      (m/assert config-schema config))
    (assoc config :redirect-uri (str (:domain config) redirect-uri))))

(defn request-token-url [{:keys [client-id redirect-uri]}]
  (str "https://github.com/login/oauth/authorize?"
       (form-encode {; :state to-do
                     :client_id client-id
                     :redirect_uri redirect-uri
                     :scope "user:email"})))

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

(defn handler [req]
  (when (and (= :get (:request-method req))
             (= redirect-uri (:uri req)))
    (if-let [code (get-in req [:params :code])]
      ; TODO: Verify state
      (if-let [email (get-email oauth-config code)]
        (if-let [user-id (get-or-create-user!
                          (:db-conn req)
                          email)]
          {:status  302
           :headers {"Location" "/"}
           :session {:user-id user-id}}
          {:status 401
           :body {:error "User denied"}})
        {:status 401
         :body {:error "User could not be authenticated with Google"}})
      {:status 302
       :headers {"Location" (request-token-url oauth-config)}})))
