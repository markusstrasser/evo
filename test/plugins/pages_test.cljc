(ns plugins.pages-test
  "Regression tests for page delete/restore flows."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.api :as api]
            [kernel.constants :as const]
            [kernel.db :as db]
            [kernel.history :as H]
            [kernel.query :as q]
            [kernel.transaction :as tx]
            [plugins.manifest :as manifest]
            #?@(:cljs [[clojure.string :as str]
                       [shell.storage :as storage]])))

(defn- ensure-plugins!
  []
  (manifest/init!)
  nil)

(defn empty-session
  "Create a minimal session map for intent dispatch tests."
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
        :journals-view? false}
   :sidebar {:right []}})

(defn trashed-page-db
  "Create a DB with one trashed page and a child block."
  []
  (ensure-plugins!)
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node
                      :id "page-a"
                      :type :page
                      :props {:title "Page A"
                              :trashed-at 42}}
                     {:op :place :id "page-a" :under const/root-trash :at :last}
                     {:op :create-node
                      :id "block-a"
                      :type :block
                      :props {:text "hello"}}
                     {:op :place :id "block-a" :under "page-a" :at :last}])
      :db
      H/record))

(deftest restore-page-clears-trash-state-and-exits-journals-view
  (let [db0 (trashed-page-db)
        session (assoc-in (empty-session) [:ui :journals-view?] true)
        {:keys [db session-updates]} (api/dispatch db0 session
                                                   {:type :restore-page
                                                    :page-id "page-a"
                                                    :switch-to? true})]
    (testing "restore moves the page back to the doc root without dropping children"
      (is (= const/root-doc (q/parent-of db "page-a")))
      (is (= ["block-a"] (q/children db "page-a"))))

    (testing "restore clears trash metadata and normalizes page view state"
      (is (nil? (get-in db [:nodes "page-a" :props :trashed-at])))
      (is (= "page-a" (get-in session-updates [:ui :current-page])))
      (is (false? (get-in session-updates [:ui :journals-view?])))
      (is (= #{} (get-in session-updates [:selection :nodes]))))))

#?(:cljs
   (deftest restore-page-roundtrip-does-not-retrash-on-storage-reload
     (let [db0 (trashed-page-db)
           {:keys [db]} (api/dispatch db0 (empty-session)
                                      {:type :restore-page
                                       :page-id "page-a"})
           markdown (storage/page->markdown db "page-a")
           ops (storage/markdown->ops "page-a" markdown)
           page-create-op (some #(when (and (= :create-node (:op %))
                                            (= "page-a" (:id %)))
                                   %)
                                ops)
           page-place-op (some #(when (and (= :place (:op %))
                                           (= "page-a" (:id %)))
                                  %)
                               ops)]
       (testing "save output no longer serializes trashed-at"
         (is (not (str/includes? markdown "trashed-at::"))))

       (testing "reload places the restored page under :doc"
         (is (= const/root-doc (:under page-place-op)))
         (is (nil? (get-in page-create-op [:props :trashed-at])))))))
