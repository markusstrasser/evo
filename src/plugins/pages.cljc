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
            [utils.text-context :as ctx]))

;; Sentinel for DCE prevention - referenced by spec.runner

;; ── Internal Helpers ──────────────────────────────────────────────────────────

(defn- extract-page-refs
  "Extract all [[page]] references from text.
   Returns a set of page names (as found in text, preserving case)."
  [text]
  (when text
    (let [pattern #"\[\[([^\]]+)\]\]"]
      (->> (re-seq pattern text)
           (map second)
           set))))

(defn find-backlinks
  "DEPRECATED: Use plugins.backlinks-index/get-backlinks for O(1) lookup.
   
   Find all blocks that reference a given page (O(n) scan).
   
   Returns a list of maps:
   {:block-id \"...\"
    :block-text \"...\"
    :page-id \"...\"
    :page-title \"...\"}
   
   Searches all blocks in the DB for [[target-page]] references.
   Excludes:
   - Blocks in trash (no valid page ancestor)
   - Self-references (blocks on the target page itself)"
  [db target-page]
  (when target-page
    (let [target-lower (str/lower-case target-page)
          all-nodes (:nodes db)
          parent-of (:parent-of (:derived db))

          ;; Find the page that contains a block by walking up the parent chain
          ;; Returns nil if block is in trash or orphaned (no page ancestor)
          find-source-page (fn [block-id]
                             (loop [id block-id]
                               (let [parent (get parent-of id)]
                                 (cond
                                   ;; Reached doc root or trash - return nil
                                   (or (nil? parent)
                                       (= parent :doc)
                                       (= parent :trash))
                                   nil

                                   ;; Found a page - return it
                                   (= :page (get-in all-nodes [parent :type]))
                                   parent

                                   ;; Keep traversing up
                                   :else
                                   (recur parent)))))

          blocks (->> all-nodes
                      (filter (fn [[_id node]] (= (:type node) :block)))
                      (keep (fn [[block-id node]]
                              (let [text (get-in node [:props :text] "")
                                    refs (extract-page-refs text)
                                    refs-lower (set (map str/lower-case refs))]
                                (when (contains? refs-lower target-lower)
                                  (let [source-page-id (find-source-page block-id)]
                                    ;; Only include blocks that have a valid page ancestor
                                    ;; (excludes trashed/orphaned blocks)
                                    (when source-page-id
                                      {:block-id block-id
                                       :block-text text
                                       :page-id source-page-id
                                       :page-title (q/page-title db source-page-id)})))))))]
      ;; Filter out blocks from the current page (don't show self-references)
      (remove (fn [backlink]
                (= (str/lower-case (or (:page-title backlink) ""))
                   target-lower))
              blocks))))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defn- handle-switch-page
  "Switch to a specific page by ID.
   LOGSEQ PARITY: Clears zoom-root and exits journals view when switching pages."
  [_db _session {:keys [page-id]}]
  {:session-updates {:ui {:current-page page-id
                          :zoom-root nil
                          :journals-view? false}}})

(defn- navigate-or-create-page
  "Navigate to page by name, creating it if it doesn't exist.
   
   Returns intent result with :ops (for new page) and :session-updates."
  [db page-name]
  (if-let [page-id (q/find-page-by-name db page-name)]
    ;; Page exists - just navigate
    {:session-updates {:ui {:current-page page-id}}}
    ;; Page doesn't exist - create it and navigate
    (let [new-page-id (str "page-" (random-uuid))
          first-block-id (str "block-" (random-uuid))]
      {:ops [{:op :create-node :id new-page-id :type :page :props {:title page-name}}
             {:op :place :id new-page-id :under :doc :at :last}
             {:op :create-node :id first-block-id :type :block :props {:text ""}}
             {:op :place :id first-block-id :under new-page-id :at :last}]
       ;; BUGFIX: nodes must include focus block, otherwise state machine sees :idle
       ;; and blocks :enter-edit intent
       :session-updates {:ui {:current-page new-page-id}
                         :selection {:nodes #{first-block-id}
                                     :focus first-block-id
                                     :anchor first-block-id}}})))

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
                          {:ui {:current-page next-page}})})))

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
  (let [months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
        now #?(:clj (java.util.Date.) :cljs (js/Date.))
        day #?(:clj (.getDate now) :cljs (.getDate now))
        month #?(:clj (.getMonth now) :cljs (.getMonth now))
        year #?(:clj (+ 1900 (.getYear now)) :cljs (.getFullYear now))
        suffix (cond
                 (#{11 12 13} (mod day 100)) "th"
                 (= 1 (mod day 10)) "st"
                 (= 2 (mod day 10)) "nd"
                 (= 3 (mod day 10)) "rd"
                 :else "th")]
    (str (nth months month) " " day suffix ", " year)))

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
                          :handler handle-auto-trash-empty-page})

