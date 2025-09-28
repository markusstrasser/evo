(ns kernel.derive.registry
  "Derivation pass registry - replaces monolithic derivation with named passes.

   Breaks derivation into independent, ordered passes that can be toggled,
   timed, and reasoned about locally. Replaces derive-full with a declarative
   registry of named passes with explicit dependencies.

   Usage:
     (run db)           ; Run all enabled passes in dependency order
     (run db {:only #{:parent-id-of :index-of}})  ; Run specific passes
     (timing-run db)    ; Run with timing information

   Pass structure:
     {:id :pass-name
      :after #{:dependency-pass-1 :dependency-pass-2}
      :run (fn [db] ...updated-db...)}

   Each pass receives a db and returns an updated db with new :derived data."
  (:require [medley.core :as medley]))

(def ^:private passes
  "Registry of derivation passes with explicit dependencies."
  [{:id :parent-id-of
    :after #{}
    :doc "Derive parent-id map from child adjacency lists"
    :run (fn [db]
           (let [parent-id-of (reduce-kv
                                (fn [m parent-id child-ids]
                                  (reduce #(assoc %1 %2 parent-id) m child-ids))
                                {}
                                (:child-ids/by-parent db))]
             (assoc-in db [:derived :parent-id-of] parent-id-of)))}

   {:id :index-of
    :after #{}  ; Can run independently of parent-id-of
    :doc "Derive sibling index map"
    :run (fn [db]
           (let [index-of (into {} (mapcat (fn [[_ child-ids]]
                                             (map-indexed (fn [idx child] [child idx]) child-ids))
                                           (:child-ids/by-parent db)))]
             (assoc-in db [:derived :index-of] index-of)))}

   {:id :child-ids-of
    :after #{}
    :doc "Ensure every node has child-ids vector (possibly empty)"
    :run (fn [db]
           (let [nodes (:nodes db)
                 adj (:child-ids/by-parent db)
                 all-ids (set (concat (keys nodes)
                                      (keys adj)
                                      (mapcat identity (vals adj))))
                 child-ids-of (into {}
                                    (for [id all-ids]
                                      [id (vec (get adj id []))]))]
             (assoc-in db [:derived :child-ids-of] child-ids-of)))}

   {:id :preorder
    :after #{:child-ids-of}
    :doc "Compute preorder traversal"
    :run (fn [db]
           (let [child-ids-of (get-in db [:derived :child-ids-of])
                 roots (or (:roots db) ["root"])
                 preorder (loop [result []
                                stack (vec (reverse roots))]
                            (if (empty? stack)
                              result
                              (let [node (peek stack)
                                    children (get child-ids-of node [])]
                                (recur (conj result node)
                                       (into (pop stack) (reverse children))))))]
             (assoc-in db [:derived :preorder] preorder)))}

   {:id :pre-post
    :after #{:child-ids-of}
    :doc "Compute pre/post intervals for efficient subtree queries"
    :run (fn [db]
           (let [child-ids-of (get-in db [:derived :child-ids-of])
                 roots (or (:roots db) ["root"])
                 {:keys [pre post id-by-pre]}
                 (loop [pre {} post {} id-by-pre {} counter 0 stack (mapv vector (reverse roots) (repeat false))]
                   (if (empty? stack)
                     {:pre pre :post post :id-by-pre id-by-pre}
                     (let [[node visited?] (peek stack)
                           rest-stack (pop stack)]
                       (if visited?
                         ;; Post-visit: assign post number
                         (recur pre
                                (assoc post node counter)
                                id-by-pre
                                (inc counter)
                                rest-stack)
                         ;; Pre-visit: assign pre number and push children + post marker
                         (let [children (get child-ids-of node [])
                               new-pre (assoc pre node counter)
                               new-id-by-pre (assoc id-by-pre counter node)]
                           (recur new-pre
                                  post
                                  new-id-by-pre
                                  (inc counter)
                                  (-> rest-stack
                                      (conj [node true])  ; Post marker
                                      (into (mapv vector (reverse children) (repeat false))))))))))]
             (-> db
                 (assoc-in [:derived :pre] pre)
                 (assoc-in [:derived :post] post)
                 (assoc-in [:derived :id-by-pre] id-by-pre))))}])

(defn- topo-sort
  "Topologically sort passes by dependencies."
  [passes]
  (let [pass-map (into {} (map (juxt :id identity)) passes)

        ;; Simple Kahn's algorithm since graph is small
        in-degree (reduce (fn [acc pass]
                            (reduce (fn [acc2 dep]
                                      (update acc2 (:id pass) (fnil inc 0)))
                                    acc
                                    (:after pass)))
                          (zipmap (map :id passes) (repeat 0))
                          passes)

        ;; Start with zero in-degree passes
        queue (into clojure.lang.PersistentQueue/EMPTY
                    (filter #(zero? (in-degree %)) (map :id passes)))

        result (loop [queue queue
                      result []
                      remaining-in-degree in-degree]
                 (if (empty? queue)
                   (if (= (count result) (count passes))
                     result
                     (throw (ex-info "Circular dependency in derivation passes"
                                     {:passes passes :partial-order result})))
                   (let [current (peek queue)
                         current-pass (pass-map current)
                         new-result (conj result current-pass)
                         new-queue (pop queue)

                         ;; Update in-degrees for passes that depend on current
                         dependents (filter #(contains? (:after %) current) passes)
                         new-in-degree (reduce (fn [acc dep]
                                                 (update acc (:id dep) dec))
                                               remaining-in-degree
                                               dependents)

                         ;; Add newly zero in-degree passes to queue
                         newly-ready (filter #(zero? (new-in-degree (:id %))) dependents)
                         updated-queue (into new-queue (map :id newly-ready))]

                     (recur updated-queue new-result new-in-degree))))]
    result))

(defn run
  "Run derivation passes in dependency order.

   Options:
   - :only #{:pass-id ...} - run only specified passes (plus dependencies)
   - :exclude #{:pass-id ...} - exclude specified passes"
  ([db] (run db {}))
  ([db {:keys [only exclude] :or {exclude #{}}}]
   (let [;; If :only is specified, need to include dependencies too
         passes-with-deps (if only
                            (let [pass-map (into {} (map (juxt :id identity)) passes)
                                  needed (loop [queue (seq only)
                                               result #{}]
                                           (if (empty? queue)
                                             result
                                             (let [current (first queue)
                                                   rest-queue (rest queue)]
                                               (if (result current)
                                                 (recur rest-queue result)
                                                 (let [current-pass (pass-map current)
                                                       deps (:after current-pass)]
                                                   (recur (concat rest-queue deps)
                                                          (conj result current)))))))]
                              (filter #(contains? needed (:id %)) passes))
                            passes)
         active-passes (cond->> passes-with-deps
                         exclude (remove #(contains? exclude (:id %))))
         ordered-passes (topo-sort active-passes)]
     (reduce (fn [db pass]
               ((:run pass) db))
             db
             ordered-passes))))

(defn timing-run
  "Run derivation passes with timing information.

   Returns {:db updated-db :timings [{:id :pass-name :ms elapsed-time} ...]}"
  ([db] (timing-run db {}))
  ([db opts]
   (let [active-passes (cond->> passes
                         (:only opts) (filter #(contains? (:only opts) (:id %)))
                         (:exclude opts) (remove #(contains? (:exclude opts) (:id %))))
         ordered-passes (topo-sort active-passes)
         start-time #?(:clj (System/nanoTime) :cljs (js/Date.now))

         {:keys [final-db timings]}
         (reduce (fn [{:keys [final-db timings]} pass]
                   (let [pass-start #?(:clj (System/nanoTime) :cljs (js/Date.now))
                         updated-db ((:run pass) final-db)
                         pass-end #?(:clj (System/nanoTime) :cljs (js/Date.now))
                         elapsed-ms #?(:clj (/ (- pass-end pass-start) 1000000.0)
                                       :cljs (- pass-end pass-start))]
                     {:final-db updated-db
                      :timings (conj timings {:id (:id pass) :ms elapsed-ms})}))
                 {:final-db db :timings []}
                 ordered-passes)]

     {:db final-db :timings timings})))

(defn enabled-passes
  "Get list of currently enabled pass IDs."
  []
  (mapv :id passes))

(defn describe-pass
  "Get description of a specific pass."
  [pass-id]
  (first (filter #(= pass-id (:id %)) passes)))

(comment
  ;; REPL usage examples:

  ;; Basic derivation
  (def test-db {:nodes {"root" {:type :root} "child" {:type :div}}
                :child-ids/by-parent {"root" ["child"]}})
  (run test-db)  ; => db with full :derived data

  ;; Partial derivation
  (run test-db {:only #{:parent-id-of :index-of}})

  ;; Timing analysis
  (timing-run test-db)  ; => {:db ... :timings [{:id :parent-id-of :ms 0.1} ...]}

  ;; Pass inspection
  (enabled-passes)  ; => [:parent-id-of :index-of :child-ids-of :preorder :pre-post]
  (describe-pass :parent-id-of)  ; => {:id :parent-id-of :after #{} ...}
  )