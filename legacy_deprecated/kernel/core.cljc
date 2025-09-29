(ns kernel.core
  "Tree-edit kernel with multimethod operation registry.

   ARCHITECTURE:
   - Canonical DB: {:nodes {id {:type kw :props map}} :child-ids/by-parent {parent-id [child-id ...]}}
   - 6 core primitives: create-node*, place*, update-node*, prune*, add-ref*, rm-ref*
   - Extensible multimethod registry: apply-op dispatches on :op keyword
   - Transaction processing: apply-tx+effects* (main processor)
   - Effects detection: pure data effects for adapters
   - REPL-driven development: structured error reports, introspection tools

   CORE CONSTRAINT: Primitive operations must not leverage :derived data"
  (:require [clojure.set :as set]
            [kernel.invariants :as inv]
            [kernel.schemas :as S]
            [malli.core :as m]
            [kernel.effects :as effects]
            [kernel.responses :as R]
            [kernel.deck :as deck]
            [kernel.tx.normalize :as normalize]
            [kernel.derive.registry :as registry]
            [kernel.lens :as lens]
            [medley.core :as medley]))

(def ^:const ROOT "root")

;; ------------------------------------------------------------
;; Tiny utils
;; ------------------------------------------------------------

(defn- roots-of [db]
  (let [r (:roots db)]
    (if (and (vector? r) (seq r)) r [ROOT])))

(defn- ->tx
  "Normalize tx input to a sequence of operations."
  [tx]
  (cond
    (nil? tx) []
    (map? tx) [tx]
    (sequential? tx) tx
    :else (throw (ex-info "Invalid tx format" {:tx tx}))))

;; ------------------------------------------------------------
;; Position/cycle utilities using lens functions
;; ------------------------------------------------------------

