(ns grunf.core
  "gurnf.core"
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:use [clojure.template :only [do-template]]
        clj-logging-config.log4j)        
  (:import [java.net Socket]
           [java.io PrintWriter]))

(declare private-fetch)

(defprotocol GrunfOutputAdapter
  "A protocol for grunf instance to log or push data to other service"
  (log-success [this] "http 2xx status")
  (log-redirect [this] "http 3xx status")
  (log-client-error [this] "http 4xx status")
  (log-server-error [this] "http 5xx status")
  (log-unknown-error [this] "link error or unknown status code"))

(defprotocol SetupLog4j
  "A protocol that Log4j needed"
  (init-logger [this] "runs set-loggers!"))

(defmacro with-log4j [this & body] ;; It should create custom namespace
  `(with-logging-config
     [(.namespace ~this) {:level (.level ~this)}]
     ~@body))

;; continuation passing style
(deftype Log4j [namespace level pattern out]
  SetupLog4j
  (init-logger [this]
    (set-loggers! (.namespace this)
                  {:level (.level this)
                   :pattern (.pattern this)
                   :out (.out this)}))
  GrunfOutputAdapter
  (log-success [this]
    (fn [{{validator :validator url :url start :start} :opts
          status :status
          body :body}]
      (log/info status url
                  "response time (msec):" (- (System/currentTimeMillis) start))
        (when-not (validator body)
          (log/error status url "-- validate failed"))
      ;; (with-log4j this
      ;;   )
      ))
  (log-redirect [this]
    (fn [{{old-url :url} :opts
          {new-url :location} :headers
          status :status}]
      (log/info status "redirect" old-url "->" new-url)
     ;; (with-log4j this
     ;;    )
     ))
  (log-client-error [this] (log-unknown-error this))
  (log-server-error [this] (log-unknown-error this))
  (log-unknown-error [this]
    (fn [{error :error
          status :status
          headers :headers
          {url :url} :opts}]
      (log/error status url error headers)
      ;; (with-log4j this
      ;;   )
      )))

(deftype Graphite [namespace host port]
  GrunfOutputAdapter
  (log-success [this]
    (fn [{{validator :validator start :start} :opts
          body :body}]
      (binding [*out* (-> (Socket. (.host this) (.port this))
                          (.getOutputStream)
                          (PrintWriter.))]
        (do-template [type value]
                     (println (str (.namespace this) "." type)
                              value
                              (-> start (/ 1000) (int)))
                     "response_time" (- (System/currentTimeMillis) start)
                     "response_size" (count (map int body))
                     "error" (if (validator body) 0 1)))))
  (log-redirect [this] (fn [_]))
  (log-client-error [this] (log-unknown-error this))
  (log-server-error [this] (log-unknown-error this))
  (log-unknown-error [this]
    (fn [{{start :start} :opts}]
      (binding [*out* (-> (Socket. (.host this) (.port this))
                          (.getOutputStream)
                          (PrintWriter.))]
        (println (str (.namespace this) ".error") 1 start)))))

(defn fetch [{:keys [url interval method http-options validator graphite-ns]
              :or {interval 5000,
                   method :get,
                   validator '(constantly true)
                   graphite-ns ""}}
             adapters]
  (letfn [(http-method [method]
            (case method
              :get http/get
              :post http/post
              :put http/put
              :delete http/delete))
          (callback [{:keys [error status opts] :as context}]
            (if error
              ((apply juxt (map log-unknown-error adapters)) context)
              (case (quot status 100)
                2 ((apply juxt (map log-success adapters)) context)
                3 (do ((apply juxt (map log-redirect adapters)) context)
                      ((http-method method)
                       (-> context :headers :location) opts callback))
                4 ((apply juxt (map log-client-error adapters)) context)
                5 ((apply juxt (map log-server-error adapters)) context)
                ((apply juxt (map log-unknown-error adapters)) context))))]
    (loop [start (System/currentTimeMillis)]
      ((http-method method) url (assoc http-options
                                  :validator (eval validator)
                                  :as :text
                                  :start start) callback)
      (Thread/sleep interval)
      (recur (System/currentTimeMillis)))))
