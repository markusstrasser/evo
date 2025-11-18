(ns fixtures
  "Generic test fixtures and utilities for kernel testing.

   Scope: Generic tree/node structures - NO application-specific logic.
   Use: Core kernel testing, transaction validation, index testing.

   For application-specific fixtures (cards, reviews, etc.), see:
   - anki_fixtures.cljc (Anki review scenarios)

   This file provides:
   - DB builders (make-db, make-blocks)
   - Tree generators (linear, flat, balanced)
   - Node generators (gen-node)
   - Intent helpers (apply-intent-and-interpret, intent-chain)
   - Predefined fixtures (empty-db, simple-tree)"
  (:require [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]))

;; =============================================================================
;; Generic DB builders
;; =============================================================================

(defn make-db
  "Build a kernel db from nodes map and children-by-parent map.
   Automatically derives :derived indices.

   Usage:
     (make-db {\"a\" {:type :div} \"b\" {:type :span}}
              {\"a\" [\"b\"]})"
  [nodes children-by-parent]
  (let [parent-id-of (reduce-kv
                      (fn [m parent-id child-ids]
                        (reduce #(assoc %1 %2 parent-id) m child-ids))
                      {}
                      children-by-parent)
        index-of (reduce-kv
                  (fn [m parent-id child-ids]
                    (merge m (into {} (map-indexed (fn [i id] [id i]) child-ids))))
                  {}
                  children-by-parent)
        roots (reduce disj
                      (set (keys nodes))
                      (apply concat (vals children-by-parent)))]
    {:nodes nodes
     :children-by-parent children-by-parent
     :roots roots
     :derived {:parent-id-of parent-id-of
               :index-of index-of}}))

(defn random-id
  "Generate a random ID string for testing."
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))))

(defn gen-node
  "Generate a node with given type and optional props."
  [type & {:as props}]
  {:type type
   :props props})

;; =============================================================================
;; Tree generators
;; =============================================================================

(defn gen-linear-tree
  "Generate a linear tree: root -> n1 -> n2 -> ... -> nN.
   Returns {:db db :ids [root-id n1-id ... nN-id]}."
  [depth]
  (let [ids (vec (repeatedly (inc depth) random-id))
        nodes (into {} (map #(vector % (gen-node :div)) ids))
        children-by-parent (into {} (map (fn [i]
                                           [(nth ids i) [(nth ids (inc i))]])
                                         (range (dec (count ids)))))
        db (make-db nodes children-by-parent)]
    {:db db :ids ids}))

(defn gen-flat-tree
  "Generate a flat tree: root with N direct children.
   Returns {:db db :root-id id :child-ids [id1 id2 ...]}."
  [num-children]
  (let [root-id (random-id)
        child-ids (vec (repeatedly num-children random-id))
        nodes (assoc (into {} (map #(vector % (gen-node :div)) child-ids))
                     root-id (gen-node :div))
        children-by-parent {root-id child-ids}
        db (make-db nodes children-by-parent)]
    {:db db :root-id root-id :child-ids child-ids}))

(defn gen-balanced-tree
  "Generate a balanced tree with given depth and branching factor.
   Returns {:db db :root-id id}."
  [depth branching-factor]
  (letfn [(build-subtree [d]
            (let [id (random-id)]
              (if (zero? d)
                {:nodes {id (gen-node :div)}
                 :children-by-parent {}
                 :root id}
                (let [children (repeatedly branching-factor #(build-subtree (dec d)))
                      child-ids (mapv :root children)
                      merged-nodes (apply merge {id (gen-node :div)} (map :nodes children))
                      merged-children (apply merge {id child-ids} (mapcat :children-by-parent children))]
                  {:nodes merged-nodes
                   :children-by-parent merged-children
                   :root id}))))]
    (let [{:keys [nodes children-by-parent root]} (build-subtree depth)
          db (make-db nodes children-by-parent)]
      {:db db :root-id root})))

;; =============================================================================
;; Predefined fixtures
;; =============================================================================

(def empty-db
  "Minimal empty database."
  (make-db {} {}))

(def single-node-db
  "Database with a single root node."
  (let [id (random-id)]
    {:db (make-db {id (gen-node :div)} {})
     :id id}))

(def simple-tree
  "Simple 3-node tree: root -> [a, b]."
  (let [root "root"
        a "a"
        b "b"]
    {:db (make-db {root (gen-node :div)
                   a (gen-node :span)
                   b (gen-node :p)}
                  {root [a b]})
     :root-id root
     :child-ids [a b]}))

(defn sample-db
  "Create a minimal database suitable for basic testing.
   Uses generic make-db without kernel-specific root nodes."
  []
  (make-db {} {}))

(defn sample-db-with-roots
  "Create a database with kernel root nodes (:doc, :trash, :session).
   This is the canonical database shape used by the kernel."
  []
  (db/empty-db))

;; =============================================================================
;; Enhanced Test Helpers
;; =============================================================================

;; Note: Validation is automatically enabled in dev.validation when running in browser
;; Tests in Node.js environment don't use validation (it's a no-op stub)

(defn make-blocks
  "Create blocks with sensible defaults. Returns db.

   Usage:
     (make-blocks
       {:a {:text \"First\" :children [:b :c]}
        :b {:text \"Second\"}
        :c {:text \"Third\" :children [:d]}
        :d {:text \"Fourth\"}})"
  [block-map]
  (let [ops (for [[id {:keys [text children] :or {text "" children []}}] block-map]
              {:op :create-node :id (name id) :type :block :props {:text text}})
        place-ops (for [[id {:keys [children]}] block-map
                        child children]
                    {:op :place :id (name child) :under (name id) :at :last})]
    (:db (tx/interpret (db/empty-db) (concat ops place-ops)))))

(defn apply-intent-and-interpret
  "Apply intent and return resulting db (convenience helper)."
  [db intent]
  (let [{:keys [ops]} (intent/apply-intent db intent)]
    (:db (tx/interpret db ops))))

(defn intent-chain
  "Apply multiple intents in sequence, returning final db.

   Usage:
     (intent-chain db
       {:type :enter-edit :block-id \"a\"}
       {:type :update-content :block-id \"a\" :text \"new\"}
       {:type :exit-edit})"
  [db & intents]
  (reduce apply-intent-and-interpret db intents))

(defn snapshot-db
  "Return readable snapshot of db for printing/comparing."
  [db & [node-ids]]
  (let [nodes (if node-ids
                (select-keys (:nodes db) node-ids)
                (:nodes db))]
    {:nodes nodes
     :tree (:children-by-parent db)
     :derived (select-keys (:derived db) [:parent-id-of :index-of])}))

(comment
  ;; Build a custom tree
  (make-db {"root" {:type :div}
            "child" {:type :span}}
           {"root" ["child"]})

  ;; Generate random trees
  (gen-linear-tree 5)
  (gen-flat-tree 10)
  (gen-balanced-tree 3 2)

  ;; Use predefined fixtures
  (:db simple-tree)

  ;; Build blocks with text
  (make-blocks {:doc {:children [:a :b]}
                :a {:text "First"}
                :b {:text "Second"}})

  ;; Chain intents
  (intent-chain (make-blocks {:a {:text "Hello"}})
                {:type :enter-edit :block-id "a"}
                {:type :update-content :block-id "a" :text "Hello World"})
  )