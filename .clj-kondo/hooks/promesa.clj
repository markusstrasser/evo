(ns hooks.promesa
  (:require [clj-kondo.hooks-api :as api]))

(defn let [{:keys [node]}]
  (let [[bindings & body] (rest (:children node))
        new-node (api/list-node
                  (list*
                   (api/token-node 'let)
                   bindings
                   body))]
    {:node new-node}))
