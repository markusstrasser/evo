(ns dev.tooling
  "Minimal dev tooling stub.

   The full dev.tooling namespace was removed in consolidation,
   but some files still reference log-dispatch! for intent logging.
   This stub provides the minimum needed for compilation."
  (:require [clojure.pprint :as pprint]))

(defn log-dispatch!
  "Stub for intent dispatch logging.
   Does nothing - full logging was consolidated into debugging skills."
  ([intent db-before db-after]
   nil)
  ([intent db-before db-after hotkey]
   nil))

;; Stubs for missing functions used by devtools component

(defn format-intent [intent]
  (str (:type intent)))

(defn copy-to-clipboard! [text]
  (when (and (exists? js/navigator)
             (exists? js/navigator.clipboard))
    (.writeText js/navigator.clipboard text)))

(defn format-entry-with-diff [entry current-page-id]
  (with-out-str (pprint/pprint entry)))

(defn get-log []
  [])

(defn format-full-log []
  "Log not available")

(defn clear-log! []
  nil)

(defn extract-hiccup-tree [db page-id]
  nil)

(defn format-hiccup-diff [before after]
  "Diff not available")

(defn format-state-snapshot [db]
  (str (count (:nodes db)) " nodes"))