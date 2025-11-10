(ns keymap.bindings-data)

(def data
  {:non-editing [[{:key "Escape"} {:type :selection :mode :clear}]
                 [{:key "ArrowDown"} {:type :selection :mode :next}]
                 [{:key "ArrowUp"} {:type :selection :mode :prev}]
                 [{:key "ArrowDown" :alt true} {:type :selection :mode :next}]
                 [{:key "ArrowUp" :alt true} {:type :selection :mode :prev}]
                 [{:key "Tab"} :indent-selected]
                 [{:key "Tab" :shift true} :outdent-selected]
                 [{:key "Backspace"} :delete-selected]
                 [{:key "Enter"} :create-and-enter-edit]]
   :editing     [[{:key "Escape"} :exit-edit]
                 [{:key "Tab"} :indent-selected]
                 [{:key "Tab" :shift true} :outdent-selected]
                 [{:key "Backspace" :mod true} :merge-with-prev]
                 ;; Text formatting (requires selection)
                 [{:key "b" :mod true} {:type :format-selection :marker "**"}]
                 [{:key "i" :mod true} {:type :format-selection :marker "__"}]
                 [{:key "h" :shift true :mod true} {:type :format-selection :marker "^^"}]
                 [{:key "s" :shift true :mod true} {:type :format-selection :marker "~~"}]
                 ;; Word navigation (Emacs-style)
                 [{:key "f" :ctrl true :shift true} {:type :move-cursor-forward-word :block-id :editing-block-id}]
                 [{:key "b" :ctrl true :shift true} {:type :move-cursor-backward-word :block-id :editing-block-id}]
                 ;; Kill commands (Emacs-style)
                 [{:key "l" :mod true} {:type :clear-block-content :block-id :editing-block-id}]
                 [{:key "u" :mod true} {:type :kill-to-beginning :block-id :editing-block-id}]
                 [{:key "k" :mod true} {:type :kill-to-end :block-id :editing-block-id}]
                 [{:key "Delete" :mod true} {:type :kill-word-forward :block-id :editing-block-id}]
                 [{:key "Delete" :alt true} {:type :kill-word-backward :block-id :editing-block-id}]]
   :global      [;; Multi-selection (works everywhere, including edit mode)
                 [{:key "ArrowDown" :shift true} {:type :selection :mode :extend-next}]
                 [{:key "ArrowUp" :shift true} {:type :selection :mode :extend-prev}]
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
                 [{:key "Enter" :mod true} {:type :toggle-checkbox}]]})
