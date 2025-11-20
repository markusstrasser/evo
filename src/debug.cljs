(ns debug
  "Development preload namespace - auto-loads debugging tools.

   Loaded via shadow-cljs :preloads configuration in blocks-ui build.
   Only active in development mode - not included in release builds.")

(defn load-browser-guard!
  "Load browser guard if DEBUG_GUARD environment variable is truthy.
   Browser guard validates focus/cursor after UI interactions."
  []
  (when js/goog.DEBUG  ; Only in development
    (let [debug-guard (.. js/window -DEBUG_GUARD)]
      (when (or debug-guard
                (some-> js/process .-env .-DEBUG_GUARD))
        (println "🛡️ Loading browser guard...")
        ;; Load guard script
        (let [script (.createElement js/document "script")]
          (set! (.-src script) "/dev/browser_guard.js")
          (set! (.-async script) true)
          (.appendChild (.-head js/document) script))))))

;; Auto-load on namespace init
(load-browser-guard!)

(println "🔧 Debug preload initialized (devtools integration active)")
