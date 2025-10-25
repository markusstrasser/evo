(ns keymap.bindings
  "Load and register keyboard bindings from pure data.

   Bindings are defined in bindings-data namespace for hot-reload and easy editing."
  (:require [keymap.core :as keymap]
            [keymap.bindings-data :as BD]))

;; ── Reload Function ───────────────────────────────────────────────────────────

(defn reload!
  "Reload all bindings from bindings-data. Call after editing bindings."
  []
  (keymap/reset-all!)
  (doseq [[ctx bs] BD/data]
    (keymap/register! ctx bs))
  :ok)

(comment
  ;; IMPORTANT: Call (reload!) explicitly during app initialization.
  ;; Do not rely on namespace load side-effects.


  ;; Hot reload: edit bindings_data.cljc and reload
  (require '[keymap.bindings] :reload)

  ;; Or call reload! directly
  (reload!))
