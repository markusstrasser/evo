(ns plugins.pages
  "Page management plugin for multi-page support.

   Provides:
   - Intent handlers for switching pages
   - Query helpers for current page
   - Page creation and navigation"
  (:require [kernel.intent :as intent]
            [kernel.query :as q]
            [kernel.constants :as const]
            [plugins.context :as ctx]))

;; ── Query Helpers ─────────────────────────────────────────────────────────────

(defn current-page
  "Get ID of the currently active page from session/ui state."
  [db]
  (get-in db [:nodes const/session-ui-id :props :current-page]))

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
                              clojure.string/trim
                              clojure.string/lower-case)
          pages (all-pages db)]
      (->> pages
           (filter (fn [page-id]
                     (let [title (page-title db page-id)]
                       (= normalized-name
                          (-> title
                              clojure.string/trim
                              clojure.string/lower-case)))))
           first))))

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defn- handle-switch-page
  "Switch to a specific page by ID."
  [db {:keys [page-id]}]
  [{:op :update-node
    :id const/session-ui-id
    :props {:current-page page-id}}])

(defn- handle-navigate-to-page
  "Navigate to page by name (from page ref click).

   Finds page with matching title (case-insensitive).
   Logs warning if page not found."
  [db {:keys [page-name]}]
  (if-let [page-id (find-page-by-name db page-name)]
    [{:op :update-node
      :id const/session-ui-id
      :props {:current-page page-id}}]
    (do
      #?(:cljs (js/console.warn (str "Page not found: " page-name))
         :clj  (println (str "Page not found: " page-name)))
      [])))

;; ── Registration ──────────────────────────────────────────────────────────────

;; Auto-register intents on namespace load
(intent/register-intent! :switch-page
  {:doc "Switch to a specific page by ID"
   :handler handle-switch-page})

(intent/register-intent! :navigate-to-page
  {:doc "Navigate to page by name (from page ref)"
   :handler handle-navigate-to-page})

(intent/register-intent! :follow-link-under-cursor
  {:doc "Follow link/reference under cursor (Cmd+O in Logseq).

         Detects context at cursor and navigates accordingly:
         - Page ref [[Page]] → switch to that page
         - Block ref ((id)) → select/focus that block (TODO)
         - No ref → no-op

         Mirrors Logseq's Cmd+O behavior."
   :spec [:map
          [:type [:= :follow-link-under-cursor]]
          [:block-id :string]
          [:cursor-pos :int]]
   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-in db [:nodes block-id :props :text] "")
           context (ctx/context-at-cursor text cursor-pos)]

       (case (:type context)
         ;; Page reference → navigate to page
         :page-ref
         (let [page-name (:page-name context)]
           (if-let [page-id (find-page-by-name db page-name)]
             [{:op :update-node
               :id const/session-ui-id
               :props {:current-page page-id}}]
             (do
               #?(:cljs (js/console.warn (str "Page not found: " page-name))
                  :clj (println (str "Page not found: " page-name)))
               [])))

         ;; Block reference → select/focus block
         :block-ref
         (let [block-uuid (:uuid context)]
           ;; For now, just select the block. Future: could scroll to it
           (if (get-in db [:nodes block-uuid])
             [{:op :update-node
               :id const/session-selection-id
               :props {:nodes #{block-uuid}
                       :focus block-uuid
                       :anchor block-uuid}}]
             (do
               #?(:cljs (js/console.warn (str "Block not found: " block-uuid))
                  :clj (println (str "Block not found: " block-uuid)))
               [])))

         ;; No reference under cursor → no-op
         [])))})
