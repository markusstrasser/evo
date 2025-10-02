(ns scripts.utils.json
  "JSON and EDN parsing utilities for Clojure/Babashka scripts."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]))

(defn parse-json
  "Parse JSON string to Clojure data with keyword keys."
  [s]
  (json/parse-string s true))

(defn generate-json
  "Generate JSON string from Clojure data. Pass {:pretty? true} for formatting."
  [data & [{:keys [pretty?]}]]
  (if pretty?
    (json/generate-string data {:pretty true})
    (json/generate-string data)))

(defn read-json
  "Read JSON from file with keyword keys."
  [path]
  (parse-json (slurp path)))

(defn write-json
  "Write Clojure data as JSON to file. Pretty-prints by default."
  [path data & [{:keys [pretty?] :or {pretty? true}}]]
  (spit path (generate-json data {:pretty? pretty?})))

(defn parse-edn
  "Parse EDN string to Clojure data."
  [s]
  (edn/read-string s))

(defn read-edn
  "Read EDN from file."
  [path]
  (parse-edn (slurp path)))

(defn write-edn
  "Write Clojure data as EDN to file."
  [path data]
  (spit path (pr-str data)))
