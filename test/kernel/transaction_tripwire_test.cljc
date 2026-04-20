(ns kernel.transaction-tripwire-test
  "Integration test: the text tripwire in `kernel.text-validation`
   actually rejects transactions at the kernel boundary, not just
   at the predicate level.

   Regression coverage for the 2026-04-19 class of bugs where a DOM
   scanner (MathJax today, Prism/highlight.js tomorrow) mutates
   rendered text and something downstream reads textContent back into
   the buffer. All intent paths funnel through :create-node /
   :update-node ops, so wiring the predicate into those two validators
   is the single chokepoint."
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]))

(defn- fresh-db-with-block
  "Build a DB containing a single placed block with the given id."
  [id text]
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id id :type :block :props {:text text}}
                      {:op :place :id id :under :doc :at :last}])))

(deftest create-node-rejects-corrupt-text
  (testing "private-use-area char in :text blocks the create"
    (let [corrupt (str "glyph " (char 0xE001))
          {:keys [db issues]} (tx/interpret
                               (db/empty-db)
                               [{:op :create-node :id "a" :type :block
                                 :props {:text corrupt}}])]
      (is (not (contains? (:nodes db) "a"))
          "node was not created")
      (is (= 1 (count issues)))
      (is (= :text-tripwire/private-use-char (:issue (first issues))))))

  (testing "<mjx- markup in :text blocks the create"
    (let [{:keys [db issues]} (tx/interpret
                               (db/empty-db)
                               [{:op :create-node :id "b" :type :block
                                 :props {:text "hello <mjx-container>x</mjx-container>"}}])]
      (is (not (contains? (:nodes db) "b")))
      (is (= :text-tripwire/scanner-markup (:issue (first issues))))))

  (testing "control char in :text blocks the create"
    (let [{:keys [db issues]} (tx/interpret
                               (db/empty-db)
                               [{:op :create-node :id "c" :type :block
                                 :props {:text (str "x" (char 0x00) "y")}}])]
      (is (not (contains? (:nodes db) "c")))
      (is (= :text-tripwire/control-char (:issue (first issues))))))

  (testing "legitimate text with dollars, underscores, unicode still creates"
    (let [text "cljs$core$key with _underscores_ and привет"
          {:keys [db issues]} (tx/interpret
                               (db/empty-db)
                               [{:op :create-node :id "ok" :type :block
                                 :props {:text text}}
                                {:op :place :id "ok" :under :doc :at :last}])]
      (is (empty? issues))
      (is (= text (get-in db [:nodes "ok" :props :text]))))))

(deftest update-node-rejects-corrupt-text
  (testing "corrupt :text on update is rejected, DB text unchanged"
    (let [db0 (fresh-db-with-block "blk" "hello")
          corrupt (str "glyph " (char 0xE001))
          {:keys [db issues]} (tx/interpret
                               db0
                               [{:op :update-node :id "blk"
                                 :props {:text corrupt}}])]
      (is (= 1 (count issues)))
      (is (= :text-tripwire/private-use-char (:issue (first issues))))
      (is (= "hello" (get-in db [:nodes "blk" :props :text]))
          "DB text must be the pre-corruption value")))

  (testing "update to clean text succeeds"
    (let [db0 (fresh-db-with-block "blk" "old")
          {:keys [db issues]} (tx/interpret
                               db0
                               [{:op :update-node :id "blk"
                                 :props {:text "new clean text"}}])]
      (is (empty? issues))
      (is (= "new clean text" (get-in db [:nodes "blk" :props :text]))))))

(deftest tripwire-blocks-corrupt-op-in-mixed-batch
  (testing "a corrupt op is rejected even when preceded by clean ops"
    ;; After merge-adjacent-updates normalises the two update-node ops
    ;; on the same id into one deep-merged update, the validator sees a
    ;; single op with the corrupt :text and rejects the whole thing.
    (let [db0 (fresh-db-with-block "blk" "start")
          {:keys [db issues]} (tx/interpret
                               db0
                               [{:op :update-node :id "blk"
                                 :props {:text "clean first step"}}
                                {:op :update-node :id "blk"
                                 :props {:text (str "x" (char 0xE001))}}])
          text-after (get-in db [:nodes "blk" :props :text])]
      (is (seq issues) "the transaction must surface a tripwire issue")
      (is (not (re-find #"\uE001" text-after))
          "the corrupt glyph must never appear in the committed text"))))
