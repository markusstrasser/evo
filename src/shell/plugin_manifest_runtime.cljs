(ns shell.plugin-manifest-runtime
  (:require [cljs.reader :as reader]
            [shadow.resource :as resource]))

(def manifest
  (-> (resource/inline "plugins.edn")
      reader/read-string))
