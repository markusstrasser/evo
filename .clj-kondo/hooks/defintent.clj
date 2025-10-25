(ns hooks.defintent
  (:require [clj-kondo.hooks-api :as api]))

(defn defintent
  "Hook for defintent macro to help clj-kondo understand the expansion.

   defintent expands to:
   (defmethod intent->ops :intent-kw [db intent] ...ops...)"
  [{:keys [node]}]
  (let [[intent-kw config-map] (rest (:children node))
        config (when (api/map-node? config-map)
                 (into {} (map (fn [[k v]]
                                 [(api/sexpr k) v])
                               (partition 2 (:children config-map)))))
        sig (:sig config)
        ops (:ops config)]
    (when (and sig ops)
      (let [sig-children (:children sig)
            [db-sym intent-sym] sig-children
            ;; Generate a defmethod-like expansion for clj-kondo
            new-node (api/list-node
                      (list
                       (api/token-node 'defmethod)
                       (api/token-node 'kernel.intent/intent->ops)
                       intent-kw
                       (api/vector-node [db-sym intent-sym])
                       ops))]
        {:node new-node}))))
