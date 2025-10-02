(ns dev.eval.pairwise
  "Pairwise duel execution and comparison graph construction.
   Builds dense comparison graphs with full metadata."
  (:require [dev.eval.prompts-v3 :as prompts]
            #?(:clj [dev.eval.judges-api :as judges])))

(defn duel!
  "Execute a single pairwise comparison.
   Returns edge record with verdict, criteria, and metadata."
  [{:keys [provider rubric context]} item-l item-r flags]
  (let [prompt (prompts/pairwise-prompt
                {:left item-l
                 :right item-r
                 :rubric rubric
                 :context context
                 :flags flags})
        #?@(:clj [output (judges/judge! provider prompt)]
            :cljs [output {:verdict "left" :criteria {:test 1.0}}])]
    {:edge [(:id item-l) (:id item-r)]
     :verdict (keyword (:verdict output))
     :criteria (:criteria output)
     :confidence (get output :confidence 0.5)
     :provider provider
     :flags flags
     :ts #?(:clj (System/currentTimeMillis)
            :cljs (.now js/Date))}))

(defn build-graph
  "Build comparison graph from pairwise duels.
   Returns map of edges with metadata."
  [duels]
  (into {}
        (map (fn [duel]
               [(:edge duel) (dissoc duel :edge)]))
        duels))

(defn graph-stats
  "Compute statistics about the comparison graph."
  [graph]
  (let [edges (keys graph)
        nodes (into #{} (mapcat identity edges))
        degree-map (reduce (fn [acc [l r]]
                             (-> acc
                                 (update l (fnil inc 0))
                                 (update r (fnil inc 0))))
                           {}
                           edges)]
    {:node-count (count nodes)
     :edge-count (count edges)
     :min-degree (when (seq degree-map) (apply min (vals degree-map)))
     :max-degree (when (seq degree-map) (apply max (vals degree-map)))
     :avg-degree (when (seq degree-map) (double (/ (reduce + (vals degree-map))
                                                   (count degree-map))))
     :degree-map degree-map}))
