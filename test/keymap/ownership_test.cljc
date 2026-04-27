(ns keymap.ownership-test
  "Checked keyboard ownership partition for declared conflict families."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [keymap.bindings :as bindings]
            [keymap.bindings-data :as bindings-data]
            [keymap.core :as keymap]))

(def editing-session {:ui {:editing-block-id "a"}})
(def view-session {:ui {:editing-block-id nil}})

(defn event [m]
  {:key (:key m)
   :mod (get m :mod false)
   :shift (get m :shift false)
   :alt (get m :alt false)})

(def declared-ownership
  [{:binding :enter :state :editing :target :contenteditable :owner :block}
   {:binding :shift-enter :state :editing :target :contenteditable :owner :block}
   {:binding :escape :state :editing :target :contenteditable :owner :block}
   {:binding :backspace :state :editing :target :contenteditable :owner :block}
   {:binding :delete :state :editing :target :contenteditable :owner :block}
   {:binding :plain-arrows :state :editing :target :contenteditable :owner :block}
   {:binding :shift-arrows :state :editing :target :contenteditable :owner :block}
   {:binding :tab :state :editing :target :contenteditable :owner :shell}
   {:binding :shift-tab :state :editing :target :contenteditable :owner :shell}
   {:binding :cmd-k :state :editing :target :contenteditable :owner :shell}
   {:binding :cmd-shift-up :state :editing :target :contenteditable :owner :shell}
   {:binding :cmd-shift-down :state :editing :target :contenteditable :owner :shell}
   {:binding :printable :state :focused :target :block-view :owner :shell}])

(def block-owned-editing-key-specs
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
    {:key "ArrowDown" :shift true}})

(defn ensure-bindings-loaded [f]
  (bindings/reload!)
  (f))

(use-fixtures :once ensure-bindings-loaded)

(deftest declared-key-families-have-one-owner
  (let [by-row (group-by #(select-keys % [:binding :state :target])
                         declared-ownership)]
    (is (every? (fn [[_ rows]] (= 1 (count (set (map :owner rows)))))
                by-row))))

(deftest block-owned-editing-keys-are-absent-from-keymap
  (let [editing-specs (set (map first (:editing bindings-data/data)))]
    (is (empty? (clojure.set/intersection editing-specs block-owned-editing-key-specs)))))

(deftest shell-owned-conflict-goldens-resolve-through-keymap
  (testing "Editing Tab and Shift+Tab remain shell-owned"
    (is (= :indent-selected
           (keymap/resolve-intent-type (event {:key "Tab"}) editing-session)))
    (is (= :outdent-selected
           (keymap/resolve-intent-type (event {:key "Tab" :shift true}) editing-session))))

  (testing "Editing Cmd+K opens quick switcher per current ownership doc"
    (is (= {:type :toggle-quick-switcher}
           (keymap/resolve-intent-type (event {:key "k" :mod true}) editing-session))))

  (testing "Editing Cmd+Shift+Up/Down are shell-owned structural moves"
    (is (= :move-selected-up
           (keymap/resolve-intent-type
            (event {:key "ArrowUp" :mod true :shift true})
            editing-session)))
    (is (= :move-selected-down
           (keymap/resolve-intent-type
            (event {:key "ArrowDown" :mod true :shift true})
            editing-session)))))

(deftest block-owned-editing-keys-do-not-resolve-through-keymap
  (doseq [spec block-owned-editing-key-specs]
    (testing (pr-str spec)
      (is (nil? (keymap/resolve-intent-type (event spec) editing-session))))))

(deftest non-editing-enter-remains-shell-owned
  (is (= {:type :enter-edit-selected}
         (keymap/resolve-intent-type (event {:key "Enter"}) view-session))))
