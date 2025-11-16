(ns plugins.folding
  "Folding plugin: expand/collapse/zoom operations for outline navigation.

   Fold state stored in session/ui (ephemeral, not in history).
   Zoom stack stored in session/ui (navigation history).

   Features:
   - Toggle fold: show/hide children of a block
   - Expand all: recursively show all descendants
   - Collapse: hide children
   - Zoom in: focus on a block (make it rendering root)
   - Zoom out: return to parent context"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]))

;; ── Private Helpers ───────────────────────────────────────────────────────────

(defn- get-folded-set
  "Get the set of folded block IDs from session state."
  [db]
  (get-in db [:nodes const/session-ui-id :props :folded] #{}))

(defn- get-zoom-stack
  "Get the zoom navigation stack from session state."
  [db]
  (get-in db [:nodes const/session-ui-id :props :zoom-stack] []))

(defn- get-zoom-root
  "Get the current zoom root (rendering root block ID)."
  [db]
  (get-in db [:nodes const/session-ui-id :props :zoom-root]))

(defn- has-children?
  "Check if a block has children."
  [db block-id]
  (seq (get-in db [:children-by-parent block-id])))

(defn- all-descendant-ids
  "Get all descendant IDs of a block (DFS traversal)."
  [db block-id]
  (let [children (get-in db [:children-by-parent block-id] [])]
    (concat children
            (mapcat #(all-descendant-ids db %) children))))

;; ── Query API (Public) ────────────────────────────────────────────────────────

(defn folded?
  "Check if a block is currently folded (children hidden)."
  [db block-id]
  (contains? (get-folded-set db) block-id))

(defn collapsible?
  "Check if a block can be collapsed (has children and not already folded)."
  [db block-id]
  (and (has-children? db block-id)
       (not (folded? db block-id))))

(defn expandable?
  "Check if a block can be expanded (has children and is currently folded)."
  [db block-id]
  (and (has-children? db block-id)
       (folded? db block-id)))

(defn zoom-level
  "Get current zoom level (0 = root, 1+ = zoomed in)."
  [db]
  (count (get-zoom-stack db)))

(defn in-zoom?
  "Check if currently zoomed into a block."
  [db]
  (pos? (zoom-level db)))

;; ── Fold Intents ──────────────────────────────────────────────────────────────

(intent/register-intent! :toggle-fold
  {:doc "Toggle expand/collapse state for a block."
   :spec [:map [:type [:= :toggle-fold]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (when (has-children? db block-id)
                (let [folded-set (get-folded-set db)
                      currently-folded? (contains? folded-set block-id)
                      new-folded (if currently-folded?
                                  (disj folded-set block-id)
                                  (conj folded-set block-id))]
                  [{:op :update-node
                    :id const/session-ui-id
                    :props {:folded new-folded}}])))})

(intent/register-intent! :expand-all
  {:doc "Recursively expand a block and all descendants."
   :spec [:map [:type [:= :expand-all]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (when (has-children? db block-id)
                (let [descendants (all-descendant-ids db block-id)
                      all-ids (cons block-id descendants)
                      folded-set (get-folded-set db)
                      new-folded (apply disj folded-set all-ids)]
                  [{:op :update-node
                    :id const/session-ui-id
                    :props {:folded new-folded}}])))})

(intent/register-intent! :collapse
  {:doc "Collapse a block (hide children)."
   :spec [:map [:type [:= :collapse]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (when (has-children? db block-id)
                (let [folded-set (get-folded-set db)
                      new-folded (conj folded-set block-id)]
                  [{:op :update-node
                    :id const/session-ui-id
                    :props {:folded new-folded}}])))})

(intent/register-intent! :toggle-subtree
  {:doc "Toggle entire subtree (Alt+Click on bullet - Logseq parity FR-Pointer-01).

         If any descendant is expanded, collapse all descendants.
         If all descendants are collapsed, expand all descendants."
   :spec [:map [:type [:= :toggle-subtree]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (when (has-children? db block-id)
                (let [descendants (all-descendant-ids db block-id)
                      all-ids (cons block-id descendants)
                      folded-set (get-folded-set db)
                      ;; Check if all descendants are collapsed
                      all-collapsed? (every? folded-set all-ids)
                      new-folded (if all-collapsed?
                                  ;; Expand all
                                  (apply disj folded-set all-ids)
                                  ;; Collapse all
                                  (apply conj folded-set all-ids))]
                  [{:op :update-node
                    :id const/session-ui-id
                    :props {:folded new-folded}}])))})

(intent/register-intent! :toggle-all-folds
  {:doc "Toggle all folds on a page. Expand all if any collapsed, else collapse all top-level."
   :spec [:map [:type [:= :toggle-all-folds]] [:root-id :string]]
   :handler (fn [db {:keys [root-id]}]
              (let [all-ids (all-descendant-ids db root-id)
                    folded-set (get-folded-set db)
                    any-folded? (some folded-set all-ids)
                    new-folded (if any-folded?
                                ;; Expand all
                                (apply disj folded-set all-ids)
                                ;; Collapse all top-level
                                (let [top-level (get-in db [:children-by-parent root-id])]
                                  (apply conj folded-set top-level)))]
                [{:op :update-node
                  :id const/session-ui-id
                  :props {:folded new-folded}}]))})

;; ── Zoom Intents ──────────────────────────────────────────────────────────────

(intent/register-intent! :zoom-in
  {:doc "Zoom into a block (make it the rendering root)."
   :spec [:map [:type [:= :zoom-in]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (when (has-children? db block-id)
                (let [current-stack (get-zoom-stack db)
                      current-root (or (get-zoom-root db) const/root-doc)
                      new-stack (conj current-stack {:block-id current-root})]
                  [{:op :update-node
                    :id const/session-ui-id
                    :props {:zoom-stack new-stack
                            :zoom-root block-id}}])))})

(intent/register-intent! :zoom-out
  {:doc "Zoom out to previous level."
   :spec [:map [:type [:= :zoom-out]]]
   :handler (fn [db _intent]
              (let [current-stack (get-zoom-stack db)]
                (when (seq current-stack)
                  (let [previous-level (peek current-stack)
                        new-stack (pop current-stack)
                        new-root (if previous-level
                                  (:block-id previous-level)
                                  const/root-doc)]
                    [{:op :update-node
                      :id const/session-ui-id
                      :props {:zoom-stack new-stack
                              :zoom-root new-root}}]))))})

(intent/register-intent! :zoom-to
  {:doc "Zoom to specific block in zoom stack (breadcrumb click)."
   :spec [:map [:type [:= :zoom-to]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (let [current-stack (get-zoom-stack db)
                    target-idx (first (keep-indexed
                                       (fn [i level]
                                         (when (= (:block-id level) block-id) i))
                                       current-stack))]
                (when target-idx
                  (let [new-stack (subvec current-stack 0 (inc target-idx))]
                    [{:op :update-node
                      :id const/session-ui-id
                      :props {:zoom-stack new-stack
                              :zoom-root block-id}}]))))})

(intent/register-intent! :reset-zoom
  {:doc "Reset zoom to root (clear zoom stack)."
   :spec [:map [:type [:= :reset-zoom]]]
   :handler (fn [_db _intent]
              [{:op :update-node
                :id const/session-ui-id
                :props {:zoom-stack []
                        :zoom-root nil}}])})
