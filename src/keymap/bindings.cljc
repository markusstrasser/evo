(ns keymap.bindings
  "Register all keyboard bindings for the application.

   Called on namespace load to populate the central keymap registry."
  (:require [keymap.core :as keymap]))

;; ── Navigation Bindings (Non-Editing Mode) ───────────────────────────────────

(keymap/register! :non-editing
  [[{:key "ArrowDown"} :select-next-sibling]
   [{:key "ArrowUp"} :select-prev-sibling]
   [{:key "ArrowDown" :alt true} :select-next-sibling]  ; Alt as alias
   [{:key "ArrowUp" :alt true} :select-prev-sibling]])  ; Alt as alias

;; ── Selection Bindings (Non-Editing Mode) ────────────────────────────────────

(keymap/register! :non-editing
  [[{:key "ArrowDown" :shift true} :extend-to-next-sibling]
   [{:key "ArrowUp" :shift true} :extend-to-prev-sibling]])

;; ── Structural Edit Bindings (Non-Editing Mode) ──────────────────────────────

(keymap/register! :non-editing
  [[{:key "Tab"} :indent-selected]
   [{:key "Tab" :shift true} :outdent-selected]
   [{:key "Backspace"} :delete-selected]])

;; ── Move Bindings (Works in Both Modes) ──────────────────────────────────────

(keymap/register! :global
  [[{:key "ArrowUp" :shift true :mod true} :move-selected-up]
   [{:key "ArrowDown" :shift true :mod true} :move-selected-down]
   [{:key "ArrowUp" :shift true :alt true} :move-selected-up]    ; Alt alias
   [{:key "ArrowDown" :shift true :alt true} :move-selected-down]]) ; Alt alias

;; ── Block Creation (Non-Editing Mode) ────────────────────────────────────────

;; Note: Enter in non-editing mode creates new block and enters edit mode
;; This requires special handling in the UI layer (can't be pure intent)
;; Registered here for documentation, but dispatch handled specially

(keymap/register! :non-editing
  [[{:key "Enter"} :create-new-block-after-focus]])

;; ── Edit Mode Bindings ────────────────────────────────────────────────────────

;; Content editing handled by contenteditable
;; These bindings override contenteditable defaults

(keymap/register! :editing
  [[{:key "Escape"} :exit-edit]
   [{:key "Backspace" :mod true} :merge-with-prev]])

(comment
  ;; Usage example from UI layer:
  (let [event (keymap/parse-dom-event dom-event)
        db @!db
        intent-type (keymap/resolve-intent-type event db)]
    (when intent-type
      (handle-intent {:type intent-type})))

  ;; Hot reload example:
  (keymap/clear-bindings! :non-editing)
  (keymap/register! :non-editing
    [[{:key "j"} :select-next-sibling]
     [{:key "k"} :select-prev-sibling]]))
