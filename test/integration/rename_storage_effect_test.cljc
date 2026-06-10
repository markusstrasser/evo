(ns integration.rename-storage-effect-test
  "Leak regression: rename-page must NOT route file-cleanup requests
   through ANY dispatch channel.

   History: the original shape — :session-updates {:storage
   {:delete-old-file ...}} — was merged wholesale into the view-state
   atom, where it stayed forever. It then briefly became a
   :storage/delete-page-file effect, until storage reconciliation
   (shell.storage/reconcile-files!, see utils.storage-reconcile) subsumed
   it: file deletion is part of the folder-as-projection-of-db save path,
   symmetric under undo/redo. This test pins that the handler emits ONLY
   ops — no :storage residue, no effects."
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

(deftest rename-emits-only-ops-no-storage-residue
  (let [session {:selection {:nodes #{} :focus nil :anchor nil} :ui {}}
        {:keys [effects session-updates issues db]}
        (api/dispatch (db-with-page) session
                      {:type :rename-page :page-id "p1" :new-title "New Title"})]
    (testing "rename applied"
      (is (empty? issues))
      (is (= "New Title" (get-in db [:nodes "p1" :props :title]))))
    (testing "file cleanup is reconciliation's job — the handler emits no effects"
      (is (nil? effects)))
    (testing "no :storage residue can reach the view-state atom"
      (is (not (contains? (or session-updates {}) :storage))
          ":session-updates carries no :storage key")
      (let [merged (session-patch/merge-patch session session-updates)]
        (is (not (contains? merged :storage))
            "merging the updates leaves no :storage key in the session")))))
