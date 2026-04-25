(ns plugins.text-formatting-test
  "Unit tests for text formatting plugin.

   Tests the :format-selection intent with focus on:
   - Basic bold/italic toggle
   - Whitespace trimming (Logseq parity)
   - Edge cases"
  (:require [clojure.test :refer [deftest testing is]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            ;; Load the plugin to register intents
            [plugins.text-formatting]
            [utils.session-patch :as session-patch]))

;; ── Session helpers ──────────────────────────────────────────────────────────

(defn empty-session
  "Create an empty session for testing."
  []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}
   :sidebar {:right []}})

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (session-patch/merge-patch session session-updates))

(defn run-intent
  "Run intent and return {:db ... :session ...}"
  [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session}))

;; ── Test Helpers ─────────────────────────────────────────────────────────────

(defn make-db-with-text
  "Create a DB with a single block containing the given text."
  [text]
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "a" :type :block :props {:text text}}
                      {:op :place :id "a" :under :doc :at :last}])))

;; ── Basic Format Tests ───────────────────────────────────────────────────────

;; Highlight (==) and strikethrough (~~) share the same :format-selection
;; handler — the keymap just passes a different :marker. These asserts
;; close the `fr.format/highlight-strikethrough` verification gap.

(deftest ^{:fr/ids #{:fr.format/highlight-strikethrough}} format-selection-highlight
  (testing "Wrapping plain text with == (highlight)"
    (let [db (make-db-with-text "hello world")
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :format-selection
                                               :block-id "a"
                                               :start 6 :end 11
                                               :marker "=="})]
      (is (= "hello ==world==" (get-in db [:nodes "a" :props :text]))))))

(deftest ^{:fr/ids #{:fr.format/highlight-strikethrough}} format-selection-strikethrough
  (testing "Wrapping plain text with ~~ (strikethrough)"
    (let [db (make-db-with-text "hello world")
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :format-selection
                                               :block-id "a"
                                               :start 0 :end 5
                                               :marker "~~"})]
      (is (= "~~hello~~ world" (get-in db [:nodes "a" :props :text]))))))

(deftest ^{:fr/ids #{:fr.format/highlight-strikethrough}} format-selection-highlight-toggles-off
  (testing "Highlight toggle: wrapped text unwraps"
    (let [db (make-db-with-text "hello ==world==")
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :format-selection
                                               :block-id "a"
                                               :start 6 :end 15
                                               :marker "=="})]
      (is (= "hello world" (get-in db [:nodes "a" :props :text]))))))

(deftest format-selection-wrap-test
  (testing "Wrapping plain text with bold markers"
    (let [db (make-db-with-text "hello world")
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :format-selection
                                                        :block-id "a"
                                                        :start 0
                                                        :end 5
                                                        :marker "**"})]
      ;; "hello" should become "**hello**"
      (is (= "**hello** world" (get-in db [:nodes "a" :props :text])))
      ;; Selection should be inside markers
      (is (= {:block-id "a" :start 2 :end 7}
             (get-in session [:ui :pending-selection]))))))

(deftest format-selection-unwrap-test
  (testing "Unwrapping already-formatted text"
    (let [db (make-db-with-text "**hello** world")
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :format-selection
                                                        :block-id "a"
                                                        :start 0
                                                        :end 9  ; "**hello**"
                                                        :marker "**"})]
      ;; "**hello**" should become "hello"
      (is (= "hello world" (get-in db [:nodes "a" :props :text])))
      ;; Selection should cover unwrapped text
      (is (= {:block-id "a" :start 0 :end 5}
             (get-in session [:ui :pending-selection]))))))

;; ── Whitespace Trimming Tests (Logseq Parity) ────────────────────────────────

(deftest format-selection-trims-leading-whitespace
  (testing "Leading whitespace is trimmed before formatting"
    (let [db (make-db-with-text "  hello world")
          session (empty-session)
          ;; Select "  hello" (with leading spaces)
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 0
                                                :end 7  ; "  hello"
                                                :marker "**"})]
      ;; Should format "hello" not "  hello"
      ;; Result: "  **hello** world" (spaces preserved outside markers)
      (is (= "  **hello** world" (get-in db [:nodes "a" :props :text]))))))

(deftest format-selection-trims-trailing-whitespace
  (testing "Trailing whitespace is trimmed before formatting"
    (let [db (make-db-with-text "hello  world")
          session (empty-session)
          ;; Select "hello  " (with trailing spaces)
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 0
                                                :end 7  ; "hello  "
                                                :marker "**"})]
      ;; Should format "hello" not "hello  "
      ;; Result: "**hello**  world" (spaces preserved outside markers)
      (is (= "**hello**  world" (get-in db [:nodes "a" :props :text]))))))

(deftest format-selection-trims-both-whitespace
  (testing "Both leading and trailing whitespace are trimmed"
    (let [db (make-db-with-text "x  bold text  y")
          session (empty-session)
          ;; Select "  bold text  " (with spaces on both sides)
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 1
                                                :end 14  ; "  bold text  "
                                                :marker "**"})]
      ;; Should format "bold text" not "  bold text  "
      ;; Result: "x  **bold text**  y"
      (is (= "x  **bold text**  y" (get-in db [:nodes "a" :props :text]))))))

(deftest format-selection-all-whitespace-preserves
  (testing "All-whitespace selection keeps original (edge case)"
    (let [db (make-db-with-text "a   b")
          session (empty-session)
          ;; Select "   " (only whitespace)
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 1
                                                :end 4  ; "   "
                                                :marker "**"})]
      ;; Should wrap the whitespace as-is (no content to trim to)
      (is (= "a**   **b" (get-in db [:nodes "a" :props :text]))))))

(deftest format-selection-italic-with-whitespace
  (testing "Italic formatting also trims whitespace"
    (let [db (make-db-with-text "say  something  here")
          session (empty-session)
          ;; Select "  something  "
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 3
                                                :end 15  ; "  something  "
                                                :marker "__"})]
      ;; Should become "say  __something__  here"
      (is (= "say  __something__  here" (get-in db [:nodes "a" :props :text]))))))

;; ── Edge Cases ───────────────────────────────────────────────────────────────

(deftest format-selection-single-char
  (testing "Single character selection formats correctly"
    (let [db (make-db-with-text "abc")
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 1
                                                :end 2  ; "b"
                                                :marker "**"})]
      (is (= "a**b**c" (get-in db [:nodes "a" :props :text]))))))

(deftest format-selection-entire-text
  (testing "Selecting entire text formats correctly"
    (let [db (make-db-with-text "hello")
          session (empty-session)
          {:keys [db]} (run-intent db session {:type :format-selection
                                                :block-id "a"
                                                :start 0
                                                :end 5
                                                :marker "**"})]
      (is (= "**hello**" (get-in db [:nodes "a" :props :text]))))))
