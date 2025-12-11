(ns plugins.structural
  "Structural-edit and movement intent compiler → core ops.

   Lowers high-level structural editing intents (delete, indent, outdent, move, etc.)
   into the closed instruction set of three core operations:
   - :create-node
   - :place
   - :update-node

   Design principle: Delete is archive by design - nodes are moved to :trash,
   never destroyed. This maintains referential integrity and enables undo.

   ## Op-fn Convention (arity safety)

   All per-node operation functions (delete-ops, indent-ops, outdent-ops) use
   uniform signature: `(fn [db session id])`. This prevents arity mismatch bugs
   when passed to `apply-to-active-targets`. Even if a function doesn't need
   session, it accepts and ignores it:

     (defn my-ops [db _session id] ...)  ; Always 3 args

   This convention eliminates the class of silent failures where a 2-arg function
   is called with 3 args (or vice versa) through higher-order function dispatch.

   Includes movement/reordering logic (merged from plugins.permute)."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q]
            [kernel.position :as pos]
            [kernel.db :as db]))

;; Sentinel for DCE prevention - referenced by spec.runner

;; ── Intent compilers ──────────────────────────────────────────────────────────

(defn- within-zoom-scope?
  "Check if target-id is within the current zoom scope.

   When zoomed into block Z, any node N is within scope if:
   - N is Z itself, OR
   - N is a descendant of Z

   When not zoomed (zoom-root is nil), all nodes are in scope.

   FR-Scope-02: Prevents operations from moving blocks outside zoom root."
  [db session target-id]
  (if-let [zoom-root (q/zoom-root session)]
    ;; Zoomed: target must be zoom-root or a descendant of it
    (or (= target-id zoom-root)
        (db/descendant-of? db zoom-root target-id))
    ;; Not zoomed: all nodes in scope
    true))

(defn delete-ops
  "Compiles a delete intent into operations that:
   1. Reparent children under deleted block's parent (LOGSEQ PARITY)
   2. Move deleted block to :trash

   Children are placed at the same position the deleted block occupied,
   preserving document order.

   Signature: [db session id] - uniform with all op-fns for apply-to-active-targets."
  [db _session id]
  (let [children (q/children db id)
        parent (q/parent-of db id)]
    (if (and (seq children) parent)
      ;; Has children: reparent them under our parent, then move self to trash
      ;; Place first child where deleted block was, remaining children after each other
      (let [reparent-ops (vec
                          (concat
                           ;; First child takes our position
                           [{:op :place :id (first children) :under parent :at {:before id}}]
                           ;; Remaining children placed after each other
                           (map-indexed
                            (fn [idx child-id]
                              {:op :place :id child-id :under parent :at {:after (nth children idx)}})
                            (rest children))))]
        (conj reparent-ops {:op :place :id id :under const/root-trash :at :last}))
      ;; No children or no parent: just move to trash
      [{:op :place :id id :under const/root-trash :at :last}])))

(defn indent-ops
  "Compiles an indent intent into a :place operation that moves the node
   under its previous sibling.

   LOGSEQ PARITY: If the target sibling is collapsed/folded, returns
   {:unfold-target sib} so the handler can expand it, ensuring the user
   sees where their block went.

   Signature: [db session id] - uniform with all op-fns for apply-to-active-targets."
  [db session id]
  (if-let [sib (q/prev-sibling db id)]
    (let [is-collapsed (q/folded? session sib)]
      {:ops [{:op :place :id id :under sib :at :last}]
       :unfold-target (when is-collapsed sib)})
    {:ops []}))

(defn- indent-multi-ops
  "Indent multiple selected blocks (Logseq parity).

   All selected blocks move under the previous sibling of the FIRST selected block,
   maintaining their relative order as siblings at the same level.

   LOGSEQ PARITY: If the target sibling is collapsed/folded, returns
   {:unfold-target new-parent} so the handler can expand it.

   Example:
     Before:              After:
     :doc                 :doc
       a                    a
       b ← selected           b (child of a)
       c ← selected           c (sibling of b, also child of a)

   Returns {:ops [] :unfold-target nil} if first selected block has no previous sibling."
  [db session targets]
  (if (seq targets)
    (let [first-id (first targets)
          new-parent (q/prev-sibling db first-id)]
      (if new-parent
        (let [is-collapsed (q/folded? session new-parent)]
          {:ops (mapv (fn [id] {:op :place :id id :under new-parent :at :last}) targets)
           :unfold-target (when is-collapsed new-parent)})
        {:ops [] :unfold-target nil}))
    {:ops [] :unfold-target nil}))

