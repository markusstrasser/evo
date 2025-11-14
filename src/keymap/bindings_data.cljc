(ns keymap.bindings-data)

;; Logseq Keymap Reference: dev/specs/LOGSEQ_KEYMAP_MACOS.md
;; This keymap mirrors Logseq's macOS shortcuts for basic editing/navigation

(def data
  {:non-editing [;; Navigation
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
                 ;; Enter on selected block → enter edit mode at END (Logseq parity)
                 [{:key "Enter"} {:type :enter-edit-selected}]]

   :editing [;; === Core Editing ===
                 ;; Escape → exit edit WITHOUT selecting block (Logseq parity)
             [{:key "Escape"} {:type :exit-edit}]

                 ;; Enter while editing → create new block below (Logseq parity)
             [{:key "Enter"} {:type :context-aware-enter :block-id :editing-block-id :cursor-pos :cursor-pos}]

                 ;; Shift+Enter → literal newline (Logseq parity)
             [{:key "Enter" :shift true} {:type :insert-newline :block-id :editing-block-id :cursor-pos :cursor-pos}]

                 ;; NOTE: Backspace/Delete are NOT bound here - handled by contenteditable + component logic
                 ;; Special cases (merge at position 0) are handled in the block editor component

                 ;; === Text Formatting ===
             [{:key "b" :mod true} {:type :format-selection :marker "**"}]
             [{:key "i" :mod true} {:type :format-selection :marker "__"}]
             [{:key "h" :shift true :mod true} {:type :format-selection :marker "^^"}]
             [{:key "s" :shift true :mod true} {:type :format-selection :marker "~~"}]

                 ;; === Indent/Outdent ===
             [{:key "Tab"} :indent-selected]
             [{:key "Tab" :shift true} :outdent-selected]

                 ;; === Word Navigation (Emacs-style, macOS) ===
             [{:key "f" :ctrl true :shift true} {:type :move-cursor-forward-word :block-id :editing-block-id}]
             [{:key "b" :ctrl true :shift true} {:type :move-cursor-backward-word :block-id :editing-block-id}]

                 ;; === Kill Commands (Emacs-style, macOS) ===
                 ;; Ctrl+L → clear entire block
             [{:key "l" :ctrl true} {:type :clear-block-content :block-id :editing-block-id}]
                 ;; Ctrl+U → kill from cursor to beginning
             [{:key "u" :ctrl true} {:type :kill-to-beginning :block-id :editing-block-id}]
                 ;; Ctrl+W → kill word forward
             [{:key "w" :ctrl true} {:type :kill-word-forward :block-id :editing-block-id}]
                 ;; Note: Ctrl+K (kill to end) is NOT bound on macOS in Logseq
                 ;; Note: Alt+W (kill word backward) is NOT bound on macOS in Logseq

                 ;; === Navigation within Editing (Arrow keys) ===
                 ;; Up/Down navigate to blocks above/below when at edge (Logseq parity)
                 ;; These intents check cursor position and navigate if at start/end
             [{:key "ArrowUp"} {:type :navigate-with-cursor-memory
                                :direction :up
                                :block-id :editing-block-id
                                :cursor-pos :cursor-pos}]
             [{:key "ArrowDown"} {:type :navigate-with-cursor-memory
                                  :direction :down
                                  :block-id :editing-block-id
                                  :cursor-pos :cursor-pos}]
                 ;; Ctrl+P/N as Up/Down aliases (Emacs-style)
             [{:key "p" :ctrl true} {:type :navigate-with-cursor-memory
                                     :direction :up
                                     :block-id :editing-block-id
                                     :cursor-pos :cursor-pos}]
             [{:key "n" :ctrl true} {:type :navigate-with-cursor-memory
                                     :direction :down
                                     :block-id :editing-block-id
                                     :cursor-pos :cursor-pos}]
                 ;; NOTE: ArrowLeft/Right are NOT bound here
                 ;; Browser handles cursor movement within the block (default contenteditable behavior)
                 ;; Navigation to adjacent blocks (at cursor edges) is handled at component level
             ]
   :global [;; NOTE: Shift+Arrow removed - handled by Block component (LOGSEQ_EDITING_SELECTION_PARITY.md §4.2)
            ;; The component owns Shift+Arrow for cursor boundary detection while editing.
            ;; This prevents double-dispatch and enables proper text selection → block selection handoff.

            ;; Selection operations
            [{:key "a" :shift true :mod true} {:type :selection :mode :all-in-view}]
            [{:key "a" :mod true} {:type :selection :mode :parent}]
                 ;; Undo/Redo
            [{:key "z" :mod true} :undo]
            [{:key "z" :shift true :mod true} :redo]
            [{:key "y" :mod true} :redo]
                 ;; Moving blocks
            [{:key "ArrowUp" :shift true :mod true} :move-selected-up]
            [{:key "ArrowDown" :shift true :mod true} :move-selected-down]
            [{:key "ArrowUp" :shift true :alt true} :move-selected-up]
            [{:key "ArrowDown" :shift true :alt true} :move-selected-down]
                 ;; Folding
            [{:key ";" :mod true} {:type :toggle-fold}]
            [{:key "ArrowDown" :mod true} {:type :expand-all}]
            [{:key "ArrowUp" :mod true} {:type :collapse}]
                 ;; Zoom
            [{:key "." :mod true} {:type :zoom-in}]
            [{:key "," :mod true} {:type :zoom-out}]
                 ;; Smart editing
            [{:key "Enter" :mod true} {:type :toggle-checkbox}]
                 ;; Follow link (Logseq parity)
            [{:key "o" :mod true} {:type :follow-link-under-cursor :block-id :editing-block-id :cursor-pos :cursor-pos}]]})
