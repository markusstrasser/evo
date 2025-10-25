(ns keymap.bindings-data)

(def data
  {:non-editing [[{:key "ArrowDown"} :select-next-sibling]
                 [{:key "ArrowUp"} :select-prev-sibling]
                 [{:key "ArrowDown" :alt true} :select-next-sibling]
                 [{:key "ArrowUp" :alt true} :select-prev-sibling]
                 [{:key "ArrowDown" :shift true} :extend-to-next-sibling]
                 [{:key "ArrowUp" :shift true} :extend-to-prev-sibling]
                 [{:key "Tab"} :indent-selected]
                 [{:key "Tab" :shift true} :outdent-selected]
                 [{:key "Backspace"} :delete-selected]
                 [{:key "Enter"} :create-new-block-after-focus]]
   :editing     [[{:key "Escape"} :exit-edit]
                 [{:key "Backspace" :mod true} :merge-with-prev]]
   :global      [[{:key "ArrowUp" :shift true :mod true} :move-selected-up]
                 [{:key "ArrowDown" :shift true :mod true} :move-selected-down]
                 [{:key "ArrowUp" :shift true :alt true} :move-selected-up]
                 [{:key "ArrowDown" :shift true :alt true} :move-selected-down]]})
