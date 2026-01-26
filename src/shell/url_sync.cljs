(ns shell.url-sync
  "Synchronize current page with browser URL query params.

   Enables deep linking via ?page=PageTitle URLs.

   Features:
   - Sync page navigation → URL (pushState)
   - Read page from URL on load
   - Handle browser back/forward (popstate)"
  (:require [kernel.query :as q]))

;; ── URL Helpers ─────────────────────────────────────────────────────────────

(defn- get-url-page-param
  "Get the 'page' query parameter from current URL, or nil."
  []
  (let [params (js/URLSearchParams. (.-search js/location))]
    (.get params "page")))

(defn- build-url-with-page
  "Build URL with page query param. Returns URL string."
  [page-title]
  (let [base (.-origin js/location)
        path (.-pathname js/location)
        params (js/URLSearchParams. (.-search js/location))]
    (if page-title
      (do
        (.set params "page" page-title)
        (str base path "?" (.toString params)))
      ;; No page - remove param but keep other params
      (do
        (.delete params "page")
        (let [param-str (.toString params)]
          (if (seq param-str)
            (str base path "?" param-str)
            (str base path)))))))

;; ── Public API ──────────────────────────────────────────────────────────────

(defn get-page-from-url
  "Read page title from URL query param (?page=...).
   Returns nil if not set."
  []
  (when-let [encoded (get-url-page-param)]
    (js/decodeURIComponent encoded)))

(defn sync-url-to-page!
  "Update browser URL to reflect current page (via pushState).

   Args:
     db - Current database (to look up page title)
     page-id - Current page ID (can be nil for journals view)
     replace? - If true, use replaceState instead of pushState

   Skips update if URL already matches to avoid duplicate history entries."
  ([db page-id] (sync-url-to-page! db page-id false))
  ([db page-id replace?]
   (let [current-url-page (get-page-from-url)
         new-title (when page-id (q/page-title db page-id))
         ;; Only update if different (avoid duplicate history)
         changed? (not= current-url-page new-title)]
     (when changed?
       (let [new-url (build-url-with-page new-title)]
         (if replace?
           (.replaceState js/history nil "" new-url)
           (.pushState js/history nil "" new-url)))))))

(defn init!
  "Initialize URL sync with popstate handler.

   Args:
     on-navigate - Function called with page-name when browser back/forward
                   triggers navigation. Signature: (fn [page-name] ...)

   Returns a cleanup function to remove the event listener."
  [on-navigate]
  (let [handler (fn [_event]
                  (let [page-name (get-page-from-url)]
                    ;; Navigate to the page from URL, or nil for journals
                    (on-navigate page-name)))]
    (.addEventListener js/window "popstate" handler)
    ;; Return cleanup function
    (fn []
      (.removeEventListener js/window "popstate" handler))))
