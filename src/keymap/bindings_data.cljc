(ns keymap.bindings-data)

(def data
  {:non-editing [[{:key "ArrowDown"} {:type :selection :mode :next}]
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
                 [{:key "Backspace" :mod true} :merge-with-prev]]
   :global      [;; Multi-selection (works everywhere, including edit mode)
                 [{:key "ArrowDown" :shift true} {:type :selection :mode :extend-next}]
                 [{:key "ArrowUp" :shift true} {:type :selection :mode :extend-prev}]
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
