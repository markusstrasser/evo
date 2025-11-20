(ns spec-registry-macros
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defmacro inline-registry []
  (let [f (io/file "resources/specs.edn")]
    (edn/read-string (slurp f))))
