(ns core.time
  "Centralized time utilities for cross-platform consistency."
  #?(:clj (:import (java.time Instant)
                   (java.util Date Calendar))
     :cljs (:import goog.string)))

(defn now-ms
  "Get current time in milliseconds."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn date-from-ms
  "Create date object from milliseconds."
  [ms]
  #?(:clj (Date. ms)
     :cljs (js/Date. ms)))

(defn now-iso-string
  "Get current time as ISO 8601 string."
  []
  #?(:clj (str (Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn today-start-ms
  "Get timestamp for start of today (midnight) in local timezone."
  []
  #?(:clj (let [cal (doto (Calendar/getInstance)
                      (.set Calendar/HOUR_OF_DAY 0)
                      (.set Calendar/MINUTE 0)
                      (.set Calendar/SECOND 0)
                      (.set Calendar/MILLISECOND 0))]
                (.getTimeInMillis cal))
     :cljs (let [now (js/Date.)
                 year (.getFullYear now)
                 month (.getMonth now)
                 date (.getDate now)]
             (.getTime (js/Date. year month date 0 0 0 0)))))