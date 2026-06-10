(ns utils.storage-reconcile-test
  "Claim-level tests: reconciliation deletes ONLY managed strays.

   Pins the fix for the rename→undo stale-file bug: baz.md lingered
   after undoing a Foo→Baz rename, resurrecting a duplicate page (with
   duplicate block ids) on the next folder load."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [utils.storage-reconcile :as reconcile]))

(defn- db-with-pages [& titles]
  {:nodes (into {}
                (map-indexed (fn [i title]
                               [(str "p" i) {:type :page :props {:title title}}])
                             titles))})

(deftest stray-files-deletes-only-managed-strays
  (testing "forward rename: old file is the stray"
    (is (= #{"foo-bar.md"}
           (reconcile/stray-files #{"foo-bar.md" "baz.md"}
                                  (db-with-pages "Baz")))))
  (testing "undo of the rename: the renamed file is the stray — symmetric"
    (is (= #{"baz.md"}
           (reconcile/stray-files #{"foo-bar.md" "baz.md"}
                                  (db-with-pages "Foo Bar")))))
  (testing "unmanaged files are never deletion candidates"
    (is (= #{}
           (reconcile/stray-files #{}
                                  (db-with-pages "Baz")))
        "a user-dropped notes.md the app never loaded/wrote is invisible here"))
  (testing "a file still projected by ANY page survives title-collision"
    (is (= #{}
           (reconcile/stray-files #{"foo-bar.md"}
                                  (db-with-pages "Foo Bar" "foo bar")))
        "two titles sanitizing to one filename keep the file"))
  (testing "trashed pages keep their files"
    (let [trashed {:nodes {"p1" {:type :page :props {:title "Kept" :trashed-at 123}}}}]
      (is (= #{} (reconcile/stray-files #{"kept.md"} trashed)))))
  (testing "blocks are not pages — only :page nodes project filenames"
    (let [mixed {:nodes {"p1" {:type :page :props {:title "Real"}}
                         "b1" {:type :block :props {:text "Ghost"}}}}]
      (is (= #{"ghost.md"} (reconcile/stray-files #{"real.md" "ghost.md"} mixed))))))
