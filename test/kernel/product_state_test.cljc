(ns kernel.product-state-test
  (:require [clojure.test :refer [deftest is]]
            [kernel.db :as db]
            [kernel.product-state :as ps]
            [kernel.state-machine :as sm]
            [kernel.transaction :as tx]))

(defn op-create [id text]
  {:op :create-node :id id :type :block :props {:text text}})

(defn op-place [id under at]
  {:op :place :id id :under under :at at})

(def base-db
  (:db (tx/interpret (db/empty-db)
                     [(op-create "a" "Alpha")
                      (op-create "b" "Beta")
                      (op-create "c" "Gamma")
                      (op-place "a" :doc :last)
                      (op-place "b" :doc :last)
                      (op-place "c" "b" :last)])))

(def base-session
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {}
   :ui {:folded #{}
        :zoom-root nil
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}})

(deftest editing-and-selection-are-exclusive
  (let [session (-> base-session
                    (assoc-in [:ui :editing-block-id] "a")
                    (assoc-in [:selection :nodes] #{"a"})
                    (assoc-in [:selection :focus] "a")
                    (assoc-in [:selection :anchor] "a"))]
    (is (= :editing-and-selection-coexist
           (:issue (first (ps/validate base-db session)))))))

(deftest focused-is-not-background
  (let [session (assoc-in base-session [:selection :focus] "a")]
    (is (= [] (ps/validate base-db session)))
    (is (= :focused (sm/current-state session)))))

(deftest selection-anchor-focus-must-be-visible
  (let [session (-> base-session
                    (assoc-in [:ui :folded] #{"b"})
                    (assoc-in [:selection :nodes] #{"c"})
                    (assoc-in [:selection :focus] "c")
                    (assoc-in [:selection :anchor] "missing"))]
    (is (some #(= :selection-anchor-not-selected (:issue %))
              (ps/validate base-db session)))
    (is (some #(= :selected-node-not-visible-selectable (:issue %))
              (ps/validate base-db session)))))

(deftest buffer-cannot-exist-outside-editing
  (let [session (assoc base-session :buffer {"a" "draft"})]
    (is (= :buffer-without-editing
           (:issue (first (ps/validate base-db session)))))))

(deftest buffer-owner-matches-editing-block
  (let [session (-> base-session
                    (assoc :buffer {"b" "draft"})
                    (assoc-in [:ui :editing-block-id] "a"))]
    (is (some #(= :buffer-owner-mismatch (:issue %))
              (ps/validate base-db session)))))

(deftest cursor-offset-within-live-text
  (let [session (-> base-session
                    (assoc :buffer {"a" "draft"})
                    (assoc-in [:ui :editing-block-id] "a")
                    (assoc-in [:ui :cursor-position] 99))]
    (is (= :cursor-offset-out-of-bounds
           (:issue (first (ps/validate base-db session)))))))

(deftest invalid-product-state-fails-loudly
  (let [session (assoc-in base-session [:ui :folded] #{"a"})]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (ps/assert-valid! base-db session)))))
