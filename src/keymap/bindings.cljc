(ns keymap.bindings
  "Load and register keyboard bindings from EDN data.

   Bindings are defined in bindings.edn for hot-reload and easy editing."
  #?(:cljs (:require-macros [keymap.bindings :refer [bindings-edn]]))
  (:require [keymap.core :as keymap]))

;; ── Load Bindings from EDN ────────────────────────────────────────────────────

#?(:clj
   (defmacro bindings-edn []
     (read-string (slurp "src/keymap/bindings.edn"))))

(def ^:private bindings-data
  "Keymap bindings loaded from EDN file at compile time."
  (bindings-edn))

;; ── Register Bindings on Namespace Load ──────────────────────────────────────

(doseq [[context bindings] bindings-data]
  (keymap/register! context bindings))

(comment
  ;; Usage example from UI layer:
  (let [event (keymap/parse-dom-event dom-event)
        db @!db
        intent-type (keymap/resolve-intent-type event db)]
    (when intent-type
      (handle-intent {:type intent-type})))

  ;; Hot reload example (edit bindings.edn and reload this namespace):
  (require '[keymap.bindings] :reload)

  ;; Or programmatically:
  (keymap/clear-bindings! :non-editing)
  (keymap/register! :non-editing
    [[{:key "j"} :select-next-sibling]
     [{:key "k"} :select-prev-sibling]]))
