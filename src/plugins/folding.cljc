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

;; Sentinel for DCE prevention - referenced by spec.runner

;; ── Private Helpers ───────────────────────────────────────────────────────────

(defn- get-folded-set
  "Get the set of folded block IDs from session state."
  [session]
  (get-in session [:ui :folded] #{}))

(defn- get-zoom-stack
  "Get the zoom navigation stack from session state."
  [session]
  (get-in session [:ui :zoom-stack] []))

(defn- get-zoom-root
  "Get the current zoom root (rendering root block ID)."
  [session]
  (get-in session [:ui :zoom-root]))

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
  "Check if a block is currently folded (children hidden).
   Takes session (not db) since fold state is ephemeral.
   db param kept for API consistency with other predicates."
  [_db session block-id]
  (contains? (get-folded-set session) block-id))

(defn collapsible?
  "Check if a block can be collapsed (has children and not already folded)."
  [db session block-id]
  (and (has-children? db block-id)
       (not (folded? db session block-id))))

(defn expandable?
  "Check if a block can be expanded (has children and is currently folded)."
  [db session block-id]
  (and (has-children? db block-id)
       (folded? db session block-id)))

(defn zoom-level
  "Get current zoom level (0 = root, 1+ = zoomed in)."
  [session]
  (count (get-zoom-stack session)))

(defn in-zoom?
  "Check if currently zoomed into a block."
  [session]
  (pos? (zoom-level session)))

;; ── Fold Intents ──────────────────────────────────────────────────────────────

(intent/register-intent! :toggle-fold
                         {:doc "Toggle expand/collapse state for a block."
                          :spec [:map [:type [:= :toggle-fold]] [:block-id :string]]
                          :fr/ids #{:fr.fold/toggle-block}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [folded-set (get-folded-set session)
                                             currently-folded? (contains? folded-set block-id)
                                             new-folded (if currently-folded?
                                                          (disj folded-set block-id)
                                                          (conj folded-set block-id))]
                                         {:session-updates {:ui {:folded new-folded}}})))})

(intent/register-intent! :expand-all
                         {:doc "Recursively expand a block and all descendants."
                          :spec [:map [:type [:= :expand-all]] [:block-id :string]]
                          :fr/ids #{:fr.fold/expand-collapse-all}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [descendants (all-descendant-ids db block-id)
                                             all-ids (cons block-id descendants)
                                             folded-set (get-folded-set session)
                                             new-folded (apply disj folded-set all-ids)]
                                         {:session-updates {:ui {:folded new-folded}}})))})

(intent/register-intent! :collapse
                         {:doc "Collapse a block (hide children)."
                          :spec [:map [:type [:= :collapse]] [:block-id :string]]
                          :fr/ids #{:fr.fold/expand-collapse-all}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [folded-set (get-folded-set session)
                                             new-folded (conj folded-set block-id)]
                                         {:session-updates {:ui {:folded new-folded}}})))})

(intent/register-intent! :toggle-subtree
                         {:doc "Toggle entire subtree (Alt+Click on bullet - Logseq parity FR-Pointer-01).

         If any descendant is expanded, collapse all descendants.
         If all descendants are collapsed, expand all descendants."
                          :spec [:map [:type [:= :toggle-subtree]] [:block-id :string]]
                          :fr/ids #{:fr.pointer/alt-click-fold}
                          :allowed-states #{:editing :selection}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [descendants (all-descendant-ids db block-id)
                                             all-ids (cons block-id descendants)
                                             folded-set (get-folded-set session)
                      ;; Check if all descendants are collapsed
                                             all-collapsed? (every? folded-set all-ids)
                                             new-folded (if all-collapsed?
                                   ;; Expand all
                                                          (apply disj folded-set all-ids)
                                   ;; Collapse all
                                                          (apply conj folded-set all-ids))]
                                         {:session-updates {:ui {:folded new-folded}}})))})

(intent/register-intent! :toggle-all-folds
                         {:doc "Toggle all folds on a page. Expand all if any collapsed, else collapse all top-level."
                          :spec [:map [:type [:= :toggle-all-folds]] [:root-id :string]]
                          :fr/ids #{:fr.fold/expand-collapse-all}
                          :handler (fn [db session {:keys [root-id]}]
                                     (let [all-ids (all-descendant-ids db root-id)
                                           folded-set (get-folded-set session)
                                           any-folded? (some folded-set all-ids)
                                           new-folded (if any-folded?
                                 ;; Expand all
                                                        (apply disj folded-set all-ids)
                                 ;; Collapse all top-level
                                                        (let [top-level (get-in db [:children-by-parent root-id])]
                                                          (apply conj folded-set top-level)))]
                                       {:session-updates {:ui {:folded new-folded}}}))})

