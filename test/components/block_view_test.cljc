(ns components.block-view-test
  "View tests for Block component.

   These tests verify that the Block component renders correct hiccup
   without requiring a browser. We test:
   - Rendering in different modes (edit/view)
   - Event handler wiring
   - Lifecycle hooks
   - CSS class application
   - Attribute values"
  (:require [clojure.test :refer [deftest testing is]]
            [view-util :as vu]
            [components.block :as block]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.constants :as const]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn sample-db
  "Create a minimal DB for testing Block component."
  []
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "Hello world"}}
                     {:op :place :id "a" :under :doc :at :last}])
      :db))

(defn editing-db
  "Create DB with block in editing mode."
  []
  (-> (sample-db)
      (tx/interpret [{:op :update-node
                      :id const/session-ui-id
                      :props {:editing-block-id "a"
                              :cursor-position 5}}])
      :db))

;; =============================================================================
;; Basic Rendering Tests
;; =============================================================================

(deftest block-renders-as-vector
  (testing "Block component returns hiccup vector"
    (let [db (sample-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (vector? view)
          "Block returns hiccup vector")
      (is (= :div.block (first view))
          "Root element is div.block"))))

(deftest block-view-mode-test
  (testing "Block in view mode shows content-view span"
    (let [db (sample-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (vu/element-exists? view :.content-view)
          "content-view element exists in view mode")
      (is (not (vu/element-exists? view :.content-edit))
          "content-edit element does not exist in view mode")
      (is (= "Hello world"
             (vu/extract-text (vu/find-element view :.content-view)))
          "content-view displays block text"))))

(deftest block-edit-mode-test
  (testing "Block in edit mode shows contenteditable span"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (vu/element-exists? view :.content-edit)
          "content-edit element exists in edit mode")
      (is (not (vu/element-exists? view :.content-view))
          "content-view element does not exist in edit mode")
      (is (vu/select-attribute view :.content-edit :contentEditable)
          "content-edit is contentEditable"))))

;; =============================================================================
;; Event Handler Tests
;; =============================================================================

(deftest block-input-handler-test
  (testing "Block in edit mode has input event handler"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})
          element (vu/find-element view :.content-edit)]
      (is (vu/has-event-handler? element :input)
          "content-edit has :input handler"))))

(deftest block-blur-handler-test
  (testing "Block in edit mode has blur event handler"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})
          element (vu/find-element view :.content-edit)]
      (is (vu/has-event-handler? element :blur)
          "content-edit has :blur handler"))))

(deftest block-keydown-handler-test
  (testing "Block in edit mode has keydown event handler"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})
          element (vu/find-element view :.content-edit)]
      (is (vu/has-event-handler? element :keydown)
          "content-edit has :keydown handler"))))

;; =============================================================================
;; Lifecycle Hook Tests
;; =============================================================================

(deftest block-mount-hook-test
  (testing "Block in edit mode has on-mount lifecycle hook"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})
          element (vu/find-element view :.content-edit)
          attrs (vu/get-attrs element)]
      (is (fn? (:replicant/on-mount attrs))
          "content-edit has :replicant/on-mount hook"))))

(deftest block-render-hook-test
  (testing "Block in edit mode has on-render lifecycle hook"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})
          element (vu/find-element view :.content-edit)
          attrs (vu/get-attrs element)]
      (is (fn? (:replicant/on-render attrs))
          "content-edit has :replicant/on-render hook"))))

;; =============================================================================
;; Attribute Tests
;; =============================================================================

(deftest block-data-attribute-test
  (testing "Block has data-block-id attribute"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (= "a" (vu/select-attribute view :.content-edit :data-block-id))
          "content-edit has correct data-block-id"))))

(deftest block-suppress-warning-test
  (testing "Block has suppressContentEditableWarning attribute"
    (let [db (editing-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (vu/select-attribute view :.content-edit :suppressContentEditableWarning)
          "content-edit has suppressContentEditableWarning"))))

;; =============================================================================
;; CSS Class Tests
;; =============================================================================

(deftest block-root-class-test
  (testing "Block root element has correct CSS classes"
    (let [db (sample-db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (= :div.block (first view))
          "Root element has .block class"))))

;; =============================================================================
;; Multiple Blocks Test
;; =============================================================================

(deftest multiple-blocks-test
  (testing "Can render multiple blocks"
    (let [db (-> (db/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text "First"}}
                                {:op :place :id "a" :under :doc :at :last}
                                {:op :create-node :id "b" :type :block :props {:text "Second"}}
                                {:op :place :id "b" :under :doc :at :last}])
                 :db)
          view-a (block/Block {:db db :block-id "a" :depth 0 :on-intent identity})
          view-b (block/Block {:db db :block-id "b" :depth 0 :on-intent identity})]
      (is (= "First" (vu/extract-text (vu/find-element view-a :.content-view))))
      (is (= "Second" (vu/extract-text (vu/find-element view-b :.content-view)))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest block-empty-text-test
  (testing "Block renders with empty text"
    (let [db (-> (db/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block :props {:text ""}}
                                {:op :place :id "a" :under :doc :at :last}])
                 :db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (vector? view)
          "Block renders with empty text")
      (is (= "" (vu/extract-text (vu/find-element view :.content-view)))
          "Empty text renders correctly"))))

(deftest block-special-chars-test
  (testing "Block renders with special characters"
    (let [db (-> (db/empty-db)
                 (tx/interpret [{:op :create-node :id "a" :type :block
                                 :props {:text "Special <>&\" chars"}}
                                {:op :place :id "a" :under :doc :at :last}])
                 :db)
          view (block/Block {:db db
                             :block-id "a"
                             :depth 0
                             :on-intent identity})]
      (is (= "Special <>&\" chars"
             (vu/extract-text (vu/find-element view :.content-view)))
          "Special characters render correctly"))))
