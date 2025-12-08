(ns kernel.dbg
  "Debug utilities for transaction tracing and DB inspection.

   Provides human-readable trace output for development and agent debugging."
  (:require [kernel.constants :as const]
            [clojure.string]))

(defn- format-op
  "Format a single operation as a concise string."
  [op]
  (case (:op op)
    :create-node
    (str "create " (:id op) " :" (:type op))

    :place
    (str "place " (:id op) " under " (:under op) " at " (:at op))

    :update-node
    (str "update " (:id op) " → " (keys (:props op)))

    (str "unknown-op: " (pr-str op))))

(defn- format-trace-entry
  "Format a single trace entry as a readable multi-line string."
  [entry]
  (let [{:keys [tx-id ops applied-ops num-applied notes]} entry
        failed-count (- (count ops) num-applied)
        status (if (zero? failed-count) "✓" "✗")]
    (str
     "────────────────────────────────────────\n"
     status " TX " tx-id
     (when (seq notes) (str " — " notes))
     "\n"
     "  Ops: " (count ops) " total, " num-applied " applied"
     (when (pos? failed-count)
       (str ", " failed-count " failed"))
     "\n"
     (when (seq applied-ops)
       (str "  Applied:\n"
            (clojure.string/join "\n"
                                 (map #(str "    " (format-op %))
                                      applied-ops))
            "\n"))
     (when (pos? failed-count)
       (let [failed-ops (drop num-applied ops)]
         (str "  Failed:\n"
              (clojure.string/join "\n"
                                   (map #(str "    " (format-op %))
                                        failed-ops))
              "\n"))))))

(defn pp-trace
  "Pretty-print transaction trace.

   Args:
   - trace: Vector of trace entries from tx/interpret

   Returns: Formatted string suitable for console output

   Example:
     (let [{:keys [trace]} (tx/interpret db ops)]
       (println (dbg/pp-trace trace)))

   Output:
     ────────────────────────────────────────
     ✓ TX 1234567890 — Create and place nodes
       Ops: 3 total, 3 applied
       Applied:
         create a :block
         place a under page at :last
         update a → (:text)
     ────────────────────────────────────────
     ✗ TX 1234567891 — Failed transaction
       Ops: 2 total, 1 applied, 1 failed
       Applied:
         create b :block
       Failed:
         place b under missing at :last"
  [trace]
  (if (empty? trace)
    "(no trace entries)"
    (clojure.string/join "\n"
                         (map format-trace-entry trace))))

(defn pp-db-summary
  "Print a summary of the database state.

   Shows:
   - Node count
   - Root structure
   - Derived index sizes

   Note: Session state (selection, editing) is in shell.view-state atom, not in DB.

   Example:
     (println (dbg/pp-db-summary db))"
  [db]
  (let [{:keys [nodes children-by-parent derived]} db]
    (str
     "──── DB SUMMARY ────\n"
     "Nodes: " (count nodes) " total\n"
     "  :doc children: " (count (get children-by-parent const/root-doc [])) "\n"
     "  :trash children: " (count (get children-by-parent const/root-trash [])) "\n"
     "\n"
     "Derived indexes:\n"
     "  parent-of: " (count (:parent-of derived)) " entries\n"
     "  index-of: " (count (:index-of derived)) " entries\n"
     "  prev-id-of: " (count (:prev-id-of derived)) " entries\n"
     "  next-id-of: " (count (:next-id-of derived)) " entries\n")))

(defn inspect-node
  "Pretty-print a single node with all its properties and relationships.

   Example:
     (println (dbg/inspect-node db \"a\"))"
  [db id]
  (if-let [node (get-in db [:nodes id])]
    (str
     "──── NODE " id " ────\n"
     "Type: " (:type node) "\n"
     "Props: " (pr-str (:props node)) "\n"
     "\n"
     "Relationships:\n"
     "  Parent: " (get-in db [:derived :parent-of id]) "\n"
     "  Index: " (get-in db [:derived :index-of id]) "\n"
     "  Prev sibling: " (get-in db [:derived :prev-id-of id]) "\n"
     "  Next sibling: " (get-in db [:derived :next-id-of id]) "\n"
     "  Children: " (get-in db [:children-by-parent id] []) "\n")
    (str "Node " id " not found")))