(defn- handle-permanently-delete-page
  "Permanently delete a page from trash (no recovery)."
  [db _session {:keys [page-id]}]
  (when page-id
    (let [parent (get-in db [:derived :parent-of page-id])]
      ;; Only delete if actually in trash
      (when (= parent const/root-trash)
        (let [descendants (collect-descendants db page-id)]
          {:ops (into
                 ;; Delete all descendants
                 (mapv (fn [id] {:op :delete-node :id id}) descendants)
                 ;; Delete the page itself
                 [{:op :delete-node :id page-id}])})))))

(intent/register-intent! :permanently-delete-page
                         {:doc "Permanently delete a page from trash"
                          :handler handle-permanently-delete-page})

(defn- handle-cleanup-old-trash
  "Delete pages that have been in trash for more than 30 days."
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
                               (mapv (fn [id] {:op :delete-node :id id}) descendants)
                               [{:op :delete-node :id page-id}])))
                          old-pages))})))

(defn- handle-scan-empty-pages
  "Scan all pages and clean up:
   - Permanently delete invalid pages (Untitled, blank titles)
   - Auto-trash valid empty pages (except today's journal)
   Run on startup to clean up orphaned pages."
  [db _session _intent]
  (let [all-pages (q/all-pages db)
        trash-pages (q/trashed-pages db)
        all-page-ids (concat all-pages trash-pages)
        today-title (today-journal-title)
        now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))

        ;; Categorize pages
        {:keys [to-delete to-trash]}
        (reduce
         (fn [acc page-id]
           (let [page-title (q/page-title db page-id)
                 is-invalid? (or (nil? page-title)
                                 (str/blank? page-title)
                                 (= page-title "Untitled"))
                 is-today? (= page-title today-title)
                 is-empty? (q/page-empty? db page-id)
                 backlinks (get-in db [:derived :backlinks-by-page
                                       (some-> page-title str/lower-case)] [])
                 has-backlinks? (seq backlinks)
                 already-trashed? (= (q/parent-of db page-id) const/root-trash)]
             (cond
               ;; Invalid pages → permanently delete
               is-invalid?
               (update acc :to-delete conj page-id)

               ;; Valid, empty, no backlinks, not today, not already trashed → trash
               (and is-empty? (not has-backlinks?) (not is-today?) (not already-trashed?))
               (update acc :to-trash conj page-id)

               :else acc)))
         {:to-delete [] :to-trash []}
         all-page-ids)]

    (when (or (seq to-delete) (seq to-trash))
      {:ops (into []
                  (concat
                   ;; Permanently delete invalid pages
                   (mapcat (fn [page-id]
                             (let [descendants (collect-descendants db page-id)]
                               (into
                                (mapv (fn [id] {:op :delete-node :id id}) descendants)
                                [{:op :delete-node :id page-id}])))
                           to-delete)
                   ;; Trash valid empty pages
                   (mapcat (fn [page-id]
                             (let [descendants (collect-descendants db page-id)]
                               (into
                                (mapv (fn [id] {:op :place :id id :under const/root-trash :at :last})
                                      descendants)
                                [{:op :place :id page-id :under const/root-trash :at :last}
                                 {:op :update-node :id page-id :props {:trashed-at now}}])))
                           to-trash)))})))

(intent/register-intent! :scan-empty-pages
                         {:handler handle-scan-empty-pages})

(intent/register-intent! :cleanup-old-trash
                         {:doc "Delete pages that have been in trash for more than 30 days"
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
                          (fn [db _session {:keys [block-id cursor-pos]}]
                            (let [text (get-in db [:nodes block-id :props :text] "")
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
                          :spec [:map
                                 [:type [:= :go-to-journal]]
                                 [:journal-title :string]]
                          :handler
                          (fn [db _session {:keys [journal-title]}]
                            (navigate-or-create-page db journal-title))})

(intent/register-intent! :open-journals-view
                         {:doc "Open the journals view (all journals stacked).
         Pushes :journals to navigation history for proper back/forward support."
                          :spec [:map [:type [:= :open-journals-view]]]
                          :handler
                          (fn [_db _session _intent]
                            {:session-updates {:ui {:journals-view? true}}})})

(intent/register-intent! :open-all-pages-view
                         {:doc "Open the all-pages view (page listing like Logseq).
         Clears current page selection to show the page list."
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
