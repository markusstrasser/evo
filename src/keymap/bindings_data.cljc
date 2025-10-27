(ns keymap.bindings-data)

(def data
  {:non-editing [[{:key "ArrowDown"} {:type :selection :mode :next}]
                 [{:key "ArrowUp"} {:type :selection :mode :prev}]
                 [{:key "ArrowDown" :alt true} {:type :selection :mode :next}]
                 [{:key "ArrowUp" :alt true} {:type :selection :mode :prev}]
                 [{:key "ArrowDown" :shift true} {:type :selection :mode :extend-next}]
                 [{:key "ArrowUp" :shift true} {:type :selection :mode :extend-prev}]
                 [{:key "Tab"} :indent-selected]
                 [{:key "Tab" :shift true} :outdent-selected]
                 [{:key "Backspace"} :delete-selected]
                 [{:key "Enter"} :create-and-enter-edit]]
   :editing     [[{:key "Escape"} :exit-edit]
                 [{:key "Tab"} :indent-selected]
                 [{:key "Tab" :shift true} :outdent-selected]
                 [{:key "Backspace" :mod true} :merge-with-prev]]
   :global      [[{:key "ArrowUp" :shift true :mod true} :move-selected-up]
                 [{:key "ArrowDown" :shift true :mod true} :move-selected-down]
                 [{:key "ArrowUp" :shift true :alt true} :move-selected-up]
                 [{:key "ArrowDown" :shift true :alt true} :move-selected-down]]})
