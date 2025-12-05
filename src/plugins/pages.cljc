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

;; ── Intent Handlers ───────────────────────────────────────────────────────────

(defn- handle-switch-page
  "Switch to a specific page by ID."
  [_db _session {:keys [page-id]}]
  {:session-updates {:ui {:current-page page-id}}})

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
                          :handler handle-switch-page})

(intent/register-intent! :navigate-to-page
                         {:doc "Navigate to page by name (from page ref)"
                          :handler handle-navigate-to-page})

(intent/register-intent! :follow-link-under-cursor
                         {:doc "Follow link/reference under cursor (Cmd+O in Logseq).

         Detects context at cursor and navigates accordingly:
         - Page ref [[Page]] → switch to that page
         - No ref → no-op

         Mirrors Logseq's Cmd+O behavior."
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
