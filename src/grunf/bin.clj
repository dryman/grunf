
; grunf | simple http monitoring loop

(ns grunf.bin
  "grunf.main"
  (:require [org.httpkit.client :as http]
            [grunf.core :as grunf])
  (:use [clojure.tools.cli :only [cli]]
        clj-logging-config.log4j)
  (:import [org.apache.log4j DailyRollingFileAppender EnhancedPatternLayout])
  (:gen-class))


(def log-pattern "%d{ISO8601}{GMT} [%-5p] [%t] - %m%n")
(def cli-options
  [["-c" "--config" "Path to the config file"]
   ["-h" "--[no-]help" "Print this message" :default false]
   ["--log" "log path for log4j. If not specified, log to console"]
   ["--log-level" "log level for log4j, (fatal|error|warn|info|debug)" :default "debug"]])

(defn- verify-config [config-array]
  (assert (= (type config-array) clojure.lang.PersistentVector)
          "Config should be an clojure array")
  (doseq [config-array-element config-array]
    (assert (= (type config-array-element) clojure.lang.PersistentArrayMap)
            "Each element in config array should be a map")
    (assert (:url config-array-element) "Must have :url in config map"))
  config-array) ;; return the original config-array

(defn -main [& argv]
  (let [[options args banner]
        (apply cli argv cli-options)]
    (when (or (:help options) (nil? argv))
      (println banner)
      (System/exit 0))
    (set-loggers! "grunf.core"
                  {:level (-> options :log-level keyword)
                   :pattern log-pattern
                   :out (if (:log options)                          
                          (DailyRollingFileAppender. (EnhancedPatternLayout. log-pattern)
                                                     (:log options)
                                                     "'.'yyyy-MM-dd")
                          :console)})
    (pmap grunf/fetch
          (try
            (->> (:config options)
                 (slurp)
                 (read-string)
                 (verify-config))
            (catch java.io.IOException e
              (println "Config file not found:" (:config options))
              (System/exit -1))
            (catch AssertionError e ;; clojure assertion
              (println "config file error:" e)
              (System/exit -1))
            (catch Exception e
              (println "Config file error" e)
              (System/exit -1))))))
