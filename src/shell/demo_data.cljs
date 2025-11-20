(ns shell.demo-data
  (:require [cljs.reader :as reader]
            [shadow.resource :as resource]))

(def ops
  (-> (resource/inline "demo-pages.edn")
      reader/read-string))
