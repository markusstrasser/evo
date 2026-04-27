(ns keymap.bindings-data)

;; Logseq Keymap Reference: dev/specs/LOGSEQ_KEYMAP_MACOS.md
;; This keymap mirrors Logseq's macOS shortcuts for basic editing/navigation

(def data
  {:non-editing [;; Clipboard
                 [{:key "c" :mod true} :copy-selected]
                 [{:key "x" :mod true} :cut-selected]
                 ;; Navigation
                 [{:key "Escape"} {:type :selection :mode :clear}]
                 [{:key "ArrowDown"} {:type :selection :mode :next}]
                 [{:key "ArrowUp"} {:type :selection :mode :prev}]
                 ;; Block selection (Alt+Up/Down)
                 [{:key "ArrowDown" :alt true} {:type :selection :mode :next}]
                 [{:key "ArrowUp" :alt true} {:type :selection :mode :prev}]
                 ;; Indent/Outdent
                 [{:key "Tab"} :indent-selected]
                 [{:key "Tab" :shift true} :outdent-selected]
                 ;; Delete selected blocks
                 [{:key "Backspace"} :delete-selected]
                 [{:key "Delete"} :delete-selected]
                 ;; Enter/Right on selected block → enter edit mode at END (Logseq parity)
                 [{:key "Enter"} {:type :enter-edit-selected}]
                 [{:key "ArrowRight"} {:type :enter-edit-selected :cursor-at :end}]
                 ;; Left on selected block → enter edit mode at START (Logseq parity)
                 [{:key "ArrowLeft"} {:type :enter-edit-selected :cursor-at :start}]
                 ;; Cmd+A cycle (view mode) → select parent → all visible
                 [{:key "a" :mod true} {:type :select-all-cycle}]
                 ;; Selection extension in view mode only. Editing Shift+Arrow
                 ;; is contenteditable/browser-owned via components.block.
                 [{:key "ArrowUp" :shift true} {:type :selection :mode :extend-prev}]
                 [{:key "ArrowDown" :shift true} {:type :selection :mode :extend-next}]
                 [{:key "a" :shift true :mod true} {:type :selection :mode :all-in-view}]]

   :editing [;; === Core Editing ===
                 ;; NOTE: Escape/Enter/Shift+Enter are NOT bound in keymap
                 ;; Escape is owned by components.block so it can commit the
                 ;; live contenteditable buffer before selecting the block.
                 ;; They MUST be handled by Block component which provides correct cursor position context
                 ;; Enter → context-aware block split/creation (dispatched via on-intent)
                 ;; Shift+Enter → literal newline (dispatched via on-intent)

                 ;; NOTE: Backspace/Delete are NOT bound here - handled by contenteditable + component logic
                 ;; Special cases (merge at position 0) are handled in the block editor component

                 ;; === Text Formatting (Logseq markdown parity) ===
             [{:key "b" :mod true} {:type :format-selection :marker "**"}]  ; Bold
             [{:key "i" :mod true} {:type :format-selection :marker "*"}]   ; Italic (single asterisk)
             [{:key "h" :shift true :mod true} {:type :format-selection :marker "=="}]  ; Highlight
             [{:key "s" :shift true :mod true} {:type :format-selection :marker "~~"}]  ; Strikethrough

                 ;; === Copy block reference (Logseq parity) ===
             [{:key "c" :shift true :mod true} {:type :copy-block-reference}]

                 ;; === Indent/Outdent ===
             [{:key "Tab"} :indent-selected]
             [{:key "Tab" :shift true} :outdent-selected]

                 ;; === Word Navigation (Emacs-style, macOS) ===
             [{:key "f" :mod true :shift true} {:type :move-cursor-forward-word :block-id :editing-block-id}]
             [{:key "b" :mod true :shift true} {:type :move-cursor-backward-word :block-id :editing-block-id}]

                 ;; === Kill Commands (Emacs-style, macOS) ===
                 ;; Ctrl+L → clear entire block
             [{:key "l" :mod true} {:type :clear-block-content :block-id :editing-block-id}]
                 ;; Ctrl+U → kill from cursor to beginning
             [{:key "u" :mod true} {:type :kill-to-beginning :block-id :editing-block-id :cursor-pos :cursor-pos}]
                 ;; Ctrl+W → kill word forward
             [{:key "w" :mod true} {:type :kill-word-forward :block-id :editing-block-id :cursor-pos :cursor-pos}]
                 ;; Note: Ctrl+K (kill to end) is NOT bound on macOS in Logseq
                 ;; Note: Alt+W (kill word backward) is NOT bound on macOS in Logseq

                 ;; === Navigation within Editing (Arrow keys) ===
                 ;; NOTE: ArrowUp/ArrowDown are NOT bound in keymap
                 ;; They MUST be handled by Block component which detects cursor row position
                 ;; and dispatches intent maps with proper context (text content, cursor offset)
                 ;;
                 ;; NOTE: ArrowLeft/Right are also NOT bound here
                 ;; Browser handles cursor movement within the block (default contenteditable behavior)
                 ;; Navigation to adjacent blocks (at cursor edges) is handled at component level

                 ;; === Cmd+A cycle (edit mode) ===
                 ;; NOTE: Cmd+A in editing mode is handled specially in shell.global-keyboard
                 ;; First press → browser select-all (not prevented)
                 ;; Second press (all selected) → dispatches :select-all-cycle with :from-editing? true
             ]
   :global [;; Undo/Redo
            [{:key "z" :mod true} :undo]
            [{:key "z" :shift true :mod true} :redo]
            [{:key "y" :mod true} :redo]
                 ;; Doc-mode toggle (Enter/Shift+Enter swap)
            [{:key "d" :shift true :mod true} {:type :toggle-doc-mode}]
                 ;; Moving blocks
            [{:key "ArrowUp" :shift true :mod true} :move-selected-up]
            [{:key "ArrowDown" :shift true :mod true} :move-selected-down]
            [{:key "ArrowUp" :shift true :alt true} :move-selected-up]
            [{:key "ArrowDown" :shift true :alt true} :move-selected-down]
                 ;; Folding
            [{:key ";" :mod true} {:type :toggle-fold}]
            [{:key "ArrowDown" :mod true} {:type :expand-all}]
            [{:key "ArrowUp" :mod true} {:type :collapse}]
                 ;; Smart editing
            [{:key "Enter" :mod true} {:type :toggle-checkbox}]
                 ;; Follow link (Logseq parity)
            [{:key "o" :mod true} {:type :follow-link-under-cursor :block-id :editing-block-id :cursor-pos :cursor-pos}]
                 ;; UI Chrome toggles
            [{:key "\\" :mod true} {:type :toggle-sidebar}]
            [{:key "p" :mod true} {:type :toggle-hotkeys}]
            [{:key "/" :mod true} {:type :toggle-hotkeys}]
            [{:key "e" :mod true :shift true} {:type :toggle-reading-mode}]
            [{:key "k" :mod true} {:type :toggle-quick-switcher}]
                 ;; Navigation history (browser-style back/forward)
            [{:key "[" :mod true} {:type :navigate-back}]
            [{:key "]" :mod true} {:type :navigate-forward}]]})