;; ── Zoom Intents ──────────────────────────────────────────────────────────────

(intent/register-intent! :zoom-in
                         {:doc "Zoom into a block (make it the rendering root)."
                          :spec [:map [:type [:= :zoom-in]] [:block-id :string]]
                          :fr/ids #{:fr.zoom/focus-subtree}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [current-stack (get-zoom-stack session)
                                             current-root (or (get-zoom-root session) const/root-doc)
                                             new-stack (conj current-stack {:block-id current-root})]
                                         {:session-updates {:ui {:zoom-stack new-stack
                                                                 :zoom-root block-id}}})))})

(intent/register-intent! :zoom-out
                         {:doc "Zoom out to previous level."
                          :spec [:map [:type [:= :zoom-out]]]
                          :fr/ids #{:fr.zoom/restore-scope}
                          :handler (fn [_db session _intent]
                                     (let [current-stack (get-zoom-stack session)]
                                       (when (seq current-stack)
                                         (let [previous-level (peek current-stack)
                                               new-stack (pop current-stack)
                                               new-root (if previous-level
                                                          (:block-id previous-level)
                                                          const/root-doc)]
                                           {:session-updates {:ui {:zoom-stack new-stack
                                                                   :zoom-root new-root}}}))))})

(intent/register-intent! :zoom-to
                         {:doc "Zoom to specific block in zoom stack (breadcrumb click)."
                          :spec [:map [:type [:= :zoom-to]] [:block-id :string]]
                          :fr/ids #{:fr.zoom/focus-subtree}
                          :handler (fn [_db session {:keys [block-id]}]
                                     (let [current-stack (get-zoom-stack session)
                                           target-idx (first (keep-indexed
                                                              (fn [i level]
                                                                (when (= (:block-id level) block-id) i))
                                                              current-stack))]
                                       (when target-idx
                                         (let [new-stack (subvec current-stack 0 (inc target-idx))]
                                           {:session-updates {:ui {:zoom-stack new-stack
                                                                   :zoom-root block-id}}}))))})

(intent/register-intent! :reset-zoom
                         {:doc "Reset zoom to root (clear zoom stack)."
                          :spec [:map [:type [:= :reset-zoom]]]
                          :fr/ids #{:fr.zoom/restore-scope}
                          :handler (fn [_db _session _intent]
                                     {:session-updates {:ui {:zoom-stack []
                                                             :zoom-root nil}}})})

;; ── Doc-mode Intent ──────────────────────────────────────────────────────────

(intent/register-intent! :toggle-doc-mode
                         {:doc "Toggle doc-mode (swap Enter/Shift+Enter behavior).
         In doc-mode: Enter inserts newline, Shift+Enter creates new block."
                          :spec [:map [:type [:= :toggle-doc-mode]]]
                          :fr/ids #{:fr.edit/newline-no-split}
                          :handler (fn [_db session _intent]
                                     (let [current-mode (get-in session [:ui :document-view?] false)]
                                       {:session-updates {:ui {:document-view? (not current-mode)}}}))})

;; ── UI Chrome Intents ─────────────────────────────────────────────────────────

(intent/register-intent! :toggle-sidebar
                         {:doc "Toggle left sidebar (pages) visibility. Bound to Cmd+B."
                          :spec [:map [:type [:= :toggle-sidebar]]]
                          :handler (fn [_db session _intent]
                                     (let [visible? (get-in session [:ui :sidebar-visible?] true)]
                                       {:session-updates {:ui {:sidebar-visible? (not visible?)}}}))})

(intent/register-intent! :toggle-hotkeys
                         {:doc "Toggle hotkeys reference panel visibility. Bound to Cmd+?."
                          :spec [:map [:type [:= :toggle-hotkeys]]]
                          :handler (fn [_db session _intent]
                                     (let [visible? (get-in session [:ui :hotkeys-visible?] false)]
                                       {:session-updates {:ui {:hotkeys-visible? (not visible?)}}}))})

(intent/register-intent! :toggle-quick-switcher
                         {:doc "Toggle quick switcher (page search) visibility. Bound to Cmd+K."
                          :fr/ids #{:fr.ui/quick-switcher}
                          :spec [:map [:type [:= :toggle-quick-switcher]]]
                          :handler (fn [_db session _intent]
                                     (let [visible? (some? (get-in session [:ui :quick-switcher]))]
                                       {:session-updates {:ui {:quick-switcher (when-not visible?
                                                                                 {:query ""
                                                                                  :selected-idx 0})}}}))})


;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
