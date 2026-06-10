(ns integration.rename-storage-effect-test
  "Leak regression: rename-page must NOT route its file-cleanup request
   through :session-updates.

   The old shape — :session-updates {:storage {:delete-old-file ...}} — was
   merged wholesale into the view-state atom by merge-view-state-updates!,
   where it stayed forever (nothing reads or clears :storage from view
   state). The request now travels as a declarative effect; this test pins
   both directions: the effect IS returned, and no :storage residue can
   reach the session."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing use-fixtures]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer [deftest is testing use-fixtures]])
            [harness.runtime-fixtures :as runtime-fixtures]
            [kernel.api :as api]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            ;; Registers :rename-page
            [plugins.pages]
            [utils.session-patch :as session-patch]))

(use-fixtures :once runtime-fixtures/bootstrap-runtime)

(defn- db-with-page []
  (:db (tx/interpret (db/empty-db)
                     [{:op :create-node :id "p1" :type :page :props {:title "Old Title"}}
                      {:op :place :id "p1" :under :doc :at :last}]
                     {:tx/now-ms 1})))

(deftest rename-returns-delete-effect-not-session-storage
  (let [session {:selection {:nodes #{} :focus nil :anchor nil} :ui {}}
        {:keys [effects session-updates issues db]}
        (api/dispatch (db-with-page) session
                      {:type :rename-page :page-id "p1" :new-title "New Title"})]
    (testing "rename applied"
      (is (empty? issues))
      (is (= "New Title" (get-in db [:nodes "p1" :props :title]))))
    (testing "file cleanup travels as a declarative effect"
      (is (= [[:storage/delete-page-file {:title "Old Title"}]] effects)))
    (testing "no :storage residue can reach the view-state atom"
      (is (not (contains? (or session-updates {}) :storage))
          ":session-updates carries no :storage key")
      (let [merged (session-patch/merge-patch session session-updates)]
        (is (not (contains? merged :storage))
            "merging the updates leaves no :storage key in the session")))))
