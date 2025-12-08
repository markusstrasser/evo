(ns shell.demo-data
  (:require [cljs.reader :as reader]
            [shadow.resource :as resource]))

(def ops
  (-> (resource/inline "seed-data.edn")
      reader/read-string))
