(ns plugins.pages
  "Page management plugin for multi-page support.

   Provides intent handlers for:
   - Switching pages
   - Page creation and navigation
   - Deleting pages
   - Following links

   Query helpers (all-pages, page-title, find-page-by-name) moved to kernel.query."
  (:require [clojure.string :as str]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [kernel.constants :as const]
            [utils.intent-helpers :as helpers]
            [utils.text-context :as ctx]
            #?(:cljs [utils.journal :as journal])))

;; Sentinel for DCE prevention - referenced by spec.runner

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defn- page-view-update
  "Canonical page-view session update builder."
  [page-id {:keys [clear-editing? reset-zoom? journals-view? selection-id]}]
  (helpers/merge-session-updates
   {:ui {:current-page page-id
         :journals-view? (boolean journals-view?)}}
   (when clear-editing? (helpers/exit-edit-update))
   (when reset-zoom? {:ui {:zoom-root nil}})
   (when selection-id (helpers/select-only-update selection-id))))

(defn- handle-switch-page
  "Switch to a specific page by ID.
   LOGSEQ PARITY: Clears zoom-root and exits journals view when switching pages."
  [_db _session {:keys [page-id]}]
  {:session-updates (page-view-update page-id {:reset-zoom? true})})

(defn- navigate-or-create-page
  "Navigate to page by name, creating it if it doesn't exist.
   
   Returns intent result with :ops (for new page) and :session-updates."
  [db page-name]
  (if-let [page-id (q/find-page-by-name db page-name)]
    ;; Page exists - just navigate
    {:session-updates (page-view-update page-id {:clear-editing? true})}
    ;; Page doesn't exist - create it and navigate
    (let [new-page-id (str "page-" (random-uuid))
          first-block-id (str "block-" (random-uuid))]
      {:ops [{:op :create-node :id new-page-id :type :page :props {:title page-name}}
             {:op :place :id new-page-id :under :doc :at :last}
             {:op :create-node :id first-block-id :type :block :props {:text ""}}
             {:op :place :id first-block-id :under new-page-id :at :last}]
       ;; BUGFIX: nodes must include focus block, otherwise state machine sees :idle
       ;; and blocks :enter-edit intent
       :session-updates (page-view-update new-page-id
                                          {:clear-editing? true
                                           :selection-id first-block-id})})))

(defn- handle-navigate-to-page
  "Navigate to page by name (from page ref click).
   Creates new page if it doesn't exist (Logseq parity)."
  [db _session {:keys [page-name]}]
  (navigate-or-create-page db page-name))

(defn- handle-create-page
  "Create a new page with the given title."
  [db _session {:keys [title]}]
  (navigate-or-create-page db title))

(defn- collect-descendants
  "Recursively collect all descendant IDs of a node."
  [db node-id]
  (let [children (q/children db node-id)]
    (into children
          (mapcat #(collect-descendants db %) children))))

(defn- handle-delete-page
  "Delete a page and all its contents by moving to trash.
   If deleting the current page, switches to another page.
   Adds :trashed-at timestamp for 30-day cleanup."
  [db session {:keys [page-id]}]
  (when page-id
    (let [current-page-id (q/current-page session)
          pages (q/all-pages db)
          ;; Get all child blocks to trash
          descendants (collect-descendants db page-id)
          ;; Figure out which page to switch to if deleting current
          other-pages (remove #{page-id} pages)
          next-page (first other-pages)
          deleting-current? (= page-id current-page-id)
          ;; Add timestamp for 30-day cleanup
          now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))]
      {:ops (into
             ;; Move all descendants to trash first
             (mapv (fn [id] {:op :place :id id :under const/root-trash :at :last}) descendants)
             ;; Move page to trash and add timestamp
             [{:op :place :id page-id :under const/root-trash :at :last}
              {:op :update-node :id page-id :props {:trashed-at now}}])
       ;; If we deleted the current page, switch to another
       :session-updates (when deleting-current?
                          (page-view-update next-page {}))})))

;; ── Registration ──────────────────────────────────────────────────────────────

;; Auto-register intents on namespace load
(intent/register-intent! :switch-page
                         {:doc "Switch to a specific page by ID"
                          :fr/ids #{:fr.pages/switch-page}
                          :handler handle-switch-page})

(intent/register-intent! :navigate-to-page
                         {:doc "Navigate to page by name (from page ref)"
                          :fr/ids #{:fr.pages/switch-page}
                          :handler handle-navigate-to-page})

(intent/register-intent! :create-page
                         {:doc "Create a new page with given title"
                          :fr/ids #{:fr.pages/switch-page}
                          :handler handle-create-page})

(intent/register-intent! :delete-page
                         {:doc "Delete a page and all its contents"
                          :fr/ids #{:fr.struct/delete-block}
                          :handler handle-delete-page})

(defn- escape-regex
  "Escape special regex characters for literal matching."
  [s]
  (str/replace s #"([.*+?^${}()|\[\]\\])" "\\$1"))

(defn- find-blocks-with-page-ref
  "Find all blocks containing [[page-name]] references (case-insensitive).
   Returns seq of [block-id block-text] pairs."
  [db page-name]
  (let [pattern (re-pattern (str "(?i)\\[\\[" (escape-regex page-name) "\\]\\]"))]
    (->> (:nodes db)
         (filter (fn [[_id node]] (= (:type node) :block)))
         (keep (fn [[block-id node]]
                 (let [text (get-in node [:props :text] "")]
                   (when (re-find pattern text)
                     [block-id text])))))))

(defn- update-page-refs-in-text
  "Replace [[old-name]] with [[new-name]] in text (case-insensitive match, preserves case of replacement)."
  [text old-name new-name]
  (let [pattern (re-pattern (str "(?i)\\[\\[" (escape-regex old-name) "\\]\\]"))]
    (str/replace text pattern (str "[[" new-name "]]"))))

(defn- handle-rename-page
  "Rename a page by updating its title and all [[OldName]] references.
   Validates: non-empty, no collision with existing page.
   LOGSEQ PARITY: Updates all page references across the graph.
   Returns :delete-old-file in session-updates so executor can clean up old .md file."
  [db _session {:keys [page-id new-title]}]
  (when (and page-id (not (str/blank? new-title)))
    (let [trimmed-title (str/trim new-title)
          old-title (q/page-title db page-id)
          existing (q/find-page-by-name db trimmed-title)]
      ;; Only rename if title is different and doesn't collide
      (when (and (not (str/blank? trimmed-title))
                 (not= old-title trimmed-title)
                 (or (nil? existing) (= existing page-id)))
        (let [;; Find all blocks with [[old-title]] references
              blocks-to-update (find-blocks-with-page-ref db old-title)
              ;; Generate update ops for each block
              ref-update-ops (map (fn [[block-id text]]
                                    {:op :update-node
                                     :id block-id
                                     :props {:text (update-page-refs-in-text text old-title trimmed-title)}})
                                  blocks-to-update)]
          {:ops (into [{:op :update-node :id page-id :props {:title trimmed-title}}]
                      ref-update-ops)
           ;; Signal executor to delete old file
           :session-updates {:storage {:delete-old-file old-title}}})))))

(intent/register-intent! :rename-page
                         {:doc "Rename a page by updating its title"
                          :fr/ids #{:fr.pages/switch-page}
                          :handler handle-rename-page})

(defn- handle-restore-page
  "Restore a page and its descendants from trash.

   Since delete flattens the tree (all descendants become direct trash children),
   restore rebuilds a flat structure under the page. Deep nesting is lost.

   Args:
     page-id     - ID of page to restore
     descendants - Optional list of descendant IDs to restore under the page"
  [db _session {:keys [page-id descendants switch-to?]}]
  (when page-id
    (let [;; Verify page is actually in trash
          parent (get-in db [:derived :parent-of page-id])]
      (when (= parent const/root-trash)
        {:ops (into
               ;; Move page back to doc
               [{:op :place :id page-id :under const/root-doc :at :last}]
               ;; Move descendants back under page (flat - nested structure lost)
               (when (seq descendants)
                 (mapv (fn [id] {:op :place :id id :under page-id :at :last})
                       descendants)))
         ;; Optionally switch to restored page
         :session-updates (when switch-to?
                            {:ui {:current-page page-id}})}))))

(intent/register-intent! :restore-page
                         {:doc "Restore a page from trash (undo delete)"
                          :fr/ids #{:fr.struct/delete-block}
                          :handler handle-restore-page})

(defn- today-journal-title
  "Get today's journal title in human format (Dec 14th, 2025)."
  []
  #?(:cljs (journal/today-title)
     :clj
     ;; CLJ fallback for tests (uses java.util.Date)
     (let [months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
           now (java.util.Date.)
           day (.getDate now)
           month (.getMonth now)
           year (+ 1900 (.getYear now))
           suffix (cond
                    (#{11 12 13} (mod day 100)) "th"
                    (= 1 (mod day 10)) "st"
                    (= 2 (mod day 10)) "nd"
                    (= 3 (mod day 10)) "rd"
                    :else "th")]
       (str (nth months month) " " day suffix ", " year))))

(defn- handle-auto-trash-empty-page
  "Auto-trash a page if it's empty and has no backlinks.
   Excludes today's journal page.
   Called when navigating away from a page."
  [db session {:keys [page-id]}]
  (when page-id
    (let [page-title (q/page-title db page-id)
          today-title (today-journal-title)
          is-today-journal? (= page-title today-title)
          is-empty? (q/page-empty? db page-id)
          backlinks (get-in db [:derived :backlinks-by-page
                                (some-> page-title str/lower-case)] [])
          has-backlinks? (seq backlinks)]
      ;; Only auto-trash if: empty, no backlinks, and not today's journal
      (when (and is-empty? (not has-backlinks?) (not is-today-journal?))
        (handle-delete-page db session {:page-id page-id})))))

(intent/register-intent! :auto-trash-empty-page
                         {:doc "Auto-trash an empty page with no backlinks (except today's journal)"
                          :fr/ids #{:fr.struct/delete-block}
                          :handler handle-auto-trash-empty-page})

(defn- handle-permanently-delete-page
  "Permanently delete a page from trash (no recovery).
   Uses tombstone pattern - marks nodes as {:tombstone? true} so UI filters them out."
  [db _session {:keys [page-id]}]
  (when page-id
    (let [parent (get-in db [:derived :parent-of page-id])]
      ;; Only delete if actually in trash
      (when (= parent const/root-trash)
        (let [descendants (collect-descendants db page-id)]
          {:ops (into
                 ;; Mark all descendants as tombstones
                 (mapv (fn [id] {:op :update-node :id id :props {:tombstone? true}}) descendants)
                 ;; Mark the page itself as tombstone
                 [{:op :update-node :id page-id :props {:tombstone? true}}])})))))

(intent/register-intent! :permanently-delete-page
                         {:doc "Permanently delete a page from trash"
                          :fr/ids #{:fr.struct/delete-block}
                          :handler handle-permanently-delete-page})

(defn- handle-cleanup-old-trash
  "Mark pages that have been in trash for more than 30 days as tombstones."
  [db _session _intent]
  (let [trash-pages (q/trashed-pages db)
        now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
        thirty-days-ms (* 30 24 60 60 1000)
        old-pages (->> trash-pages
                       (filter (fn [pid]
                                 (let [trashed-at (q/trashed-at db pid)]
                                   (and trashed-at
                                        (> (- now trashed-at) thirty-days-ms))))))]
    (when (seq old-pages)
      {:ops (into []
                  (mapcat (fn [page-id]
                            (let [descendants (collect-descendants db page-id)]
                              (into
                               (mapv (fn [id] {:op :update-node :id id :props {:tombstone? true}}) descendants)
                               [{:op :update-node :id page-id :props {:tombstone? true}}])))
                          old-pages))})))

(defn- invalid-page?
  "Check if page has invalid title (nil, blank, or 'Untitled')."
  [page-title]
  (or (nil? page-title)
      (str/blank? page-title)
      (= page-title "Untitled")))

(defn- trashable-page?
  "Check if page should be auto-trashed (empty, no backlinks, not today, not already trashed)."
  [db page-id page-title today-title]
  (let [is-today? (= page-title today-title)
        is-empty? (q/page-empty? db page-id)
        backlinks (get-in db [:derived :backlinks-by-page
                              (some-> page-title str/lower-case)] [])
        has-backlinks? (seq backlinks)
        already-trashed? (= (q/parent-of db page-id) const/root-trash)]
    (and is-empty? (not has-backlinks?) (not is-today?) (not already-trashed?))))

(defn- categorize-pages
  "Categorize pages into :to-delete (invalid) and :to-trash (empty, valid) sets."
  [db page-ids today-title]
  (reduce
   (fn [acc page-id]
     (let [page-title (q/page-title db page-id)]
       (cond
         (invalid-page? page-title)
         (update acc :to-delete conj page-id)

         (trashable-page? db page-id page-title today-title)
         (update acc :to-trash conj page-id)

         :else acc)))
   {:to-delete [] :to-trash []}
   page-ids))

(defn- tombstone-ops-for-page
  "Generate tombstone operations for a page and all descendants."
  [db page-id]
  (let [descendants (collect-descendants db page-id)
        all-nodes (conj descendants page-id)]
    (mapv (fn [id] {:op :update-node :id id :props {:tombstone? true}})
          all-nodes)))

(defn- trash-ops-for-page
  "Generate trash operations for a page and all descendants."
  [db page-id now]
  (let [descendants (collect-descendants db page-id)
        place-ops (mapv (fn [id] {:op :place :id id :under const/root-trash :at :last})
                        (conj descendants page-id))
        timestamp-op {:op :update-node :id page-id :props {:trashed-at now}}]
    (conj place-ops timestamp-op)))

(defn- handle-scan-empty-pages
  "Scan all pages and clean up:
   - Permanently delete invalid pages (Untitled, blank titles)
   - Auto-trash valid empty pages (except today's journal)
   Run on startup to clean up orphaned pages."
  [db _session _intent]
  (let [all-page-ids (concat (q/all-pages db) (q/trashed-pages db))
        today-title (today-journal-title)
        now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
        {:keys [to-delete to-trash]} (categorize-pages db all-page-ids today-title)]

    (when (or (seq to-delete) (seq to-trash))
      {:ops (into []
                  (concat
                   (mapcat #(tombstone-ops-for-page db %) to-delete)
                   (mapcat #(trash-ops-for-page db % now) to-trash)))})))

(intent/register-intent! :scan-empty-pages
                         {:doc "Scan pages and clean up invalid/empty ones"
                          :fr/ids #{:fr.struct/delete-block}
                          :handler handle-scan-empty-pages})

(intent/register-intent! :cleanup-old-trash
                         {:doc "Delete pages that have been in trash for more than 30 days"
                          :fr/ids #{:fr.struct/delete-block}
                          :handler handle-cleanup-old-trash})

(intent/register-intent! :follow-link-under-cursor
                         {:doc "Follow link/reference under cursor (Cmd+O in Logseq).

         Detects context at cursor and navigates accordingly:
         - Page ref [[Page]] → switch to that page
         - No ref → no-op

         Mirrors Logseq's Cmd+O behavior."
                          :fr/ids #{:fr.pages/follow-link}
                          :spec [:map
                                 [:type [:= :follow-link-under-cursor]]
                                 [:block-id :string]
                                 [:cursor-pos :int]]
                          :handler
                          (fn [db _session {:keys [block-id cursor-pos pending-buffer]}]
                            (let [text (if (= (:block-id pending-buffer) block-id)
                                         (:text pending-buffer)
                                         (get-in db [:nodes block-id :props :text] ""))
                                  context (ctx/context-at-cursor text cursor-pos)]
                              (case (:type context)
                                :page-ref (navigate-or-create-page db (:page-name context))
                                nil)))})

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(intent/register-intent! :go-to-journal
                         {:doc "Navigate to daily journal page, creating if needed.

         This is the core daily journal functionality:
         - If journal page exists → navigate to it
         - If not → create it with empty block and navigate

         The journal-title parameter should be in Logseq format:
         'MMM do, yyyy' (e.g., 'Dec 11th, 2025')

         See docs/DAILY_JOURNAL_SPEC.md for format details."
                          :fr/ids #{:fr.pages/switch-page}
                          :spec [:map
                                 [:type [:= :go-to-journal]]
                                 [:journal-title :string]]
                          :handler
                          (fn [db _session {:keys [journal-title]}]
                            (navigate-or-create-page db journal-title))})

(intent/register-intent! :open-journals-view
                         {:doc "Open the journals view (all journals stacked).
         Pushes :journals to navigation history for proper back/forward support."
                          :fr/ids #{:fr.pages/switch-page}
                          :spec [:map [:type [:= :open-journals-view]]]
                          :handler
                          (fn [_db _session _intent]
                            {:session-updates {:ui {:journals-view? true}}})})

(intent/register-intent! :open-all-pages-view
                         {:doc "Open the all-pages view (page listing like Logseq).
         Clears current page selection to show the page list."
                          :fr/ids #{:fr.pages/switch-page}
                          :spec [:map [:type [:= :open-all-pages-view]]]
                          :handler
                          (fn [_db _session _intent]
                            {:session-updates {:ui {:journals-view? false
                                                    :current-page nil}}})})

;; ── Navigation History (browser-style back/forward) ─────────────────────────

(intent/register-intent! :navigate-back
                         {:doc "Navigate back in page history (Cmd+[).
         Returns to the previous page in navigation history without
         affecting undo/redo history. Like browser back button.

         Handles :journals as a virtual page for journals view."
                          :fr/ids #{:fr.pages/switch-page}
                          :spec [:map [:type [:= :navigate-back]]]
                          :handler
                          (fn [_db session _intent]
                            (let [history (get-in session [:ui :history] [])
                                  history-idx (get-in session [:ui :history-index] -1)]
                              (when (> history-idx 0)
                                (let [new-idx (dec history-idx)
                                      new-page (nth history new-idx)]
                                  ;; Handle :journals as virtual page
                                  (if (= new-page :journals)
                                    {:session-updates
                                     {:ui {:journals-view? true
                                           :history-index new-idx
                                           :zoom-root nil}}}
                                    {:session-updates
                                     {:ui {:current-page new-page
                                           :journals-view? false
                                           :history-index new-idx
                                           :zoom-root nil}}})))))})

(intent/register-intent! :navigate-forward
                         {:doc "Navigate forward in page history (Cmd+]).
         Goes to the next page in navigation history (after going back).
         Like browser forward button.

         Handles :journals as a virtual page for journals view."
                          :fr/ids #{:fr.pages/switch-page}
                          :spec [:map [:type [:= :navigate-forward]]]
                          :handler
                          (fn [_db session _intent]
                            (let [history (get-in session [:ui :history] [])
                                  history-idx (get-in session [:ui :history-index] -1)
                                  max-idx (dec (count history))]
                              (when (and (>= history-idx 0) (< history-idx max-idx))
                                (let [new-idx (inc history-idx)
                                      new-page (nth history new-idx)]
                                  ;; Handle :journals as virtual page
                                  (if (= new-page :journals)
                                    {:session-updates
                                     {:ui {:journals-view? true
                                           :history-index new-idx
                                           :zoom-root nil}}}
                                    {:session-updates
                                     {:ui {:current-page new-page
                                           :journals-view? false
                                           :history-index new-idx
                                           :zoom-root nil}}})))))})

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
