(ns plugins.autocomplete-intents-test
  "Unit tests for the autocomplete/slash-palette/quick-switcher intents.

   Covers FR-UI/slash-palette family + FR-UI/quick-switcher. These are
   the six `implemented-untested` FRs the coverage matrix had flagged
   🟡 — the intents work in production, but there were no unit tests
   citing them. These tests drive the intents directly via
   `intent/apply-intent`, asserting only the session-updates + ops
   contract so they stay decoupled from UI rendering."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            ;; Load plugin namespaces so handlers register
            [plugins.autocomplete]
            [plugins.folding]
            [utils.session-patch :as session-patch]))

;; ── Session helper (mirrors plugins/clipboard_test.cljc) ────────────────────

(defn- empty-session []
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

(defn- merge-session [session updates]
  (session-patch/merge-patch session updates))

(defn- run [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        db' (if (seq ops) (:db (tx/interpret db ops)) db)]
    {:db db' :session (merge-session session session-updates)}))

(defn- with-page [db id title]
  (:db (tx/interpret db
                     [{:op :create-node :id id :type :page :props {:title title}}
                      {:op :place :id id :under :doc :at :last}])))

(defn- seed-pages [& titles]
  (reduce-kv (fn [acc idx title]
               (with-page acc (str "p" idx) title))
             (db/empty-db)
             (into {} (map-indexed vector titles))))

;; ── :autocomplete/trigger — FR.ui/slash-palette ─────────────────────────────

(deftest ^{:fr/ids #{:fr.ui/slash-palette}} trigger-opens-palette
  (testing ":autocomplete/trigger initializes autocomplete state with items"
    (let [db (seed-pages "Alpha" "Beta")
          {:keys [session]} (run db (empty-session)
                                 {:type :autocomplete/trigger
                                  :source :page-ref
                                  :block-id "b1"
                                  :trigger-pos 2})
          ac (get-in session [:ui :autocomplete])]
      (is (= :page-ref (:type ac)))
      (is (= "b1" (:block-id ac)))
      (is (= 2 (:trigger-pos ac)))
      (is (= "" (:query ac)))
      (is (= 0 (:selected ac)))
      (is (vector? (:items ac)))
      (is (seq (:items ac)) "palette must surface at least one item"))))

(deftest ^{:fr/ids #{:fr.ui/slash-palette}} trigger-defaults-trigger-length
  (testing "trigger-length defaults from :source when not explicit"
    (let [db (seed-pages "X")
          session (empty-session)
          page-refs (:session (run db session {:type :autocomplete/trigger
                                               :source :page-ref
                                               :block-id "b1"
                                               :trigger-pos 2}))
          commands (:session (run db session {:type :autocomplete/trigger
                                              :source :command
                                              :block-id "b1"
                                              :trigger-pos 1}))]
      (is (= 2 (get-in page-refs [:ui :autocomplete :trigger-length]))
          "[[ is 2 chars")
      (is (= 1 (get-in commands [:ui :autocomplete :trigger-length]))
          "/ is 1 char"))))

;; ── :autocomplete/update — FR.ui/slash-filter ───────────────────────────────

(deftest ^{:fr/ids #{:fr.ui/slash-filter}} update-filters-items
  (testing "successive :autocomplete/update re-runs search with new query"
    (let [db (seed-pages "Alpha" "Beta" "Alphabet")
          {:keys [session]} (run db (empty-session)
                                 {:type :autocomplete/trigger
                                  :source :page-ref
                                  :block-id "b1"
                                  :trigger-pos 2})
          initial-count (count (get-in session [:ui :autocomplete :items]))
          {:keys [session]} (run db session {:type :autocomplete/update :query "alpha"})
          filtered (get-in session [:ui :autocomplete :items])
          existing (filter #(= :existing (:type %)) filtered)]
      (is (pos? initial-count) "palette had items before filtering")
      (is (= "alpha" (get-in session [:ui :autocomplete :query])))
      (is (= 0 (get-in session [:ui :autocomplete :selected]))
          "filter resets selection to 0")
      (is (= 2 (count existing))
          "filter narrows to titles matching 'alpha' (Alpha + Alphabet)"))))

;; ── :autocomplete/navigate — FR.ui/slash-navigate ───────────────────────────

(deftest ^{:fr/ids #{:fr.ui/slash-navigate}} navigate-down-and-up
  (testing ":autocomplete/navigate moves :selected with clamping at boundaries"
    (let [db (seed-pages "A" "B" "C")
          {:keys [session]} (run db (empty-session)
                                 {:type :autocomplete/trigger
                                  :source :page-ref
                                  :block-id "b1"
                                  :trigger-pos 2})
          total (count (get-in session [:ui :autocomplete :items]))
          max-idx (max 0 (dec total))
          {:keys [session]} (run db session {:type :autocomplete/navigate :direction :down})]
      (is (pos? total))
      (is (= (min 1 max-idx) (get-in session [:ui :autocomplete :selected]))
          "down from 0 → 1 (or stays at 0 if list has only one item)")
      ;; Walk all the way to the end, then try to overshoot: should clamp.
      (let [walked (reduce (fn [s _] (:session (run db s {:type :autocomplete/navigate :direction :down})))
                           session
                           (range (inc total)))
            overshot (get-in walked [:ui :autocomplete :selected])]
        (is (= max-idx overshot) "navigate down past end is clamped"))
      ;; Navigate up from 0 is clamped.
      (let [up-at-zero (:session (run db session {:type :autocomplete/navigate :direction :up}))]
        (is (zero? (get-in up-at-zero [:ui :autocomplete :selected]))
            "navigate up at 0 stays at 0")))))

;; ── :autocomplete/select — FR.ui/slash-select ───────────────────────────────

(deftest ^{:fr/ids #{:fr.ui/slash-select}} select-inserts-page-ref
  (testing ":autocomplete/select replaces [[query with [[Title]]"
    (let [db (:db (tx/interpret
                    (seed-pages "Alpha" "Beta")
                    ;; Block with user text "hello [[al"
                    [{:op :create-node :id "b1" :type :block
                      :props {:text "hello [[al"}}
                     {:op :place :id "b1" :under :doc :at :last}]))
          ;; Autocomplete is active with trigger at pos 8 (after "[["),
          ;; query "al", item 0 is "Alpha".
          session (-> (empty-session)
                      (assoc-in [:ui :editing-block-id] "b1")
                      (assoc-in [:ui :autocomplete]
                                {:type :page-ref
                                 :block-id "b1"
                                 :trigger-pos 8
                                 :trigger-length 2
                                 :query "al"
                                 :selected 0
                                 :items [{:type :existing :id "p0" :title "Alpha"}]}))
          {:keys [db session]} (run db session {:type :autocomplete/select})]
      (is (= "hello [[Alpha]]" (get-in db [:nodes "b1" :props :text])))
      (is (nil? (get-in session [:ui :autocomplete]))
          ":select dismisses the palette"))))

;; ── :autocomplete/dismiss — FR.ui/slash-close ───────────────────────────────

(deftest ^{:fr/ids #{:fr.ui/slash-close}} dismiss-closes-without-insert
  (testing ":autocomplete/dismiss clears palette state, no DB op"
    (let [initial-db (seed-pages "A")
          session (-> (empty-session)
                      (assoc-in [:ui :autocomplete]
                                {:type :page-ref
                                 :block-id "b1"
                                 :trigger-pos 2
                                 :query "a"
                                 :selected 0
                                 :items [{:title "A"}]}))
          {:keys [db session]} (run initial-db session {:type :autocomplete/dismiss})]
      (is (nil? (get-in session [:ui :autocomplete])))
      (is (= initial-db db) "no DB mutation"))))

;; ── :toggle-quick-switcher — FR.ui/quick-switcher ───────────────────────────

(deftest ^{:fr/ids #{:fr.ui/quick-switcher}} quick-switcher-toggles
  (testing "first :toggle-quick-switcher opens, second closes"
    (let [db (seed-pages "Alpha")
          {:keys [session]} (run db (empty-session) {:type :toggle-quick-switcher})]
      (is (some? (get-in session [:ui :quick-switcher])) "opens on first toggle")
      (let [{:keys [session]} (run db session {:type :toggle-quick-switcher})]
        (is (nil? (get-in session [:ui :quick-switcher])) "closes on second toggle")))))
