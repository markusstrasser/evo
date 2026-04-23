(ns plugins.folding
  "Folding plugin: expand/collapse operations for outline navigation.

   Fold state stored in session/ui (ephemeral, not in history).

   Features:
   - Toggle fold: show/hide children of a block
   - Expand all: recursively show all descendants
   - Collapse: hide children"
  (:require [kernel.intent :as intent]
            [kernel.query :as q]
            [utils.collection :as coll]))

;; Sentinel for DCE prevention - referenced by spec.runner

;; ── Private Helpers ───────────────────────────────────────────────────────────
;; NOTE: folded-set, zoom-stack, zoom-root moved to kernel.query (canonical source)

(defn- has-children?
  "Check if a block has children."
  [db block-id]
  (seq (get-in db [:children-by-parent block-id])))

(defn- all-descendant-ids
  "Get all descendant IDs of a block (DFS traversal)."
  [db block-id]
  (let [children (get-in db [:children-by-parent block-id] [])]
    (into children (mapcat #(all-descendant-ids db %) children))))

;; ── Query API (Public) ────────────────────────────────────────────────────────

(defn folded?
  "Check if a block is currently folded (children hidden).
   Takes session (not db) since fold state is ephemeral.
   db param kept for API consistency with other predicates."
  [_db session block-id]
  (contains? (q/folded-set session) block-id))

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
  (count (q/zoom-stack session)))

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
                                       {:session-updates
                                        {:ui {:folded (-> session
                                                          q/folded-set
                                                          (coll/toggle-membership block-id))}}}))})

(intent/register-intent! :expand-all
                         {:doc "Recursively expand a block and all descendants."
                          :spec [:map [:type [:= :expand-all]] [:block-id :string]]
                          :fr/ids #{:fr.fold/expand-collapse-all}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [all-ids (cons block-id (all-descendant-ids db block-id))]
                                         {:session-updates
                                          {:ui {:folded (-> session
                                                            q/folded-set
                                                            (coll/remove-all all-ids))}}})))})

(intent/register-intent! :collapse
                         {:doc "Collapse a block (hide children)."
                          :spec [:map [:type [:= :collapse]] [:block-id :string]]
                          :fr/ids #{:fr.fold/expand-collapse-all}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       {:session-updates
                                        {:ui {:folded (-> session
                                                          q/folded-set
                                                          (conj block-id))}}}))})

(intent/register-intent! :toggle-subtree
                         {:doc "Toggle entire subtree (Alt+Click on bullet - Logseq parity FR-Pointer-01).

         If any descendant is expanded, collapse all descendants.
         If all descendants are collapsed, expand all descendants."
                          :spec [:map [:type [:= :toggle-subtree]] [:block-id :string]]
                          :fr/ids #{:fr.pointer/alt-click-fold}
                          :allowed-states #{:editing :selection}
                          :handler (fn [db session {:keys [block-id]}]
                                     (when (has-children? db block-id)
                                       (let [all-ids (cons block-id (all-descendant-ids db block-id))
                                             current-folded (q/folded-set session)
                                             all-collapsed? (every? current-folded all-ids)
                                             new-folded (if all-collapsed?
                                                          (coll/remove-all current-folded all-ids)
                                                          (coll/add-all current-folded all-ids))]
                                         {:session-updates {:ui {:folded new-folded}}})))})

(intent/register-intent! :toggle-all-folds
                         {:doc "Toggle all folds on a page. Expand all if any collapsed, else collapse all top-level."
                          :spec [:map [:type [:= :toggle-all-folds]] [:root-id :string]]
                          :fr/ids #{:fr.fold/expand-collapse-all}
                          :handler (fn [db session {:keys [root-id]}]
                                     (let [all-ids (all-descendant-ids db root-id)
                                           current-folded (q/folded-set session)
                                           any-folded? (some current-folded all-ids)
                                           new-folded (if any-folded?
                                                        (coll/remove-all current-folded all-ids)
                                                        (coll/add-all current-folded
                                                                      (get-in db [:children-by-parent root-id])))]
                                       {:session-updates {:ui {:folded new-folded}}}))})

;; ── Doc-mode Intent ──────────────────────────────────────────────────────────

(intent/register-intent! :toggle-doc-mode
                         {:doc "Toggle doc-mode (swap Enter/Shift+Enter behavior).
         In doc-mode: Enter inserts newline, Shift+Enter creates new block."
                          :spec [:map [:type [:= :toggle-doc-mode]]]
                          :fr/ids #{:fr.edit/newline-no-split}
                          :handler (fn [_db session _intent]
                                     {:session-updates
                                      {:ui {:document-view? (not (get-in session [:ui :document-view?] false))}}})})

;; ── UI Chrome Intents ─────────────────────────────────────────────────────────

(intent/register-intent! :toggle-sidebar
                         {:doc "Toggle left sidebar (pages) visibility. Bound to Cmd+B."
                          :fr/ids #{:fr.ui/quick-switcher}
                          :spec [:map [:type [:= :toggle-sidebar]]]
                          :handler (fn [_db session _intent]
                                     {:session-updates
                                      {:ui {:sidebar-visible? (not (get-in session [:ui :sidebar-visible?] true))}}})})

(intent/register-intent! :toggle-hotkeys
                         {:doc "Toggle hotkeys reference panel visibility. Bound to Cmd+P."
                          :fr/ids #{:fr.ui/quick-switcher}
                          :spec [:map [:type [:= :toggle-hotkeys]]]
                          :handler (fn [_db session _intent]
                                     {:session-updates
                                      {:ui {:hotkeys-visible? (not (get-in session [:ui :hotkeys-visible?] false))}}})})

(intent/register-intent! :toggle-reading-mode
                         {:doc "Toggle reading/focus mode (bigger font, hide sidebar). Bound to Cmd+Shift+E."
                          :fr/ids #{:fr.ui/quick-switcher}
                          :spec [:map [:type [:= :toggle-reading-mode]]]
                          :handler (fn [_db session _intent]
                                     {:session-updates
                                      {:ui {:reading-mode? (not (get-in session [:ui :reading-mode?] false))}}})})

(intent/register-intent! :toggle-quick-switcher
                         {:doc "Toggle quick switcher (page search) visibility. Bound to Cmd+K."
                          :fr/ids #{:fr.ui/quick-switcher}
                          :spec [:map [:type [:= :toggle-quick-switcher]]]
                          :handler (fn [_db session _intent]
                                     (let [visible? (some? (get-in session [:ui :quick-switcher]))]
                                       {:session-updates
                                        {:ui {:quick-switcher (when-not visible?
                                                                {:query ""
                                                                 :selected-idx 0})}}}))})


;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
