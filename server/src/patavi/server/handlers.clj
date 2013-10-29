(ns patavi.server.handlers
  (:use patavi.server.util)
  (:require [clojure.tools.logging :as log]
            [clojure.string :only [replace split] :as s]
            [clojure.core.async :as async :refer [go <! >! chan]]
            [clj-wamp.server :as wamp]
            [ring.util.response :as resp]
            [org.httpkit.server :as http-kit]
            [patavi.common.util :refer [dissoc-in]]
            [patavi.server.config :refer [config]]
            [patavi.server.service :only [publish available?] :as service]))

(def base (:ws-base-uri config))
(def service-rpc-uri (str base "rpc#"))
(def service-status-uri (str base "status#"))

(defn dispatch-rpc
  [method data]
  (let [listeners [wamp/*call-sess-id*]
        {:keys [updates close results]} (service/publish method data)]
    (try
      (go (loop [update (<! updates)]
            (when ((comp not nil?) update)
              (wamp/emit-event! service-status-uri (:msg update) listeners)
              (recur (<! updates)))))
      @results
      (catch Exception e
        (do
          (log/error e)
          {:error {:uri service-rpc-uri
                   :message (.getMessage e)}})))))

(defn service-run-rpc [method data]
  (if (service/available? method)
    (dispatch-rpc method data)
    {:error {:uri service-rpc-uri
             :message (str "service " method " not available")}}))

(def origin-re (re-pattern (:ws-origin-re config)))

(defn handle-service
  "Returns a http-kit websocket handler with wamp subprotocol"
  [request]
  (wamp/with-channel-validation request channel origin-re
    (wamp/http-kit-handler channel
                           {:on-call {service-rpc-uri service-run-rpc}
                            :on-subscribe {service-status-uri true}
                            :on-publish {service-status-uri true}})))
