(ns grunf.adapter.riemann
  "riemann adapter"
  (:use riemann.client
        [grunf.core :only [GrunfOutputAdapter pool]])
  (:import [java.io IOException]
           [java.net ConnectException UnknownHostException]))

(deftype RiemannAdapter [client tags]
  GrunfOutputAdapter
  (log-success [this]
    (fn [{{start :start
           url :url
           ttl :interval
           status :status} :opts}]
      (let [now (int (/ (System/currentTimeMillis) 1000))
            diff (- (System/currentTimeMillis) start)]
        (.execute pool
                  (fn []
                    (try
                      (send-event (.client this)
                                  {:service url
                                   :state (str "ok: " status)
                                   :time now
                                   :tags (merge tags "grunf")
                                   :description "query time"
                                   :metric diff
                                   :ttl (/ (* ttl 5) 1000)
                                   })
                      (catch IOException e)))))))
  (log-validate-error [this]
    (fn [{{start :start
           url :url
           ttl :interval
           status :status} :opts}]
      (let [now (int (/ (System/currentTimeMillis)))
            diff (- (System/currentTimeMillis) start)]
        (.execute pool
                  (fn []
                    (try (send-event (.client this)
                                     {:service url
                                      :state (str "warning: " status)
                                      :time now
                                      :tags (merge tags "grunf")
                                      :description "validation error"
                                      :metric diff
                                      :ttl (/ (* ttl 5) 1000)
                                      })
                         (catch IOException e)))))))
  (log-redirect [this] (fn [_]))
  (log-client-error [this] (grunf.core/log-server-error this))
  (log-server-error [this]
    (fn [{{start :start
           url :url
           headers :headers
           ttl :interval} :opts
           error :error
           status :status}]
      (let [now (int (/ (System/currentTimeMillis)))
            diff (- (System/currentTimeMillis) start)]
        (.execute pool
                  (fn []
                    (try (send-event (.client this)
                                     {:service url
                                      :state (str "error: " status)
                                      :time (int (/ (System/currentTimeMillis) 1000))
                                      :tags (merge tags "grunf")
                                      :description (str "error:" error "\n" headers)
                                      :metric (- (System/currentTimeMillis) start)
                                      :ttl (/ (* ttl 2) 1000)
                                      })
                         (catch IOException e)))))))
  (log-unknown-error [this]
    (fn [{{start :start
           url :url
           ttl :interval} :opts
           error :error
           status :status}]
      (let [now (int (/ (System/currentTimeMillis)))
            diff (- (System/currentTimeMillis) start)
            state (cond 
                    (instance? java.net.UnknownHostException error)
                    "error: UnkownHostException"
                    (instance? java.net.ConnectException error)
                    "error: ConnectException"
                    :else
                    "error: nil")]
        (.execute pool
                  (fn []
                    (try (send-event (.client this)
                                     {:service url
                                      :state state
                                      :time (int (/ (System/currentTimeMillis) 1000))
                                      :tags (merge tags "grunf")
                                      :description (str "error:" error)
                                      :metric (- (System/currentTimeMillis) start)
                                      :ttl (/ (* ttl 2) 1000)
                                      })
                         (catch IOException e)))))))
  )

