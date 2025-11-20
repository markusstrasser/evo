(ns plugins.slash-commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [kernel.constants :as const]
            [kernel.db :as db]
            [kernel.intent :as intent]
            [kernel.transaction :as tx]
            [plugins.slash-commands])) ; ensure intents registered

(defn- run-intent [db intent-map]
  (let [{:keys [ops]} (intent/apply-intent db intent-map)]
    (tx/interpret db ops)))

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
          result (run-intent db {:type :slash-menu/open
                                  :block-id "slash-block"
                                  :trigger-pos 0})
          menu (get-in (:db result) [:nodes const/session-ui-id :props :slash-menu])]
      (is (= "slash-block" (:block-id menu)))
      (is (= 0 (:trigger-pos menu)))
      (is (= 0 (:selected-idx menu)))
      (is (seq (:results menu))))))

(deftest ^{:fr/ids #{:fr.ui/slash-filter}}
  slash-update-search-narrows-results
  (testing "Updating search filters command list"
    (let [db (base-db "/tod")
          {:keys [db]} (run-intent db {:type :slash-menu/open
                                       :block-id "slash-block"
                                       :trigger-pos 0})
          {:keys [db]} (run-intent db {:type :slash-menu/update-search
                                       :block-id "slash-block"
                                       :cursor-pos 4})
          menu (get-in db [:nodes const/session-ui-id :props :slash-menu])]
      (is (= "tod" (:search-text menu)))
      (is (= [:todo] (map :id (:results menu))))
      (is (= 0 (:selected-idx menu))))))

(deftest ^{:fr/ids #{:fr.ui/slash-navigate}}
  slash-next-prev-wrap-selection
  (testing "Next wraps to start and prev wraps to end"
    (let [db (base-db "/")
          {:keys [db]} (run-intent db {:type :slash-menu/open
                                       :block-id "slash-block"
                                       :trigger-pos 0})
          total (count (get-in db [:nodes const/session-ui-id :props :slash-menu :results]))
          forward-db (reduce (fn [state _]
                               (:db (run-intent state {:type :slash-menu/next})))
                             db
                             (range total))
          wrapped-idx (get-in forward-db [:nodes const/session-ui-id :props :slash-menu :selected-idx])
          backward-db (:db (run-intent db {:type :slash-menu/prev}))
          prev-idx (get-in backward-db [:nodes const/session-ui-id :props :slash-menu :selected-idx])]
      (is (= 0 wrapped-idx))
      (is (= (dec total) prev-idx)))))

(deftest ^{:fr/ids #{:fr.ui/slash-select}}
  slash-select-executes-command
  (testing "Selecting a command applies block mutation"
    (let [db (base-db "/todo")
          {:keys [db]} (run-intent db {:type :slash-menu/open
                                       :block-id "slash-block"
                                       :trigger-pos 0})
          {:keys [db]} (run-intent db {:type :slash-menu/select})
          text (get-in db [:nodes "slash-block" :props :text])
          menu (get-in db [:nodes const/session-ui-id :props :slash-menu])]
      (is (str/starts-with? text "TODO"))
      (is (nil? menu)))))

(deftest ^{:fr/ids #{:fr.ui/slash-close}}
  slash-close-clears-menu
  (testing "Closing menu removes slash state"
    (let [db (base-db "/todo")
          {:keys [db]} (run-intent db {:type :slash-menu/open
                                       :block-id "slash-block"
                                       :trigger-pos 0})
          {:keys [db]} (run-intent db {:type :slash-menu/close})]
      (is (nil? (get-in db [:nodes const/session-ui-id :props :slash-menu]))))))
