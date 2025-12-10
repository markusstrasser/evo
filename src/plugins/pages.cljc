(ns plugins.pages
  "Page management plugin for multi-page support.

   Provides:
   - Intent handlers for switching pages
   - Query helpers for current page
   - Page creation and navigation"
  (:require [clojure.string :as str]
            [kernel.intent :as intent]
            [kernel.query :as q]
            [plugins.context :as ctx]))

;; Sentinel for DCE prevention - referenced by spec.runner
(def loaded? true)

;; ── Query Helpers ─────────────────────────────────────────────────────────────

(defn current-page
  "Get ID of the currently active page from session/ui state."
  [session]
  (get-in session [:ui :current-page]))

(defn all-pages
  "Get list of all page IDs (direct children of :doc root)."
  [db]
  (q/children db :doc))

(defn page-title
  "Get title of a page by ID."
  [db page-id]
  (get-in db [:nodes page-id :props :title] "Untitled"))

(defn find-page-by-name
  "Find page ID by title (case-insensitive).

   Returns nil if page not found."
  [db page-name]
  (when page-name
    (let [normalized-name (-> page-name
                              str/trim
                              str/lower-case)
          pages (all-pages db)]
      (->> pages
           (filter (fn [page-id]
                     (let [title (page-title db page-id)]
                       (= normalized-name
                          (-> title
                              str/trim
                              str/lower-case)))))
           first))))

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
  "Find all blocks that reference a given page.
   
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
                                       :page-title (page-title db source-page-id)})))))))]
      ;; Filter out blocks from the current page (don't show self-references)
      (remove (fn [backlink]
                (= (str/lower-case (or (:page-title backlink) ""))
                   target-lower))
              blocks))))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defn- handle-switch-page
  "Switch to a specific page by ID.
   LOGSEQ PARITY: Clears zoom-root when switching pages."
  [_db _session {:keys [page-id]}]
  {:session-updates {:ui {:current-page page-id
                          :zoom-root nil}}})

(defn- navigate-or-create-page
  "Navigate to page by name, creating it if it doesn't exist.
   
   Returns intent result with :ops (for new page) and :session-updates."
  [db page-name]
  (if-let [page-id (find-page-by-name db page-name)]
    ;; Page exists - just navigate
    {:session-updates {:ui {:current-page page-id}}}
    ;; Page doesn't exist - create it and navigate
    (let [new-page-id (str "page-" (random-uuid))
          first-block-id (str "block-" (random-uuid))]
      {:ops [{:op :create-node :id new-page-id :type :page :props {:title page-name}}
             {:op :place :id new-page-id :under :doc :at :last}
             {:op :create-node :id first-block-id :type :block :props {:text ""}}
             {:op :place :id first-block-id :under new-page-id :at :last}]
       :session-updates {:ui {:current-page new-page-id}
                         :selection {:nodes #{} :focus first-block-id :anchor nil}}})))

(defn- handle-navigate-to-page
  "Navigate to page by name (from page ref click).
   Creates new page if it doesn't exist (Logseq parity)."
  [db _session {:keys [page-name]}]
  (navigate-or-create-page db page-name))

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
