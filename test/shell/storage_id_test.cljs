(ns shell.storage-id-test
  "Round-trip tests for Phase C: stable block IDs via Logseq-style id::
   property lines."
  (:require [cljs.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [shell.storage :as storage]
            [clojure.string :as str]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn- build-page
  "Build a db with one page and the given blocks under it.
   Each block is {:id :text :children [...]}."
  [page-id title blocks]
  (let [block-ops
        (letfn [(walk [parent-id blks]
                  (mapcat (fn [{:keys [id text children]}]
                            (concat
                             [{:op :create-node :id id :type :block :props {:text text}}
                              {:op :place :id id :under parent-id :at :last}]
                             (walk id (or children []))))
                          blks))]
          (walk page-id blocks))
        ops (concat
             [{:op :create-node :id page-id :type :page :props {:title title}}
              {:op :place :id page-id :under :doc :at :last}]
             block-ops)]
    (:db (tx/interpret (db/empty-db) ops))))

(defn- round-trip
  "Serialize page `page-id` from `db` and re-parse it. Returns the ops
   that would be applied on reload."
  [db page-id]
  (let [md (storage/page->markdown db page-id)]
    (storage/markdown->ops page-id md)))

;; ── Serializer emits id:: lines ─────────────────────────────────────────────

(deftest serializer-writes-id-property
  (testing "Each block gets an id:: property line"
    (let [db (build-page "p" "Page" [{:id "block-a" :text "hello"}
                                     {:id "block-b" :text "world"}])
          md (storage/page->markdown db "p")]
      (is (str/includes? md "id:: block-a"))
      (is (str/includes? md "id:: block-b")))))

(deftest serializer-places-id-under-block-content
  (testing "id:: line is indented to block-content level (indent+2 spaces)"
    (let [db (build-page "p" "Page" [{:id "outer" :text "outer text"
                                      :children [{:id "inner" :text "inner text"}]}])
          md (storage/page->markdown db "p")
          lines (str/split-lines md)]
      (is (some #{"  id:: outer"} lines)
          "Top-level block id:: aligned under its bullet")
      (is (some #{"    id:: inner"} lines)
          "Nested block id:: aligned under its bullet"))))

;; ── Parser honors id:: when present ─────────────────────────────────────────

(deftest round-trip-preserves-ids
  (testing "Serialize → parse yields the same block ids"
    (let [db (build-page "p" "Page" [{:id "alpha" :text "A"}
                                     {:id "beta" :text "B"
                                      :children [{:id "gamma" :text "G"}]}])
          ops (round-trip db "p")
          block-ids (->> ops
                         (filter #(and (= :create-node (:op %))
                                       (= :block (:type %))))
                         (map :id)
                         set)]
      (is (= #{"alpha" "beta" "gamma"} block-ids)))))

(deftest round-trip-preserves-structure
  (testing "Parent/child relationships survive reload"
    (let [db (build-page "p" "Page" [{:id "outer" :text "outer"
                                      :children [{:id "inner" :text "inner"}]}])
          ops (round-trip db "p")
          inner-place (first (filter #(and (= :place (:op %))
                                           (= "inner" (:id %))) ops))]
      (is (= "outer" (:under inner-place))
          "Inner block is placed under outer after round-trip"))))

;; ── Total rules for malformed input ─────────────────────────────────────────

(deftest missing-id-mints-block-uuid
  (testing "Block without id:: gets a fresh block-<uuid>"
    (let [md "title:: T\n- line one"
          ops (storage/markdown->ops "p" md)
          block-op (first (filter #(and (= :create-node (:op %))
                                        (= :block (:type %))) ops))]
      (is (str/starts-with? (:id block-op) "block-"))
      (is (pos? (count (:id block-op)))))))

(deftest duplicate-id-keeps-first-remints-second
  (testing "Two blocks with the same id:: — second is re-minted"
    (let [md (str "title:: T\n"
                  "- first\n"
                  "  id:: dup\n"
                  "- second\n"
                  "  id:: dup\n")
          ops (storage/markdown->ops "p" md)
          block-ids (->> ops
                         (filter #(and (= :create-node (:op %))
                                       (= :block (:type %))))
                         (map :id))]
      (is (= 2 (count block-ids)) "Both blocks are created")
      (is (= 2 (count (set block-ids))) "The two ids are distinct")
      (is (some #{"dup"} block-ids) "First occurrence keeps its id"))))

(deftest blank-id-value-is-reminted
  (testing "id:: with empty value gets minted"
    (let [md (str "title:: T\n"
                  "- block\n"
                  "  id:: \n")
          ops (storage/markdown->ops "p" md)
          block-op (first (filter #(and (= :create-node (:op %))
                                        (= :block (:type %))) ops))]
      (is (str/starts-with? (:id block-op) "block-"))
      (is (not= "" (:id block-op))))))

(deftest non-uuid-id-is-accepted-as-is
  (testing "Non-UUID strings are accepted — evo's own id format is not UUID-shaped"
    (let [md (str "title:: T\n"
                  "- block\n"
                  "  id:: page-a-b1\n")
          ops (storage/markdown->ops "p" md)
          block-op (first (filter #(and (= :create-node (:op %))
                                        (= :block (:type %))) ops))]
      (is (= "page-a-b1" (:id block-op))
          "Non-UUID ids round-trip unchanged"))))

;; ── Multiline blocks still work ─────────────────────────────────────────────

(deftest multiline-block-with-id
  (testing "id:: comes after continuation lines"
    (let [db (build-page "p" "Page"
                         [{:id "ml" :text "line one\nline two\nline three"}])
          md (storage/page->markdown db "p")
          ops (round-trip db "p")
          block-op (first (filter #(and (= :create-node (:op %))
                                        (= "ml" (:id %))) ops))]
      (is (str/includes? md "line two"))
      (is (str/includes? md "line three"))
      (is (str/includes? md "id:: ml"))
      (is (= "line one\nline two\nline three"
             (get-in block-op [:props :text]))
          "Multi-line text reconstructed correctly"))))
