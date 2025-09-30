(ns core.struct
  "Structural-edit intent compiler → core ops.

   Lowers high-level structural editing intents (delete, indent, outdent, etc.)
   into the closed instruction set of three core operations:
   - :create-node
   - :place
   - :update-node

   Design principle: Delete is archive by design - nodes are moved to :trash,
   never destroyed. This maintains referential integrity and enables undo.")

;; ── Derived index accessors ──────────────────────────────────────────────────

(defn- parent-of
  "Returns the parent ID of the given node ID."
  [DB id]
  (get-in DB [:derived :parent-of id]))

(defn- prev-sibling
  "Returns the previous sibling ID of the given node ID."
  [DB id]
  (get-in DB [:derived :prev-id-of id]))

(defn- grandparent-of
  "Returns the grandparent ID of the given node ID."
  [DB id]
  (when-let [p (parent-of DB id)]
    (parent-of DB p)))

;; ── Intent compilers ──────────────────────────────────────────────────────────

(defn delete-ops
  "Compiles a delete intent into a :place operation that moves the node to :trash.
   Delete is archive - we never destroy nodes."
  [_DB id]
  [{:op :place :id id :under :trash :at :last}])

(defn indent-ops
  "Compiles an indent intent into a :place operation that moves the node
   under its previous sibling. Returns empty vector if no previous sibling exists
   (no-op safety)."
  [DB id]
  (if-let [sib (prev-sibling DB id)]
    [{:op :place :id id :under sib :at :last}]
    []))

(defn outdent-ops
  "Compiles an outdent intent into a :place operation that moves the node
   to be a sibling of its parent (under its grandparent, after its parent).
   Returns empty vector if no grandparent exists (no-op safety)."
  [DB id]
  (let [p  (parent-of DB id)
        gp (grandparent-of DB id)]
    (if (and p gp)
      [{:op :place :id id :under gp :at {:after p}}]
      [])))

;; ── Multimethod dispatch ──────────────────────────────────────────────────────

(defmulti compile-intent
  "Compiles a single high-level intent into a vector of core operations.
   Dispatch on the :type key of the intent map."
  (fn [_DB intent] (:type intent)))

(defmethod compile-intent :delete
  [DB {:keys [id]}]
  (delete-ops DB id))

(defmethod compile-intent :indent
  [DB {:keys [id]}]
  (indent-ops DB id))

(defmethod compile-intent :outdent
  [DB {:keys [id]}]
  (outdent-ops DB id))

(defmethod compile-intent :default
  [_DB _]
  [])

;; ── Public API ────────────────────────────────────────────────────────────────

(defn compile-intents
  "Compiles a sequence of high-level intents into a vector of core operations.
   Each intent is compiled independently and the results are concatenated."
  [DB intents]
  (->> intents
       (mapcat #(compile-intent DB %))
       vec))