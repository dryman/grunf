(ns grunf.core
  "gurnf.core"
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            )
  (:use overtone.at-at)
  (:import [java.net URLEncoder]
           [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit ThreadPoolExecutor]))

(defprotocol GrunfOutputAdapter
  "A protocol for grunf instance to log or push data to other service"
  (log-success [this] "http 2xx status")
  (log-redirect [this] "http 3xx status")
  (log-client-error [this] "http 4xx status")
  (log-server-error [this] "http 5xx status")
  (log-validate-error [this] "validation failed")
  (log-unknown-error [this] "link error or unknown status code"))

(defonce my-pool (mk-pool))

(defn- http-method
  "take http-method names and return actual instance"
  [method]
  (case method
    :get http/get
    :post http/post
    :put http/put
    :delete http/delete))

(defn- url-encode [s] (URLEncoder/encode (str s) "utf8"))

(defn- query-string
  "Returns URL-encoded query string for given params map."
  [m]
  (let [param (fn [k v]  (str (url-encode (name k)) "=" (url-encode v)))
        join  (fn [strs] (str/join "&" strs))]
    (join (for [[k v] m] (if (sequential? v)
                           (join (map (partial param k) (or (seq v) [""])))
                           (param k v))))))



(defn fetch [{:keys [url interval method http-options validator graphite-ns params-fn]
              :or {interval 5000,
                   method :get,
                   validator '(constantly true)
                   graphite-ns ""
                   params-fn '(repeat nil)}
              :as input-options}
             adapters]
  (letfn
      [(callback
         [{:keys [error status body opts] :as context}]
         (let [log-wrapper (fn [f] ((apply juxt (map f adapters)) context))]
           (if error
             (log-wrapper log-unknown-error)
             (case (quot status 100)
               2 (if-let [v (:validator opts)]
                   (if (= (:ad-hoc opts) :js-trace)
                     (try
                       (if-let [t-url (re-find #"(?<=src=').*(?='></script>)" body)]
                         (do
                           (println t-url)
                           ((apply juxt (map log-redirect adapters)) (assoc-in context [:headers :location] t-url))
                           ((http-method method) t-url opts callback))
                         (log-wrapper log-success))
                       (catch Exception e
                         (log-wrapper log-unknown-error)))
                     (try (if (v body)
                            (log-wrapper log-success)
                            (log-wrapper log-validate-error))
                          (catch Exception e
                            (log-wrapper log-validate-error))))
                   (log-wrapper log-success))
               3 (do (log-wrapper log-redirect)
                     ((http-method method)
                      (-> context :headers :location) opts callback))
               4 (log-wrapper log-client-error)
               5 (log-wrapper log-server-error)
               (log-wrapper log-unknown-error)))))]    
    (let [validator-exec (eval validator)
          params-seq (atom (eval params-fn))]
      (every interval
             (fn []
               (let [start (System/currentTimeMillis)
                     url (if (first @params-seq) ; url
                           (if (neg? (.indexOf ^String url (int \?)))
                             (str url "?" (query-string (first @params-seq)))
                             (str url "&" (query-string (first @params-seq))))
                           url)]
                 ((http-method method)
                  url
                  (assoc http-options   ; http-options
                    :ad-hoc (:ad-hoc input-options)
                    :validator validator-exec
                    :validator-source validator
                    :as :text
                    :start start)
                  callback)
                 (swap! params-seq rest)))
             my-pool))))
