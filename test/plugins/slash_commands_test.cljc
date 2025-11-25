(ns plugins.slash-commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [kernel.constants :as const]
            [kernel.db :as db]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [plugins.slash-commands])) ; ensure intents registered

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
        :cursor-position nil
        :slash-menu nil}
   :sidebar {:right []}})

(defn apply-session-updates
  "Apply session-updates returned by a handler to a session."
  [session session-updates]
  (if session-updates
    (merge-with merge session session-updates)
    session))

;; ── Test helpers ─────────────────────────────────────────────────────────────

(defn- run-intent
  "Run intent and return {:db ... :session ...}"
  [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (:db (tx/interpret db (or ops [])))
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session}))

(defn- seed-block [text]
  [(let [id "slash-block"]
     {:op :create-node :id id :type :block :props {:text text}})
   {:op :place :id "slash-block" :under :doc :at :last}])

(defn- base-db [text]
  (:db (tx/interpret (db/empty-db) (seed-block text))))

(deftest ^{:fr/ids #{:fr.ui/slash-palette}}
  slash-open-sets-menu-state
  (testing "Opening the slash menu records trigger metadata"
    (let [db (base-db "/todo")
          session (empty-session)
          {:keys [session]} (run-intent db session {:type :slash-menu/open
                                                     :block-id "slash-block"
                                                     :trigger-pos 0})
          menu (get-in session [:ui :slash-menu])]
      (is (= "slash-block" (:block-id menu)))
      (is (= 0 (:trigger-pos menu)))
      (is (= 0 (:selected-idx menu)))
      (is (seq (:results menu))))))

(deftest ^{:fr/ids #{:fr.ui/slash-filter}}
  slash-update-search-narrows-results
  (testing "Updating search filters command list"
    (let [db (base-db "/tod")
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :slash-menu/open
                                                        :block-id "slash-block"
                                                        :trigger-pos 0})
          {:keys [session]} (run-intent db session {:type :slash-menu/update-search
                                                     :block-id "slash-block"
                                                     :cursor-pos 4})
          menu (get-in session [:ui :slash-menu])]
      (is (= "tod" (:search-text menu)))
      (is (= [:todo] (map :id (:results menu))))
      (is (= 0 (:selected-idx menu))))))

(deftest ^{:fr/ids #{:fr.ui/slash-navigate}}
  slash-next-prev-wrap-selection
  (testing "Next wraps to start and prev wraps to end"
    (let [db (base-db "/")
          session (empty-session)
          {:keys [session]} (run-intent db session {:type :slash-menu/open
                                                     :block-id "slash-block"
                                                     :trigger-pos 0})
          total (count (get-in session [:ui :slash-menu :results]))
          forward-session (reduce (fn [s _]
                                    (:session (run-intent db s {:type :slash-menu/next})))
                                  session
                                  (range total))
          wrapped-idx (get-in forward-session [:ui :slash-menu :selected-idx])
          {:keys [session]} (run-intent db session {:type :slash-menu/prev})
          prev-idx (get-in session [:ui :slash-menu :selected-idx])]
      (is (= 0 wrapped-idx))
      (is (= (dec total) prev-idx)))))

(deftest ^{:fr/ids #{:fr.ui/slash-select}}
  slash-select-executes-command
  (testing "Selecting a command applies block mutation"
    (let [db (base-db "/todo")
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :slash-menu/open
                                                        :block-id "slash-block"
                                                        :trigger-pos 0})
          {:keys [db session]} (run-intent db session {:type :slash-menu/select})
          text (get-in db [:nodes "slash-block" :props :text])
          menu (get-in session [:ui :slash-menu])]
      (is (str/starts-with? text "TODO"))
      (is (nil? menu)))))

(deftest ^{:fr/ids #{:fr.ui/slash-close}}
  slash-close-clears-menu
  (testing "Closing menu removes slash state"
    (let [db (base-db "/todo")
          session (empty-session)
          {:keys [db session]} (run-intent db session {:type :slash-menu/open
                                                        :block-id "slash-block"
                                                        :trigger-pos 0})
          {:keys [session]} (run-intent db session {:type :slash-menu/close})]
      (is (nil? (get-in session [:ui :slash-menu]))))))
