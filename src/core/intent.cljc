(ns core.intent
  "Intent router with dual multimethods for structural and view intents.

   Design (ADR-016): Intent Router Pattern
   - intent->ops: Compiles structural intents to core operations
   - intent->db: Applies view intents directly to database
   - apply-intent: Unified entry point with explicit dispatch

   This unifies the event handling pipeline while keeping the 3-op kernel pure.
   Plugins implement one or both multimethods as needed.

   Example usage:
     ;; Structural intent (compiles to ops)
     (apply-intent db {:type :indent :id \"a\"})

     ;; View intent (direct DB update)
     (apply-intent db {:type :select :ids [\"a\" \"b\"]})")

;; ── Intent → Operations (Structural) ──────────────────────────────────────────

(defmulti intent->ops
  "Compile a structural intent into a vector of core operations.

   Returns: vector of operation maps for interpretation
   Dispatch: :type key of intent map

   Structural intents affect the document tree and must go through
   the interpret pipeline for validation and derived state updates.

   Example:
     (defmethod intent->ops :indent [db {:keys [id]}]
       [{:op :place :id id :under (prev-sibling db id) :at :last}])"
  (fn [_db intent] (:type intent)))

(defmethod intent->ops :default
  [_db _intent]
  nil)  ;; Return nil to signal no ops handler

;; ── Intent → Database (View) ──────────────────────────────────────────────────

(defmulti intent->db
  "Apply a view intent directly to the database.

   Returns: updated database
   Dispatch: :type key of intent map

   View intents modify UI state (selection, viewport, collapsed state)
   without affecting the document tree. They bypass interpret for efficiency.

   Example:
     (defmethod intent->db :select [db {:keys [ids]}]
       (assoc db :selection {:nodes (set ids) :focus (last ids)}))"
  (fn [_db intent] (:type intent)))

(defmethod intent->db :default
  [_db _intent]
  nil)  ;; Return nil to signal no db handler

;; ── Unified Entry Point ───────────────────────────────────────────────────────

(defn apply-intent
  "Unified intent application with explicit dispatch.

   Tries intent->ops first (structural), falls back to intent->db (view).
   Returns: {:db updated-db :ops [operations] :path :ops|:db|:unknown}

   The :path key indicates which route was taken for debugging/logging.

   Example:
     ;; Structural: returns ops for caller to interpret
     (apply-intent db {:type :indent :id \"a\"})
     ;=> {:db db :ops [{:op :place ...}] :path :ops}

     ;; View: returns updated db directly
     (apply-intent db {:type :select :ids [\"a\"]})
     ;=> {:db updated-db :ops [] :path :db}

     ;; Unknown: returns unchanged
     (apply-intent db {:type :unknown})
     ;=> {:db db :ops [] :path :unknown}"
  [db intent]
  (let [ops-result (intent->ops db intent)
        db-result (intent->db db intent)]
    (cond
      ;; Structural path: returns ops vector (or empty vector)
      (some? ops-result)
      {:db db :ops (vec ops-result) :path :ops}

      ;; View path: returns updated db
      (some? db-result)
      {:db db-result :ops [] :path :db}

      ;; Unknown intent type: both returned nil
      :else
      {:db db :ops [] :path :unknown})))

;; ── Convenience helpers ───────────────────────────────────────────────────────

(defn has-ops-handler?
  "Returns true if intent type has an intent->ops implementation."
  [intent-type]
  (some? (get-method intent->ops intent-type)))

(defn has-db-handler?
  "Returns true if intent type has an intent->db implementation."
  [intent-type]
  (some? (get-method intent->db intent-type)))

(defn has-handler?
  "Returns true if intent type has any handler (ops or db)."
  [intent-type]
  (or (has-ops-handler? intent-type)
      (has-db-handler? intent-type)))