(defn- outdent-multi-ops
  "Outdent multiple selected blocks (Logseq parity).

   All selected blocks move to position after their current parent,
   maintaining their relative order.

   Example:
     Before:              After:
     :doc                 :doc
       parent               parent
         a1 ← selected      a1 (sibling of parent, after it)
         a2 ← selected      a2 (after a1)

   Uses chained :at {:after prev-id} to maintain relative order."
  [db session targets]
  (when (seq targets)
    (let [first-id (first targets)
          p (q/parent-of db first-id)
          gp (when p (q/parent-of db p))
          roots (set (:roots db const/roots))
          parent-is-page? (= :page (get-in db [:nodes p :type]))]
      ;; Check if outdent is valid for the first block
      (when (and p gp
                 (not (contains? roots p))
                 (not parent-is-page?)
                 (within-zoom-scope? db session gp))
        ;; First block goes after parent, rest chain after each other
        (loop [remaining targets
               prev-anchor p
               ops []]
          (if (empty? remaining)
            ops
            (let [id (first remaining)]
              (recur (rest remaining)
                     id
                     (conj ops {:op :place :id id :under gp :at {:after prev-anchor}})))))))))

#_{:clj-kondo/ignore [:unused-private-var]} ; Scaffolded for batch indent
(defn- collect-right-siblings
  "Collect all right siblings of a node (all siblings after it).
   Returns vector of sibling IDs in document order.

   Example:
     Parent: [A B C D]
     (collect-right-siblings db B) => [C D]"
  [db id]
  (loop [current-id (q/next-sibling db id)
         result []]
    (if current-id
      (recur (q/next-sibling db current-id)
             (conj result current-id))
      result)))

