(ns til.config
  (:refer-clojure :exclude [get])
  (:require
   [clojure.edn :as edn]
   [malli.core :as m]))

(def schema
  [:map
   [:http-port :int]
   [:environment [:enum :prod :dev]]
   [:cookie-secret [:vector {:min 16 :max 16} :int]]
   [:oauth [:or
            [:map
             [:client-id :string]
             [:client-secret :string]
             [:domain :string]]
            [:map
             [:dummy-user [:map
                           [:email :string]
                           [:name :string]
                           [:avatar-url :string]]]]]]])

(def config
  (delay
    (let [config (some-> (slurp "config.edn")
                         edn/read-string
                         (update :http-port (fn [port] (or (some-> (System/getenv "PORT")
                                                                   parse-long)
                                                           port))))]
     (m/assert schema config)
     config)))

(defn get [k]
  (@config k))

(comment
  ;; for generating :cookie-secret
  (vec (crypto.random/bytes 16)))

