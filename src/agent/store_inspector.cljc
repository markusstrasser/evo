(ns agent.store-inspector
  (:require [clojure.set]
            [clojure.string]))

(defn inspect-store
  "Inspect the current store state with optional filtering"
  [store & {:keys [include-keys exclude-keys]}]
  (let [db @store
        all-keys (set (keys db))
        keys-to-show (cond
                       include-keys (clojure.set/intersection all-keys include-keys)
                       exclude-keys (clojure.set/difference all-keys exclude-keys)
                       :else all-keys)]
    {:store-summary {:node-count (count (:nodes db))
                     :selected-count (count (:selected (:view db)))
                     :reference-count (count (:references db))}
     :filtered-data (select-keys db keys-to-show)}))

(defn quick-state-dump
  "Quick dump of key state for debugging"
  [store]
  (let [db @store
        selected (:selected (:view db))
        references (:references db)]
    (str "Nodes: " (count (:nodes db))
         ", Selected: " (count selected) " (" (clojure.string/join ", " selected) ")"
         ", References: " (count references) " entries")))