(defn outdent-ops
  "Logical Outdenting (Logseq default with :editor/logical-outdenting? true):

   Moves node to position RIGHT AFTER its parent (as a sibling of parent),
   leaving right siblings untouched under the original parent.

   This matches Logseq's logical outdenting behavior where the outdented block
   becomes a sibling of its parent, positioned immediately after it.

   Example (Logical Outdenting):
     Before:
       - Grandparent
         - Parent
           - A
           - B ← outdent this
           - C
           - D

     After (Logical Outdenting):
       - Grandparent
         - Parent
           - A
           - C  ← B's right siblings stay under Parent
           - D
         - B    ← outdented to position RIGHT AFTER Parent (sibling of Parent)

   Note: Direct outdenting (where B kidnaps C and D as children) can be added
   via config flag when user preference plumbing exists.

   FR-Scope-02: Prevents outdenting if parent is a root container (already
   at top level) OR if grandparent is outside the current zoom scope."
  [db session id]
  (let [p (q/parent-of db id)
        gp (when p (q/parent-of db p))
        roots (set (:roots db const/roots))
        parent-is-page? (= :page (get-in db [:nodes p :type]))]
    ;; Can outdent if: has parent, has grandparent, parent is NOT a root,
    ;; parent is NOT a page (blocks directly under pages can't outdent),
    ;; AND grandparent is within zoom scope
    ;; Note: parent-is-root or parent-is-page means block is already at top level
    (if (and p gp
             (not (contains? roots p))
             (not parent-is-page?)
             (within-zoom-scope? db session gp))
      ;; Logical outdenting: move to position RIGHT AFTER parent (as sibling of parent)
      ;; Right siblings stay under parent (not kidnapped)
      [{:op :place :id id :under gp :at {:after p}}]
      [])))

;; ── Intent → Operations (ADR-016) ────────────────────────────────────────────

(intent/register-intent! :delete
                         {:doc "Delete node by moving to :trash.

         LOGSEQ PARITY: After deletion, enters edit mode on previous block
         with cursor at end. Falls back to next block if no previous."
                          :fr/ids #{:fr.struct/delete-block}
                          :spec [:map [:type [:= :delete]] [:id :string]]
                          :handler (fn [db _session {:keys [id]}]
                                     (let [;; Find focus target BEFORE deletion
                                           prev-block (q/prev-block-dom-order db id)
                                           next-block (when-not prev-block
                                                        (q/next-block-dom-order db id))
                                           new-focus (or prev-block next-block)
                                           ;; Get text length for cursor positioning at end
                                           prev-text-len (when prev-block
                                                           (count (get-in db [:nodes prev-block :props :text] "")))]
                                       {:ops (delete-ops db nil id)
                                        :session-updates
                                        (if new-focus
                                          {:selection {:nodes #{new-focus}
                                                       :focus new-focus
                                                       :anchor new-focus}
                                           :ui {:editing-block-id new-focus
                                                :cursor-position (if prev-block prev-text-len 0)}}
                                          ;; No blocks left - clear everything
                                          {:selection {:nodes #{} :focus nil :anchor nil}
                                           :ui {:editing-block-id nil}})}))})

(intent/register-intent! :indent
                         {:doc "Indent node under previous sibling.

                          LOGSEQ PARITY: If target sibling is collapsed, it's expanded."
                          :fr/ids #{:fr.struct/indent-outdent}
                          :spec [:map [:type [:= :indent]] [:id :string]]
                          :allowed-states #{:editing :selection}
                          :handler (fn [db session {:keys [id]}]
                                     (let [{:keys [ops unfold-target]} (indent-ops db session id)
                                           unfold-updates (when unfold-target
                                                            {:ui {:folded (disj (q/folded-set session) unfold-target)}})]
                                       {:ops (or ops [])
                                        :session-updates unfold-updates}))})

(intent/register-intent! :outdent
                         {:doc "Outdent node to be sibling of parent."
                          :fr/ids #{:fr.struct/indent-outdent}
                          :spec [:map [:type [:= :outdent]] [:id :string]]
                          :allowed-states #{:editing :selection}
                          :handler (fn [db session {:keys [id]}]
                                     (outdent-ops db session id))})

(intent/register-intent! :create-and-place
                         {:doc "Create new block and place it under parent."
                          :fr/ids #{:fr.struct/create-sibling}
                          :spec [:map [:type [:= :create-and-place]] [:id :string] [:parent :string] [:after {:optional true} :string]]
                          :handler (fn [_db _session {:keys [id parent after]}]
                                     [{:op :create-node :id id :type :block :props {:text ""}}
                                      {:op :place :id id :under parent :at (if after {:after after} :last)}])})

(intent/register-intent! :create-and-enter-edit
                         {:doc "Create new block after focus and immediately enter edit mode.
   This consolidates the two-step UI logic (create + setTimeout + enter-edit) into a single intent.
   Clears selection to maintain edit/view mode mutual exclusivity."
                          :fr/ids #{:fr.struct/create-sibling}
                          :spec [:map [:type [:= :create-and-enter-edit]]]
                          :handler (fn [db _session _intent]
                                     (let [focus-id (q/focus db)
                                           parent (q/parent-of db focus-id)
                                           new-id (str "block-" (random-uuid))]
                                       {:ops [;; Create and place new block
                                              {:op :create-node :id new-id :type :block :props {:text ""}}
                                              {:op :place :id new-id :under parent :at {:after focus-id}}]
                                        ;; Session updates: clear selection, enter edit mode
                                        :session-updates {:selection {:nodes #{} :focus nil :anchor nil}
                                                          :ui {:editing-block-id new-id
                                                               :cursor-position 0}}}))})

(intent/register-intent! :create-block-in-page
                         {:doc "Create new block directly under a page (for empty pages).
   Used when a page has no blocks and user clicks to add one.
   Enters edit mode on the new block."
                          :fr/ids #{:fr.struct/create-sibling}
                          :spec [:map
                                 [:type [:= :create-block-in-page]]
                                 [:page-id [:or :string :keyword]]
                                 [:block-id :string]]
                          :handler (fn [_db _session {:keys [page-id block-id]}]
                                     {:ops [{:op :create-node :id block-id :type :block :props {:text ""}}
                                            {:op :place :id block-id :under page-id :at :first}]
                                      :session-updates {:selection {:nodes #{} :focus nil :anchor nil}
                                                        :ui {:editing-block-id block-id
                                                             :cursor-position 0}}})})

;; ── Multi-select intents ──────────────────────────────────────────────────────

(defn- sort-by-doc-order
  "Sort node IDs by document order (pre-order traversal).
   Ensures operations are applied top-to-bottom, left-to-right."
  [db ids]
  (sort-by #(get-in db [:derived :pre %] ##Inf) ids))

(defn- active-targets
  "Return selected node IDs or the currently editing block (vector, doc-ordered)."
  [db session]
  (let [selected (q/selection session)
        editing-id (q/editing-block-id session)
        targets (cond
                  (seq selected) selected
                  editing-id [editing-id]
                  :else [])]
    (vec (sort-by-doc-order db targets))))

(defn- filter-top-level-targets
  "Filter targets to only include 'top-level' blocks in the selection tree.

   LOGSEQ PARITY: When a parent and its children are both selected, only the parent
   should be moved (since moving the parent also moves its children).

   Algorithm: Remove any block whose parent is also in the selection.

   Example:
     Selection: [A, B (child of A), C]
     Result: [A, C] (B is filtered out because its parent A is in selection)"
  [db targets]
  (let [target-set (set targets)
        ;; Find parents that are also in the selection
        parent-ids-in-selection (set (filter target-set
                                             (map #(q/parent-of db %) targets)))]
    ;; Keep only blocks whose parent is NOT in the selection
    (vec (remove #(contains? parent-ids-in-selection (q/parent-of db %)) targets))))

#_{:clj-kondo/ignore [:unused-private-var]} ; Entry point for multi-select ops
(defn- apply-to-active-targets
  "Apply op-fn to each active target node, returning combined ops vector.

   All op-fns MUST have uniform signature: (fn [db session id]).
   This eliminates arity mismatch bugs - no conditional dispatch needed."
  [db session op-fn]
  (->> (active-targets db session)
       (mapcat #(op-fn db session %))
       vec))

(defn- same-parent?
  "Check that all ids share the same parent."
  [db ids]
  (when (seq ids)
    (let [parent (q/parent-of db (first ids))]
      (and parent
           (every? #(= parent (q/parent-of db %)) (rest ids))
           parent))))

(defn- consecutive-siblings?
  "Check if all ids are consecutive siblings (no gaps between them).

   LOGSEQ PARITY: Logseq rejects indent/outdent on non-consecutive selections
   to prevent unexpected structural changes.

   Example:
     [a b c] under parent [a b c d] → true (consecutive)
     [a c] under parent [a b c d] → false (b missing)
     [a b] under parent [a b c d] → true (consecutive)"
  [db ids]
  (when (and (seq ids) (same-parent? db ids))
    (let [parent (q/parent-of db (first ids))
          children (get-in db [:children-by-parent parent] [])
          id-set (set ids)
          ;; Find indices of selected IDs in children vector
          indices (keep-indexed (fn [idx child]
                                  (when (id-set child) idx))
                                children)]
      ;; Check if indices are consecutive (each is prev + 1)
      (or (< (count indices) 2)
          (every? (fn [[a b]] (= b (inc a)))
                  (partition 2 1 (sort indices)))))))

#_{:clj-kondo/ignore [:unused-private-var]} ; Scaffolded for Logseq gap-fill behavior
(defn- consolidate-to-consecutive
  "Fill gaps in non-consecutive selection to make it consecutive.

   LOGSEQ PARITY: When selection has gaps, consolidate to the full range
   from first to last selected sibling.

   Example:
     Parent's children: [a b c d e]
     Selection: [a c e] (non-consecutive)
     Result: [a b c d e] (filled gaps)

   Returns nil if blocks don't share same parent."
  [db ids]
  (when-let [parent (same-parent? db ids)]
    (let [children (get-in db [:children-by-parent parent] [])
          id-set (set ids)
          indices (keep-indexed (fn [idx child]
                                  (when (id-set child) idx))
                                children)
          min-idx (apply min indices)
          max-idx (apply max indices)]
      ;; Return all siblings from min to max index (inclusive)
      (vec (subvec children min-idx (inc max-idx))))))

(defn- move-selected-up-ops
  "Move selected nodes up one sibling position.

   Logseq climb semantics: When selection is at first-child position (no previous sibling),
   'climb out' by re-parenting under grandparent, positioned immediately before parent.

   Example:
     Before (first-child climb):
       - Grandparent
         - Parent
           - A ← move up (first child, no prev sibling)
           - B

     After:
       - Grandparent
         - A    ← climbed out, now sibling of Parent (before Parent)
         - Parent
           - B

   Multi-select: All selected nodes climb together, preserving their relative order.
   - Filters to top-level blocks only (if parent and child both selected, only parent moves)
   - Selection must be consecutive siblings (no gaps) - non-consecutive selections are no-op

   Boundary: Cannot climb if:
   - Parent is a root
   - Parent is a page (blocks stay within their page)
   - Grandparent is outside zoom scope"
  [db session]
  (let [raw-targets (active-targets db session)
        ;; LOGSEQ PARITY: Filter to top-level blocks (remove children of selected parents)
        targets (filter-top-level-targets db raw-targets)
        first-id (first targets)
        parent (same-parent? db targets)
        ;; LOGSEQ PARITY: Reject non-consecutive selections to prevent split moves
        consecutive? (consecutive-siblings? db targets)
        prev (when first-id (q/prev-sibling db first-id))
        before-prev (when prev (q/prev-sibling db prev))]
    (cond
      ;; No targets after filtering
      (empty? targets)
      []

      ;; Reject non-consecutive selection
      (and parent (not consecutive?))
      []

      ;; Normal case: has previous sibling, move before it
      (and parent prev)
      (intent/intent->ops db {:type :move
                              :selection targets
                              :parent parent
                              :anchor (if before-prev {:after before-prev} :first)})

      ;; Climb case: first child with no prev sibling
      ;; Re-parent under grandparent, place before parent
      ;; FR-Scope-02: Prevent climb if grandparent is outside zoom scope
      (and parent (not prev) first-id)
      (let [grandparent (q/parent-of db parent)
            roots (set (:roots db const/roots))
            parent-type (get-in db [:nodes parent :type])]
        ;; Can climb if:
        ;; - grandparent exists
        ;; - parent is NOT a root
        ;; - parent is NOT a page (blocks stay within their page - Logseq parity)
        ;; - grandparent is within zoom scope
        (if (and grandparent
                 (not (contains? roots parent))
                 (not= parent-type :page)
                 (within-zoom-scope? db session grandparent))
          ;; Can climb: move to grandparent level, positioned before parent
          (let [parent-prev (q/prev-sibling db parent)]
            (intent/intent->ops db {:type :move
                                    :selection targets
                                    :parent grandparent
                                    :anchor (if parent-prev {:after parent-prev} :first)}))
          ;; Can't climb: at page boundary OR grandparent outside zoom
          []))

      ;; No valid move
      :else [])))

(defn- move-selected-down-ops
  "Move selected nodes down one sibling position.

   Logseq descend semantics: When selection is at last-child position (no next sibling),
   'descend' by re-parenting under the next sibling of parent, positioned as first child.

   Example:
     Before (last-child descend):
       - Grandparent
         - Parent
           - A
           - B ← move down (last child, no next sibling)
         - Uncle (parent's next sibling)
           - C

     After:
       - Grandparent
         - Parent
           - A
         - Uncle
           - B    ← descended into Uncle, now first child
           - C

   Multi-select: All selected nodes descend together, preserving their relative order.
   - Filters to top-level blocks only (if parent and child both selected, only parent moves)
   - Selection must be consecutive siblings (no gaps) - non-consecutive selections are no-op

   Boundary: Cannot descend if:
   - Parent is a page (would cross to different page)
   - Target is outside zoom scope"
  [db session]
  (let [raw-targets (active-targets db session)
        ;; LOGSEQ PARITY: Filter to top-level blocks (remove children of selected parents)
        targets (filter-top-level-targets db raw-targets)
        last-id (last targets)
        parent (same-parent? db targets)
        ;; LOGSEQ PARITY: Reject non-consecutive selections to prevent split moves
        consecutive? (consecutive-siblings? db targets)
        next-sib (when last-id (get-in db [:derived :next-id-of last-id]))]
    (cond
      ;; No targets after filtering
      (empty? targets)
      []

      ;; Reject non-consecutive selection
      (and parent (not consecutive?))
      []

      ;; Normal case: has next sibling, move after it
      (and parent next-sib)
      (intent/intent->ops db {:type :move
                              :selection targets
                              :parent parent
                              :anchor {:after next-sib}})

      ;; Descend case: last child with no next sibling
      ;; Re-parent under parent's next sibling (if it exists), placed as first child
      ;; FR-Scope-02: Prevent descend if target is outside zoom scope
      (and parent (not next-sib) last-id)
      (let [parent-next (q/next-sibling db parent)
            parent-type (get-in db [:nodes parent :type])]
        ;; Can descend if:
        ;; - parent's next sibling exists
        ;; - parent is NOT a page (prevents cross-page movement - Logseq parity)
        ;; - target is within zoom scope
        (if (and parent-next
                 (not= parent-type :page)
                 (within-zoom-scope? db session parent-next))
          ;; Can descend: move into parent's next sibling as first child
          (intent/intent->ops db {:type :move
                                  :selection targets
                                  :parent parent-next
                                  :anchor :first})
          ;; Can't descend: at page boundary OR target outside zoom
          []))

      ;; No valid move
      :else [])))

(intent/register-intent! :delete-selected
                         {:doc "Delete all selected nodes (or editing block if no selection).

         LOGSEQ PARITY: After deletion, focus moves to:
         1. Previous visible block (preferred)
         2. Next visible block (if no previous)
         3. Nothing (if page becomes empty)"
                          :fr/ids #{:fr.struct/delete-block}
                          :spec [:map [:type [:= :delete-selected]]]
                          :handler (fn [db session _intent]
                                     (let [targets (active-targets db session)
                                           ;; Find focus target BEFORE deletion
                                           ;; Sort targets to get first/last in doc order
                                           first-target (first targets)
                                           last-target (last targets)
                                           ;; Prefer previous block, fall back to next
                                           prev-block (when first-target
                                                        (q/prev-block-dom-order db first-target))
                                           next-block (when (and (not prev-block) last-target)
                                                        (q/next-block-dom-order db last-target))
                                           ;; But next-block might BE one of the targets, find one that isn't
                                           target-set (set targets)
                                           safe-next (when next-block
                                                       (loop [candidate next-block]
                                                         (cond
                                                           (nil? candidate) nil
                                                           (not (contains? target-set candidate)) candidate
                                                           :else (recur (q/next-block-dom-order db candidate)))))
                                           new-focus (or prev-block safe-next)]
                                       {:ops (vec (mapcat #(delete-ops db session %) targets))
                                        :session-updates {:selection {:nodes (if new-focus #{new-focus} #{})
                                                                      :focus new-focus
                                                                      :anchor new-focus}
                                                          :ui {:editing-block-id nil}}}))})

(intent/register-intent! :indent-selected
                         {:doc "Indent all selected/editing nodes (Logseq parity).

                          For single block: moves under previous sibling.
                          For multi-select: ALL blocks move under first's prev-sibling,
                          remaining siblings at the same level.

                          LOGSEQ PARITY:
                          - Parent+child selections filter to top-level only (child comes along)
                          - Non-consecutive sibling selections are rejected (no-op)
                          - If target sibling is collapsed, it's expanded"

                          :fr/ids #{:fr.struct/indent-outdent}

                          :spec [:map [:type [:= :indent-selected]]]
                          :handler (fn [db session _intent]
                                     (let [editing-id (q/editing-block-id session)
                                           raw-targets (active-targets db session)
                                           ;; Filter to top-level: if parent+child selected, only parent moves
                                           targets (filter-top-level-targets db raw-targets)]
                                       ;; LOGSEQ PARITY: Reject non-consecutive multi-selection
                                       (if (and (> (count targets) 1)
                                                (not (consecutive-siblings? db targets)))
                                         ;; Non-consecutive: no-op
                                         {:ops []
                                          :session-updates (when editing-id
                                                             {:ui {:editing-block-id editing-id}})}
                                         ;; Valid selection: proceed with indent
                                         (let [result (if (= 1 (count targets))
                                                        (indent-ops db session (first targets))
                                                        (indent-multi-ops db session targets))
                                               {:keys [ops unfold-target]} result
                                               ;; Build session updates
                                               base-updates (when editing-id
                                                              {:ui {:editing-block-id editing-id}})
                                               ;; LOGSEQ PARITY: Expand collapsed target
                                               unfold-updates (when unfold-target
                                                                {:ui {:folded (disj (q/folded-set session) unfold-target)}})]
                                           {:ops (or ops [])
                                            :session-updates (merge base-updates unfold-updates)}))))})

(intent/register-intent! :outdent-selected
                         {:doc "Outdent all selected/editing nodes (Logseq parity).

                          For single block: moves after parent (logical outdent).
                          For multi-select: ALL blocks move after parent in order,
                          maintaining their relative positions.

                          LOGSEQ PARITY:
                          - Parent+child selections filter to top-level only (child comes along)
                          - Non-consecutive sibling selections are rejected (no-op)"

                          :fr/ids #{:fr.struct/indent-outdent}

                          :spec [:map [:type [:= :outdent-selected]]]
                          :handler (fn [db session _intent]
                                     (let [editing-id (q/editing-block-id session)
                                           raw-targets (active-targets db session)
                                           ;; Filter to top-level: if parent+child selected, only parent moves
                                           targets (filter-top-level-targets db raw-targets)]
                                       ;; LOGSEQ PARITY: Reject non-consecutive multi-selection
                                       (if (and (> (count targets) 1)
                                                (not (consecutive-siblings? db targets)))
                                         ;; Non-consecutive: no-op
                                         {:ops []
                                          :session-updates (when editing-id
                                                             {:ui {:editing-block-id editing-id}})}
                                         ;; Valid selection: proceed with outdent
                                         (let [ops (if (= 1 (count targets))
                                                     (outdent-ops db session (first targets))
                                                     (or (outdent-multi-ops db session targets) []))]
                                           {:ops ops
                                            :session-updates (when editing-id
                                                               {:ui {:editing-block-id editing-id}})}))))})

(intent/register-intent! :move-selected-up
                         {:doc "Move selected nodes up one sibling position."

                          :fr/ids #{:fr.struct/climb-descend}

                          :spec [:map [:type [:= :move-selected-up]]]
                          :handler (fn [db session _intent]
                                     (move-selected-up-ops db session))})

(intent/register-intent! :move-selected-down
                         {:doc "Move selected nodes down one sibling position."

                          :fr/ids #{:fr.struct/climb-descend}

                          :spec [:map [:type [:= :move-selected-down]]]
                          :handler (fn [db session _intent]
                                     (move-selected-down-ops db session))})

;; ── Movement/Reordering (merged from plugins.permute) ────────────────────────

(defn planned-positions
  "Compute target sibling vector after applying selection at the given anchor.

   Args:
     db - database
     selection - vector of node IDs to move (preserves order)
     parent - target parent ID
     anchor - position anchor (from kernel.anchor)

   Returns:
     Vector representing the final sibling order after move.

   Algorithm:
     1. Remove all selected nodes from parent's current children
     2. Resolve anchor position in the remaining siblings
     3. Insert selection at that position (preserving internal order)"
  [db {:keys [selection parent anchor]}]
  (let [current-kids (pos/children db parent)
        selection-set (set selection)
        kids-without-selection (vec (remove selection-set current-kids))
        target-idx (try
                     (pos/resolve-anchor-in-vec kids-without-selection anchor)
                     (catch #?(:clj Exception :cljs js/Error) _
                       ;; If anchor references a selected node, it will fail after removal
                       ;; Fallback to end
                       (count kids-without-selection)))
        safe-idx (min target-idx (count kids-without-selection))
        [head tail] (split-at safe-idx kids-without-selection)]
    (vec (concat head selection tail))))

(defn lower-reorder
  "Lower a :move intent to a minimal sequence of :place operations.

   Intent schema:
   {:selection [id ...]      ; IDs to move/reorder (non-contiguous OK)
    :parent parent-id        ; target parent
    :anchor Anchor}          ; where selection lands

   Returns: vector of :place ops that achieve the reorder.

   Strategy: emit one :place per selected ID, in target order, using relative anchors.
   Each :place uses {:after prev-id} to build up the sequence incrementally."
  [db intent]
  (let [{:keys [selection parent anchor]} intent
        target-order (planned-positions db intent)

        ;; Build ops: place each selected node using {:after previous-in-target-order}
        ops (reduce (fn [ops-acc id]
                      (let [;; Find what comes before this ID in target order
                            idx-in-target (.indexOf target-order id)
                            prev-id (when (pos? idx-in-target)
                                      (nth target-order (dec idx-in-target)))]
                        (conj ops-acc
                              (if prev-id
                                {:op :place
                                 :id id
                                 :under parent
                                 :at {:after prev-id}}
                                ;; First in selection goes at the anchor
                                {:op :place
                                 :id id
                                 :under parent
                                 :at anchor}))))
                    []
                    selection)]
    ops))

(defn- find-missing-nodes
  "Return vector of selection IDs that don't exist in db."
  [db selection]
  (let [nodes (:nodes db)]
    (filterv #(not (contains? nodes %)) selection)))

(defn- would-create-cycle-any?
  "Check if moving any node in selection under parent would create a cycle.
   A cycle occurs when parent is a descendant of any selected node.
   Short-circuits on first cycle found."
  [db selection parent]
  (and (string? parent) ; Keywords (roots) can't be descendants
       (some #(db/descendant-of? db % parent) selection)))

(defn validate-move-intent
  "Validate a move intent before lowering.

   Returns: nil if valid, or issue map if invalid.

   Checks:
   - Selection IDs exist
   - Parent exists
   - No cycles (none of selection are ancestors of parent)"
  [db {:keys [selection parent] :as intent}]
  (let [missing (find-missing-nodes db selection)]
    (cond
      (seq missing)
      {:reason ::node-not-found
       :hint "One or more selected nodes don't exist"
       :missing missing
       :intent intent}

      (not (db/valid-parent? db parent))
      {:reason ::parent-not-found
       :hint (str "Parent " parent " doesn't exist")
       :parent parent
       :intent intent}

      (would-create-cycle-any? db selection parent)
      {:reason ::would-create-cycle
       :hint "Cannot move node into its own descendant"
       :intent intent}

      :else nil)))

(defn lower-move
  "Main entry point: lower a move intent to ops.

   Returns:
   - {:ops [Op...]} if valid
   - {:issues [Issue...]} if invalid"
  [db intent]
  (if-let [issue (validate-move-intent db intent)]
    {:issues [issue]}
    {:ops (lower-reorder db intent)}))

(intent/register-intent! :move
                         {:doc "Move selection to target parent at anchor position (handles both cross-parent and same-parent reordering)."
                          :spec [:map
                                 [:type [:= :move]]
                                 [:selection [:vector :string]]
                                 [:parent [:or :string :keyword]] ; keyword for :doc root
                                 [:anchor [:or
                                           :keyword ; :first, :last
                                           [:map [:after :string]]
                                           [:map [:before :string]]]]]
                          :fr/ids #{:fr.struct/climb-descend}
                          :handler (fn [db _session intent]
                                     (:ops (lower-move db intent)))})

;; ── Move While Editing ────────────────────────────────────────────────────────

(intent/register-intent! :move-block-up-while-editing
                         {:doc "Move current editing block up, preserving edit mode."
                          :spec [:map [:type [:= :move-block-up-while-editing]] [:block-id :string]]
                          :fr/ids #{:fr.struct/climb-descend}
                          :handler (fn [db session {:keys [_block-id]}]
              ;; Use existing move-selected-up logic but don't exit edit mode
              ;; Just return the structural move ops (uses selection, not block-id)
                                     (move-selected-up-ops db session))})

(intent/register-intent! :move-block-down-while-editing
                         {:doc "Move current editing block down, preserving edit mode."
                          :spec [:map [:type [:= :move-block-down-while-editing]] [:block-id :string]]
                          :fr/ids #{:fr.struct/climb-descend}
                          :handler (fn [db session {:keys [_block-id]}]
              ;; Use existing move-selected-down logic (uses selection, not block-id)
                                     (move-selected-down-ops db session))})