(defn pos->index
  "Convert position spec to insertion index.
   pos ∈ {nil,:first,:last, [:index i], [:before anchor-id], [:after anchor-id]}"
  {:malli/schema (m/schema [:schema {:registry S/registry} :kernel.schemas/pos->index-fn])}
  [db parent-id id pos]
  (let [child-ids (lens/children-of db parent-id)
        base (vec (remove #{id} child-ids))
        idx (fn [anchor-id]
              (let [i (.indexOf base anchor-id)]
                (when (neg? i)
                  (throw (ex-info "anchor not in parent" {:anchor anchor-id :parent parent-id})))
                i))]
    (cond
      (nil? pos) (count base) ; default :last
      (= :first pos) 0
      (= :last pos) (count base)
      (and (vector? pos) (= :index (first pos)))
      (let [i (second pos)]
        (when (or (neg? i) (> i (count base)))
          (throw (ex-info "index OOB" {:i i :n (count base)})))
        i)
      (and (vector? pos) (= :before (first pos))) (idx (second pos))
      (and (vector? pos) (= :after (first pos))) (inc (idx (second pos)))
      :else (throw (ex-info "bad :pos" {:pos pos})))))

(defn cycle? [db id new-parent-id]
  "Check if setting new-parent-id as parent of id would create a cycle."
  (when new-parent-id
    ;; Build parent map from adjacency
    (let [parent-id-of (reduce-kv (fn [m parent-id child-ids]
                                    (reduce #(assoc %1 %2 parent-id) m child-ids))
                                  {} (:children-by-parent-id db))]
      ;; Walk up from new-parent-id and see if we reach id
      (loop [current new-parent-id
             visited #{}]
        (cond
          (nil? current) false ; reached root without finding id
          (= current id) true ; found id - would be a cycle
          (visited current) false ; loop in existing structure, but not involving id
          :else (recur (parent-id-of current) (conj visited current)))))))

;; ------------------------------------------------------------
;; 6 core primitives (existence, topology, attributes, deletion, references)
;; ------------------------------------------------------------

(defn create-node*
  "Idempotent create of a node shell; never attaches to a parent."
  [db {:keys [id type props node-id node-type] :or {type :div props {}}}]
  (let [id (or id node-id)
        type (or type node-type :div)]
    (assert id "create-node*: :id or :node-id required")
    (if (lens/node-exists? db id)
      db
      (assoc-in db [:nodes id] {:type type :props props}))))

(defn place*
  "Topology + order in one op.
   {:id ... :parent-id p-or-nil :pos (see pos->index)} or
   {:node-id ... :parent-id p-or-nil :anchor (see pos->index)}
   - parent-id=nil    => detach only
   - parent-id=same   => reorder
   - parent-id≠same   => move-and-place
   Cycle-safe."
  {:malli/schema (m/schema [:schema {:registry S/registry} :kernel.schemas/place*-fn])}
  [db {:keys [id parent-id pos node-id anchor]}]
  (let [id (or id node-id)
        pos (or pos anchor)]
    (assert id "place*: :id or :node-id required")
    (assert (lens/node-exists? db id) (str "place*: node does not exist: " id))
    (when (and parent-id (not (lens/node-exists? db parent-id)))
      (throw (ex-info "place*: parent-id does not exist" {:parent-id parent-id})))
    (when (cycle? db id parent-id)
      (throw (ex-info "place*: cycle/invalid parent" {:id id :parent-id parent-id})))
    (let [old-parent-id (lens/parent-of db id)]
      (if (and (= parent-id old-parent-id) (nil? pos))
        db
        (let [db1 (if old-parent-id
                    (update-in db [:children-by-parent-id old-parent-id]
                               (fn [children] (vec (remove #{id} children))))
                    db)]
          (if (nil? parent-id)
            db1
            (let [child-ids (lens/children-of db1 parent-id)
                  base (vec (remove #{id} child-ids))
                  i (pos->index db1 parent-id id pos)
                  v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
              (assert (= (count v) (count (distinct v))) "place*: duplicate children detected")
              (assoc-in db1 [:children-by-parent-id parent-id] v))))))))

(defn update-node*
  "Deep-merge-ish updates: map values merge, scalars replace."
  [db {:keys [id props sys updates node-id]}]
  (let [id (or id node-id)]
    (assert id "update-node*: :id or :node-id required")
    (let [u (cond-> {}
              (some? props) (assoc :props props)
              (some? sys) (assoc :sys sys)
              (map? updates) (merge updates))
          deep (fn m [a b] (if (and (map? a) (map? b)) (merge-with m a b) b))]
      (update-in db [:nodes id] #(merge-with deep % u)))))

(defn- subtree-ids
  "Get all node IDs in subtree rooted at node-id (inclusive)."
  [db node-id]
  (let [db-with-derived (if (:derived db) db (registry/run db))
        children (lens/children-of db-with-derived node-id)]
    (reduce (fn [acc child-id]
              (set/union acc (subtree-ids db-with-derived child-id)))
            #{node-id}
            children)))

(defn prune*
  "Hard delete by predicate over ids. Closure guarantee: if a node matches,
   its entire subtree is removed."
  [db pred]
  (let [ids (set (keys (:nodes db)))
        victims (reduce (fn [acc id]
                          (if (pred db id)
                            (set/union acc (subtree-ids db id))
                            acc))
                        #{} ids)
        db1 (reduce (fn [d id]
                      (-> d
                          (update :nodes dissoc id)
                          (update :children-by-parent-id dissoc id)))
                    db victims)]
    ;; scrub victims from all child vectors
    (let [db2 (update db1 :children-by-parent-id
                      (fn [m]
                        (into {} (for [[parent-id child-ids] m]
                                   [parent-id (vec (remove victims child-ids))]))))
          db3 (update db2 :refs
                      (fn [E]
                        (into {}
                              (for [[rel m] (or E {})]
                                [rel (into {}
                                           (keep (fn [[s ds]]
                                                   (when-not (victims s)
                                                     (let [ds' (into #{} (remove victims ds))]
                                                       (when (seq ds') [s ds']))))
                                                 m))]))))]
      db3)))

(defn- ref-neighbors [db rel id]
  (seq (get-in db [:refs rel id] #{})))

(defn- reachable? [db rel start target]
  (loop [q (conj #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs #queue []) start)
         seen #{}]
    (when-let [x (peek q)]
      (cond
        (= x target) true
        (seen x) (recur (pop q) seen)
        :else (recur (into (pop q) (remove seen (ref-neighbors db rel x)))
                     (conj seen x))))))

(defn- edge-ok? [db rel src dst]
  (let [{:keys [acyclic? unique?]} (get-in db [:edge-registry rel] {})]
    (and
     (if unique? (empty? (get-in db [:refs rel src] #{})) true)
     (if acyclic? (not (reachable? db rel dst src)) true))))

(defn add-ref* [db {:keys [rel src dst relation source-id target-id] :as m}]
  (let [rel (or rel relation)
        src (or src source-id)
        dst (or dst target-id)]
    (assert (lens/node-exists? db src) (str "add-ref*: src missing: " src))
    (assert (lens/node-exists? db dst) (str "add-ref*: dst missing: " dst))
    (when (= src dst)
      (throw (ex-info "add-ref*: self-edge not allowed" {:op m :why :self-edge})))
    (when-not (edge-ok? db rel src dst)
      (throw (ex-info "add-ref*: registry constraint violation" {:op m :why :edge-constraint :rel rel :src src :dst dst})))
    (update-in db [:refs rel src] (fnil conj #{}) dst)))

(defn rm-ref* [db {:keys [rel src dst relation source-id target-id]}]
  (let [rel (or rel relation)
        src (or src source-id)
        dst (or dst target-id)]
    (update-in db [:refs rel src] (fnil disj #{}) dst)))

;; ------------------------------------------------------------
;; Operation registry (multimethod-based dispatch)
;; ------------------------------------------------------------

(defn apply-op
  "Data-driven operation dispatch using the registry."
  [db op]
  (let [opkw (:op op)
        definition (S/op-definition-for opkw)]
    (if-not definition
      ;; This case should be caught by validation, but kept as a safeguard.
      (throw (ex-info "Unknown :op (registry lookup failed during dispatch)" {:op opkw}))
      (let [handler (:handler definition)]
        (handler db op)))))

;; ------------------------------------------------------------
;; Initialize Core Registry
;; ------------------------------------------------------------

(S/register-op! :create-node
                {:schema ::S/create-node-op
                 :handler create-node*
                 :axes #{:existence}})
(S/register-op! :place
                {:schema ::S/place-op
                 :handler place*
                 :axes #{:topology :order}})
(S/register-op! :update-node
                {:schema ::S/update-node-op
                 :handler update-node*
                 :axes #{:attributes}})
(S/register-op! :prune
                {:schema ::S/prune-op
                 ;; Wrapper required as prune* expects pred directly
                 :handler (fn [db op] (prune* db (:pred op)))
                 :axes #{:existence}})
(S/register-op! :add-ref
                {:schema ::S/add-ref-op
                 :handler add-ref*
                 :axes #{:references}})
(S/register-op! :rm-ref
                {:schema ::S/rm-ref-op
                 :handler rm-ref*
                 :axes #{:references}})

;; ------------------------------------------------------------
;; Transaction Pipeline Stages
;; ------------------------------------------------------------

(defn stage:validate-schema
  [ctx]
  (try
    (S/validate-op! (:op ctx))
    ctx
    (catch clojure.lang.ExceptionInfo e
      (assoc ctx :error {:why :schema :data (ex-data e) :message (.getMessage e)}))
    (catch Throwable t
      (assoc ctx :error {:why :schema :message (.getMessage t)}))))

(defn stage:apply-op
  [ctx]
  (try
    (let [db-next (apply-op (:db-before ctx) (:op ctx))]
      (assoc ctx :db-after db-next))
    (catch clojure.lang.ExceptionInfo e
      (assoc ctx :error {:why :op :data (ex-data e) :message (.getMessage e)}))
    (catch Throwable t
      (assoc ctx :error {:why :op :message (.getMessage t)}))))

(defn stage:derive
  [ctx]
  (try
    (update ctx :db-after registry/run)
    (catch Throwable t
      (assoc ctx :error {:why :derivation :message (.getMessage t)}))))

(defn stage:assert-invariants
  [ctx]
  (if (not (get-in ctx [:config :assert?]))
    ctx
    (try
      (inv/check-invariants (:db-after ctx))
      ctx
      (catch clojure.lang.ExceptionInfo e
        (assoc ctx :error {:why :invariants :data (ex-data e) :message (.getMessage e)}))
      (catch Throwable t
        (assoc ctx :error {:why :invariants :message (.getMessage t)})))))

(defn stage:detect-effects
  [ctx]
  (let [es (effects/detect (:db-before ctx) (:db-after ctx) (:op-index ctx) (:op ctx))]
    (assoc ctx :effects es)))

(def default-pipeline
  [stage:validate-schema
   stage:apply-op
   stage:derive
   stage:assert-invariants
   stage:detect-effects])

(defn- run-pipeline
  "Run the pipeline stages over the context. Uses (reduced) for short-circuiting on error."
  [pipeline ctx]
  (reduce (fn [c stage-fn]
            ;; Check for :error before executing the stage.
            (if (:error c)
              (reduced c)
              (let [next-c (stage-fn c)]
                ;; Check for :error immediately after execution.
                (if (:error next-c) (reduced next-c) next-c))))
          ctx
          pipeline))

;; ------------------------------------------------------------
;; Main Transaction Processor
;; ------------------------------------------------------------

(defn apply-tx+effects*
  "Process a transaction using the declarative pipeline. Does not throw."
  ([db tx] (apply-tx+effects* db tx {}))
  ([db tx {:keys [assert? pipeline trace?]
           :or {assert? false pipeline default-pipeline trace? false}}]

   ;; Handle initial validation exceptions immediately, returning standardized error map.
   (try
     (S/validate-db! db)
     (S/validate-tx! tx)
     (catch Throwable t
       (let [exd (ex-data t)]
         ;; Return error directly, not wrapped in reduced
         {:error {:why (or (:why exd) :validation) :message (.getMessage t) :data exd}
          :db db :effects []})))

   (let [raw-ops (->tx tx)
         ops (normalize/normalize raw-ops) ; Apply peephole optimizations
         config {:assert? assert?}]

     (loop [i 0
            current-db (registry/run db)
            all-effects []
            trace []]
       (if (= i (count ops))
         ;; Transaction Success - run deck checks
         (let [findings (deck/run current-db {:when #{:postorder-index}})]
           (cond-> {:db current-db :effects all-effects :findings findings}
             (seq trace) (assoc :trace trace)))

         (let [op (nth ops i)
               initial-ctx {:db-before current-db :op op :op-index i :config config}
               ;; Run the pipeline
               final-ctx (run-pipeline pipeline initial-ctx)]

           (if-let [error (:error final-ctx)]
             ;; Operation Failure: stop and return standardized error
             {:db db ;; Return original db
              :effects []
              :error (merge error {:op-index i :op op})}

             ;; Operation Success: continue
             (let [trace-step (when trace?
                                {:i i :op op :db (:db-after final-ctx) :effects (:effects final-ctx)})]
               (recur (inc i)
                      (:db-after final-ctx)
                      (into all-effects (:effects final-ctx))
                      (if trace-step (conj trace trace-step) trace))))))))))

;; ------------------------------------------------------------
;; Public API Wrappers
;; ------------------------------------------------------------

(defn run-tx
  "Total transaction processor: never throws."
  ([db tx] (run-tx db tx {}))
  ([db tx opts]
   (try
     (let [result (apply-tx+effects* db tx opts)]
       (if-let [error (:error result)]
         ;; Failure case
         {:ok? false
          :db (or (:db result) db)
          :effects []
          :error error}
         ;; Success case
         {:ok? true
          :db (:db result)
          :effects (:effects result)
          :findings (:findings result)}))
     (catch Throwable t
       {:ok? false :db db :effects [] :error {:why :unexpected :message (.getMessage t)}}))))

(defn evaluate
  "Uniform envelope using response builders. Never throws."
  ([db tx] (evaluate db tx {}))
  ([db tx opts]
   (try
     (let [result (apply-tx+effects* db tx opts)]
       (if-let [error (:error result)]
         (R/error error)
         (R/ok (select-keys result [:db :effects :trace]))))
     (catch Throwable t
       (R/error {:why :unexpected :data {:message (.getMessage t)}})))))

;; ------------------------------------------------------------
;; Verification assertions (updated to use new APIs)
;; ------------------------------------------------------------

(defn verify-system
  "Run all verification assertions to ensure system integrity."
  []

  ;; Multi-root traversal
  (let [db {:nodes {"root" {:type :root} "palette" {:type :root} "w" {:type :div} "orphan" {:type :span}}
            :children-by-parent-id {"root" ["w"]}
            :roots ["root" "palette"]}
        d (registry/run db)]
    (assert (= (get-in d [:derived :preorder]) ["root" "w" "palette"]) "Multi-root preorder incorrect")
    ;; Registry provides core derivation data
    (assert (contains? (:derived d) :parent-id-of) "Should have parent-id-of")
    (assert (contains? (:derived d) :index-of) "Should have index-of")
    (assert (contains? (:derived d) :preorder) "Should have preorder"))

  ;; Core operations work through the registry system
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}
        {:keys [db effects]} (apply-tx+effects* base {:op :create-node :id "x" :type :div})]
    (assert (contains? (:nodes db) "x") "create-node should create node")
    (assert (empty? effects) "create-node should emit no effects")
    (assert (every? map? effects) "All effects should be data maps"))

  ;; Data-driven registry dispatch
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}]
    ;; Core primitives work directly
    (assert (contains? (:nodes (apply-op base {:op :create-node :id "test-core"})) "test-core") "Core ops must work")
    ;; run-tx uses the registry
    (assert (:ok? (run-tx base [{:op :create-node :id "test-run-tx"}])) "run-tx must use registry")
    ;; apply-tx+effects* uses the registry
    (assert (contains? (:nodes (:db (apply-tx+effects* base [{:op :create-node :id "test-effects"}]))) "test-effects") "apply-tx+effects* must use registry")
    ;; Unknown ops fail gracefully with clear error
    (try (apply-op base {:op :unknown-op}) (assert false "Should throw for unknown op")
         (catch Exception e (assert (re-find #"Unknown :op" (.getMessage e)) "Should give clear error message"))))

  ;; Declarative pipeline error handling
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}
        error-result (run-tx base [{:op :create-node :id "a"}
                                   {:op :place :id "a" :parent-id "NONEXISTENT"}])]
    (assert (not (:ok? error-result)) "Invalid transaction should fail")
    (assert (= (get-in error-result [:error :op-index]) 1) "Should report correct failing op index")
    (assert (contains? #{:schema :op :invariants} (get-in error-result [:error :why])) "Should categorize error type")
    (assert (= (:db error-result) base) "DB should be unchanged on error")
    (assert (empty? (:effects error-result)) "No effects on error"))

  ;; Cycle detection prevents infinite nesting
  (let [base {:nodes {"a" {:type :div} "b" {:type :div}} :children-by-parent-id {}}
        tx [{:op :place :id "a" :parent-id "b"}
            {:op :place :id "b" :parent-id "a"}]
        result (run-tx base tx)]
    (assert (not (:ok? result)) "Cycle creation should fail")
    (assert (= (get-in result [:error :why]) :op) "Should fail at operation level")
    (assert (re-find #"cycle" (get-in result [:error :message])) "Error should mention cycle"))

  ;; Schema validation catches malformed operations
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}
        bad-ops [{:op :create-node} ; missing :id
                 {:op :place :id "nonexistent"} ; node doesn't exist
                 {:op :unknown-thing :data "whatever"}] ; unknown op
        results (map #(run-tx base [%]) bad-ops)]
    (assert (every? #(not (:ok? %)) results) "All malformed ops should fail")
    (assert (every? #(contains? #{:schema :op} (get-in % [:error :why])) results) "Should categorize errors appropriately")
    (assert (every? #(= (:db %) base) results) "DB should remain unchanged on all failures"))

  ;; Pipeline stage isolation
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}
        op {:op :create-node :id "test" :type :div}]
    ;; Each stage should work independently
    (let [ctx1 (stage:validate-schema {:db-before base :op op :op-index 0 :config {}})
          ctx2 (stage:apply-op ctx1)
          ctx3 (stage:derive ctx2)
          ctx4 (stage:assert-invariants ctx3)
          ctx5 (stage:detect-effects ctx4)]
      (assert (not (:error ctx1)) "Schema validation should pass")
      (assert (not (:error ctx2)) "Op application should pass")
      (assert (not (:error ctx3)) "Derivation should pass")
      (assert (not (:error ctx4)) "Invariants should pass")
      (assert (not (:error ctx5)) "Effects detection should pass")
      (assert (contains? (:nodes (:db-after ctx2)) "test") "Op should create node")
      (assert (:derived (:db-after ctx3)) "Derivation should add derived data")
      (assert (coll? (:effects ctx5)) "Should have effects collection")))

  ;; Transaction atomicity
  (let [base {:nodes {"root" {:type :root} "a" {:type :div}} :children-by-parent-id {}}
        failing-tx [{:op :create-node :id "b" :type :div}
                    {:op :place :id "b" :parent-id "NONEXISTENT"}]
        result (run-tx base failing-tx)]
    (assert (not (:ok? result)) "Transaction with invalid op should fail")
    (assert (= (:db result) base) "Failed transaction should not modify DB at all")
    (assert (not (contains? (:nodes (:db result)) "b")) "Failed tx should not create intermediate nodes"))

  ;; Response builders work correctly
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}
        success-result (evaluate base [{:op :create-node :id "test"}])
        error-result (evaluate base [{:op :unknown-op}])]
    (assert (= (:status success-result) :ok) "Success should have :ok status")
    (assert (contains? success-result :db) "Success should include :db")
    (assert (= (:status error-result) :error) "Error should have :error status")
    (assert (contains? (:error error-result) :why) "Error should include error details"))

  ;; Transaction optimization works correctly
  (let [base {:nodes {"root" {:type :root}} :children-by-parent-id {}}
        ops-before [{:op :create-node :id "temp" :type :div}
                    {:op :update-node :id "keep" :props {:a 1}}
                    {:op :update-node :id "keep" :props {:b 2}}
                    {:op :delete :id "temp"}]
        result (apply-tx+effects* base ops-before)]
    (assert (:ok? (run-tx (:db result) [])) "Optimized result should be valid")
    (assert (not (contains? (:nodes (:db result)) "temp")) "Temp node should be optimized away")
    (assert (= (get-in result [:db :nodes "keep" :props]) {:a 1 :b 2}) "Properties should be merged"))

  ;; Lens functions provide correct navigation
  (let [tree-db {:nodes {"root" {:type :root} "parent" {:type :div} "child1" {:type :span} "child2" {:type :span}}
                 :children-by-parent-id {"root" ["parent"] "parent" ["child1" "child2"]}}
        derived-db (registry/run tree-db)]
    (assert (= (lens/children-of tree-db "parent") ["child1" "child2"]) "Children lookup should work")
    (assert (= (lens/parent-of derived-db "child1") "parent") "Parent lookup should work")
    (assert (= (lens/path-to-root derived-db "child2") ["child2" "parent" "root"]) "Path to root should work")
    (assert (lens/node-exists? tree-db "child1") "Node existence check should work")
    (assert (not (lens/node-exists? tree-db "nonexistent")) "Non-existent node check should work"))

  ;; Registry system provides all expected derivations
  (let [complex-db {:nodes {"root" {:type :root} "a" {:type :div} "b" {:type :span} "c" {:type :p}}
                    :children-by-parent-id {"root" ["a"] "a" ["b" "c"]}}
        derived (registry/run complex-db)]
    (assert (every? #(contains? (:derived derived) %)
                    [:parent-id-of :index-of :child-ids-of :preorder :preorder-index :postorder-index :id-by-pre])
            "Registry should provide all core derivations")
    (assert (= (get-in derived [:derived :preorder]) ["root" "a" "b" "c"]) "Preorder should be correct")
    (assert (= (get-in derived [:derived :parent-id-of "b"]) "a") "Parent mapping should be correct")
    (assert (= (get-in derived [:derived :index-of "c"]) 1) "Index mapping should be correct"))

  ;; Deck system provides structured findings instead of throwing
  (let [invalid-db {:nodes {"root" {:type :root}} :children-by-parent-id {"root" ["nonexistent"]}}
        derived-invalid (registry/run invalid-db)
        findings (deck/run derived-invalid)]
    (assert (vector? findings) "Deck should return findings vector")
    (assert (some #(= (:level %) :error) findings) "Should find errors in invalid structure")
    (assert (some #(and (:msg %) (re-find #"missing|unknown" (:msg %))) findings) "Should report missing nodes"))

  "✓ All system verification assertions passed - clean architecture working correctly")