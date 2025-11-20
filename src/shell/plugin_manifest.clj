(ns shell.plugin-manifest
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defonce ^:private plugin-names
  (-> "plugins.edn" io/resource slurp edn/read-string))

(defmacro require-specs []
  `(list ~@(for [sym plugin-names]
             `[~sym])))

(defmacro plugin-symbols []
  `~plugin-names)
