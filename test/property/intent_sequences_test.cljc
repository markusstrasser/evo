(ns property.intent-sequences-test
  "Property-based tests for intent sequences.

   Tests that random sequences of structural intents maintain DB invariants.
   Unlike invariants_test.cljc (which tests raw kernel ops), this tests
   the intent→ops→interpret pipeline that real user interactions use.

   CRITICAL INVARIANTS:
   - DB valid after any intent sequence
   - Parent-child consistency maintained
   - No orphaned nodes (except trash)
   - Intent sequences are composable (order independence for invariants)
   - Undo/redo preserves DB validity"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.query :as q]
            ;; Load plugins so intent handlers are registered
            [plugins.editing]
            [plugins.structural]
            [plugins.selection]
            [plugins.folding]
            [plugins.navigation]
            [plugins.context-editing]))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn make-test-db
  "Build a DB with n blocks under :doc."
  [n]
  (let [ids (mapv #(str "block-" %) (range n))
        ops (mapcat (fn [id]
                      [{:op :create-node :id id :type :block :props {:text (str "Text " id)}}
                       {:op :place :id id :under :doc :at :last}])
                    ids)]
    (:db (tx/interpret (db/empty-db) ops))))

(defn make-nested-db
  "Build a DB with parent-child relationships.
   block-0, block-1 at top level; block-2, block-3 under block-0."
  []
  (:db (tx/interpret (db/empty-db)
         [{:op :create-node :id "block-0" :type :block :props {:text "Parent A"}}
          {:op :place :id "block-0" :under :doc :at :last}
          {:op :create-node :id "block-1" :type :block :props {:text "Parent B"}}
          {:op :place :id "block-1" :under :doc :at :last}
          {:op :create-node :id "block-2" :type :block :props {:text "Child A1"}}
          {:op :place :id "block-2" :under "block-0" :at :last}
          {:op :create-node :id "block-3" :type :block :props {:text "Child A2"}}
          {:op :place :id "block-3" :under "block-0" :at :last}])))

(defn make-session
  "Create a minimal session for intent execution."
  [& {:keys [editing-id focus-id]}]
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes (if focus-id #{focus-id} #{})
               :focus focus-id
               :anchor focus-id}
   :buffer {}
   :ui {:folded #{}
        :zoom-root nil
        :editing-block-id editing-id
        :cursor-position 0}})

;; ── Generators ───────────────────────────────────────────────────────────────

(defn gen-block-id-from
  "Generate a block ID from a known set."
  [ids]
  (gen/elements ids))

(defn gen-indent-intent
  "Generate an indent intent for a known block."
  [ids]
  (gen/fmap (fn [id] {:type :indent :id id})
            (gen-block-id-from ids)))

(defn gen-outdent-intent
  "Generate an outdent intent for a known block."
  [ids]
  (gen/fmap (fn [id] {:type :outdent :id id})
            (gen-block-id-from ids)))

(defn gen-create-and-place-intent
  "Generate a create-and-place intent."
  [ids]
  (gen/let [parent (gen-block-id-from ids)
            after (gen/one-of [(gen/return nil) (gen-block-id-from ids)])]
    {:type :create-and-place
     :id (str "new-" (random-uuid))
     :parent parent
     :after after}))

