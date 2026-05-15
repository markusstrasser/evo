(ns keymap.ownership-data
  "Single source of truth for keyboard ownership data.

   Both `keymap.ownership-test` and the static `bb lint:registry-state`
   verifier consume this namespace so the two cannot drift apart.

   See `docs/KEYBOARD_OWNERSHIP.md` for the contract.")

(def block-owned-editing-key-specs
  "Keys whose handling in editing mode MUST stay with the contenteditable
   element / `components.block` — NOT in the shell keymap.

   Plain and Shift Arrow keys are owned by the contenteditable: cursor
   movement and text-selection are browser-default behavior, and the
   block component bridges to adjacent blocks at boundaries (see
   CLAUDE.md `Keyboard & Selection`).

   Enter, Escape, Backspace, Delete need component-level cursor context
   (split, merge, exit-edit) that the keymap cannot supply."
  #{{:key "Enter"}
    {:key "Enter" :shift true}
    {:key "Escape"}
    {:key "Backspace"}
    {:key "Delete"}
    {:key "ArrowUp"}
    {:key "ArrowDown"}
    {:key "ArrowLeft"}
    {:key "ArrowRight"}
    {:key "ArrowUp" :shift true}
    {:key "ArrowDown" :shift true}
    {:key "ArrowLeft" :shift true}
    {:key "ArrowRight" :shift true}})