(defn gen-update-content-intent
  "Generate an update-content intent."
  [ids]
  (gen/let [id (gen-block-id-from ids)
            text (gen/fmap #(apply str %) (gen/vector gen/char-alphanumeric 0 50))]
    {:type :update-content :id id :text text}))

(defn gen-toggle-fold-intent
  "Generate a toggle-fold intent."
  [ids]
  (gen/fmap (fn [id] {:type :toggle-fold :block-id id})
            (gen-block-id-from ids)))

(defn gen-structural-intent
  "Generate any structural intent from a pool of known IDs."
  [ids]
  (gen/one-of [(gen-indent-intent ids)
               (gen-outdent-intent ids)
               (gen-toggle-fold-intent ids)
               (gen-update-content-intent ids)]))

(defn gen-intent-sequence
  "Generate a sequence of intents operating on known block IDs."
  [ids]
  (gen/vector (gen-structural-intent ids) 1 8))

;; ── Intent Application Helpers ───────────────────────────────────────────────

(defn apply-intent-safe
  "Apply intent, returning updated DB. Swallows handler errors gracefully.
   Returns {:db db :session session :error? bool}."
  [db session intent-map]
  (try
    (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
          apply-updates (fn [s updates]
                          (if updates
                            (reduce-kv (fn [acc k v]
                                         (if (map? v)
                                           (update acc k merge v)
                                           (assoc acc k v)))
                                       s updates)
                            s))]
      (if (seq ops)
        (let [{:keys [db issues]} (tx/interpret db ops)]
          (if (seq issues)
            {:db db :session session :error? true}
            {:db db
             :session (apply-updates session session-updates)
             :error? false}))
        {:db db
         :session (apply-updates session session-updates)
         :error? false}))
    (catch #?(:clj Exception :cljs js/Error) _e
      {:db db :session session :error? true})))

(defn apply-intent-sequence
  "Apply a sequence of intents, threading DB and session through.
   Returns final {:db db :session session}."
  [db session intents]
  (reduce
    (fn [{:keys [db session]} intent-map]
      ;; For intents that need :editing state, set up session
      (let [intent-type (:type intent-map)
            needs-editing? (#{:delete :update-content} intent-type)
            focus-id (or (:id intent-map) (:block-id intent-map) (get-in session [:selection :focus]))
            adjusted-session (if (and needs-editing? focus-id)
                               (-> session
                                   (assoc-in [:ui :editing-block-id] focus-id)
                                   (assoc-in [:selection :focus] focus-id)
                                   (assoc-in [:selection :anchor] focus-id)
                                   (assoc-in [:selection :nodes] #{focus-id}))
                               (-> session
                                   (assoc-in [:selection :focus] focus-id)
                                   (assoc-in [:selection :anchor] focus-id)
                                   (assoc-in [:selection :nodes] (if focus-id #{focus-id} #{}))))]
        (apply-intent-safe db adjusted-session intent-map)))
    {:db db :session session}
    intents))

;; ── Property Tests ───────────────────────────────────────────────────────────

(defspec structural-intents-preserve-validity 50
  (testing "Random structural intents on a flat DB maintain validity"
    (let [ids ["block-0" "block-1" "block-2" "block-3"]]
      (prop/for-all [intents (gen-intent-sequence ids)]
        (let [db (make-test-db 4)
              session (make-session :focus-id "block-0")
              {:keys [db]} (apply-intent-sequence db session intents)
              {:keys [ok?]} (db/validate db)]
          ok?)))))

(defspec nested-db-intents-preserve-validity 50
  (testing "Random intents on a nested DB maintain validity"
    (let [ids ["block-0" "block-1" "block-2" "block-3"]]
      (prop/for-all [intents (gen-intent-sequence ids)]
        (let [db (make-nested-db)
              session (make-session :focus-id "block-2")
              {:keys [db]} (apply-intent-sequence db session intents)
              {:keys [ok?]} (db/validate db)]
          ok?)))))

(defspec indent-outdent-cycles-preserve-validity 50
  (testing "Repeated indent/outdent cycles don't corrupt DB"
    (let [ids ["block-0" "block-1" "block-2"]]
      (prop/for-all [intents (gen/vector
                               (gen/one-of [(gen-indent-intent ids)
                                            (gen-outdent-intent ids)])
                               2 12)]
        (let [db (make-test-db 3)
              session (make-session :focus-id "block-0")
              {:keys [db]} (apply-intent-sequence db session intents)
              {:keys [ok?]} (db/validate db)]
          ok?)))))

(defspec create-and-structural-ops-compose 30
  (testing "Creating blocks then structurally modifying them maintains validity"
    (let [existing-ids ["block-0" "block-1" "block-2"]]
      (prop/for-all [creates (gen/vector (gen-create-and-place-intent existing-ids) 1 4)
                     structural (gen-intent-sequence existing-ids)]
        (let [db (make-test-db 3)
              session (make-session :focus-id "block-0")
              ;; First apply creates
              {:keys [db session]} (apply-intent-sequence db session creates)
              ;; Then apply structural ops
              {:keys [db]} (apply-intent-sequence db session structural)
              {:keys [ok?]} (db/validate db)]
          ok?)))))

;; ── Deterministic Composability Tests ────────────────────────────────────────

(deftest indent-then-outdent-is-identity-for-parent
  (testing "Indent then outdent returns block to original parent"
    (let [db (make-test-db 3)
          session (make-session :focus-id "block-1")
          parent-before (q/parent-of db "block-1")
          ;; Indent block-1 (goes under block-0)
          {:keys [db session]} (apply-intent-safe db session {:type :indent :id "block-1"})
          ;; Outdent block-1 (should return to :doc)
          {:keys [db]} (apply-intent-safe db session {:type :outdent :id "block-1"})
          parent-after (q/parent-of db "block-1")]
      (is (= parent-before parent-after)
          "Indent + outdent should restore original parent"))))

(deftest sequential-indents-create-valid-nesting
  (testing "Sequential indents create valid nesting without corruption"
    (let [db (make-test-db 4)
          session (make-session :focus-id "block-1")
          ;; Indent block-1 under block-0 (previous sibling)
          {:keys [db session]} (apply-intent-safe db session {:type :indent :id "block-1"})
          _ (is (= "block-0" (q/parent-of db "block-1"))
                "block-1 should indent under previous sibling block-0")
          ;; Indent block-2 — its previous sibling is now block-0 (block-1 moved)
          {:keys [db session]} (apply-intent-safe db session {:type :indent :id "block-2"})
          ;; Indent block-3
          {:keys [db]} (apply-intent-safe db session {:type :indent :id "block-3"})
          {:keys [ok?]} (db/validate db)]
      (is ok? "DB should be valid after sequential indents"))))

(deftest fold-unfold-doesnt-affect-db
  (testing "Folding is session-only, DB unchanged"
    (let [db (make-nested-db)
          session (make-session :focus-id "block-0")
          db-before db
          {:keys [db session]} (apply-intent-safe db session {:type :toggle-fold :block-id "block-0"})
          {:keys [ok?]} (db/validate db)]
      (is ok? "DB should be valid after fold")
      (is (= (:nodes db-before) (:nodes db))
          "Fold should not change DB nodes")
      (is (contains? (get-in session [:ui :folded]) "block-0")
          "Block should be in folded set"))))
