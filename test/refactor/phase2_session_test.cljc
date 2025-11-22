(ns refactor.phase2-session-test
  "Phase 2: Verify session atom functionality and DB sync.

   Tests that:
   1. Session atom is created with correct structure
   2. Session query helpers work correctly
   3. Session syncs from DB correctly
   4. Session state is independent of DB state"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            #?@(:cljs [[shell.session :as session]
                       [shell.session-sync :as session-sync]])
            [kernel.db :as db]
            [kernel.constants :as const]))

;; Note: These tests are CLJS-only since session is CLJS-only

#?(:cljs
   (deftest session-atom-structure
     (testing "Session atom has correct initial structure"
       (session/reset-session!)
       (let [s (session/get-session)]
         (is (contains? s :cursor)
             "Session should have :cursor key")
         (is (contains? s :selection)
             "Session should have :selection key")
         (is (contains? s :buffer)
             "Session should have :buffer key")
         (is (contains? s :ui)
             "Session should have :ui key")
         (is (contains? s :sidebar)
             "Session should have :sidebar key")))

     (testing "Session cursor structure"
       (session/reset-session!)
       (let [cursor (get-in (session/get-session) [:cursor])]
         (is (contains? cursor :block-id))
         (is (contains? cursor :offset))
         (is (nil? (:block-id cursor))
             "Initial cursor block-id should be nil")
         (is (= 0 (:offset cursor))
             "Initial cursor offset should be 0")))

     (testing "Session selection structure"
       (session/reset-session!)
       (let [sel (get-in (session/get-session) [:selection])]
         (is (contains? sel :nodes))
         (is (contains? sel :focus))
         (is (contains? sel :anchor))
         (is (= #{} (:nodes sel))
             "Initial selection nodes should be empty set")))

     (testing "Session UI structure"
       (session/reset-session!)
       (let [ui (get-in (session/get-session) [:ui])]
         (is (contains? ui :folded))
         (is (contains? ui :zoom-root))
         (is (contains? ui :current-page))
         (is (contains? ui :editing-block-id))
         (is (contains? ui :cursor-position))
         (is (= #{} (:folded ui))
             "Initial folded set should be empty")))))

#?(:cljs
   (deftest session-query-helpers
     (testing "editing-block-id helper"
       (session/reset-session!)
       (is (nil? (session/editing-block-id))
           "Initial editing-block-id should be nil")

       (session/swap-session! assoc-in [:ui :editing-block-id] "test-block")
       (is (= "test-block" (session/editing-block-id))
           "editing-block-id should return updated value"))

     (testing "folded helper"
       (session/reset-session!)
       (is (= #{} (session/folded))
           "Initial folded should be empty set")

       (session/swap-session! assoc-in [:ui :folded] #{"a" "b"})
       (is (= #{"a" "b"} (session/folded))
           "folded should return updated set"))

     (testing "zoom-root helper"
       (session/reset-session!)
       (is (nil? (session/zoom-root))
           "Initial zoom-root should be nil")

       (session/swap-session! assoc-in [:ui :zoom-root] "zoom-block")
       (is (= "zoom-block" (session/zoom-root))
           "zoom-root should return updated value"))

     (testing "selection-nodes helper"
       (session/reset-session!)
       (is (= #{} (session/selection-nodes))
           "Initial selection nodes should be empty")

       (session/swap-session! assoc-in [:selection :nodes] #{"x" "y"})
       (is (= #{"x" "y"} (session/selection-nodes))
           "selection-nodes should return updated set"))

     (testing "focus-id helper"
       (session/reset-session!)
       (is (nil? (session/focus-id))
           "Initial focus should be nil")

       (session/swap-session! assoc-in [:selection :focus] "focused-block")
       (is (= "focused-block" (session/focus-id))
           "focus-id should return updated value"))))

#?(:cljs
   (deftest session-db-sync
     (testing "Sync UI state from DB"
       (session/reset-session!)
       (let [test-db (-> (db/empty-db)
                         (assoc-in [:nodes const/session-ui-id :props]
                                   {:folded #{"fold-1" "fold-2"}
                                    :zoom-root "zoom-block"
                                    :current-page "page-1"
                                    :editing-block-id "edit-block"
                                    :cursor-position 5}))]

         (session-sync/sync-ui-from-db! test-db)

         (is (= #{"fold-1" "fold-2"} (session/folded))
             "Folded state should sync from DB")
         (is (= "zoom-block" (session/zoom-root))
             "Zoom root should sync from DB")
         (is (= "page-1" (session/current-page))
             "Current page should sync from DB")
         (is (= "edit-block" (session/editing-block-id))
             "Editing block ID should sync from DB")
         (is (= 5 (session/cursor-position))
             "Cursor position should sync from DB")))

     (testing "Sync selection state from DB"
       (session/reset-session!)
       (let [test-db (-> (db/empty-db)
                         (assoc-in [:nodes const/session-selection-id :props]
                                   {:nodes #{"a" "b" "c"}
                                    :focus "b"
                                    :anchor "a"}))]

         (session-sync/sync-ui-from-db! test-db)

         (is (= #{"a" "b" "c"} (session/selection-nodes))
             "Selection nodes should sync from DB")
         (is (= "b" (session/focus-id))
             "Focus should sync from DB")))

     (testing "Sync buffer state from DB"
       (session/reset-session!)
       (let [test-db (-> (db/empty-db)
                         (assoc-in [:nodes "session/buffer" :props]
                                   {:block-1 "some text"
                                    :block-2 "other text"}))]

         (session-sync/sync-buffer-from-db! test-db)

         (is (= {:block-1 "some text" :block-2 "other text"}
                (get-in (session/get-session) [:buffer]))
             "Buffer should sync from DB")))))

#?(:cljs
   (deftest session-independence
     (testing "Session state is independent of DB"
       (session/reset-session!)
       (let [test-db (db/empty-db)]

         ;; Modify session
         (session/swap-session! assoc-in [:ui :folded] #{"independent-fold"})
         (session/swap-session! assoc-in [:selection :nodes] #{"independent-select"})

         ;; DB should not be affected
         (is (= #{} (get-in test-db [:nodes const/session-ui-id :props :folded] #{}))
             "DB should not be affected by session changes")

         ;; Session should have changes
         (is (= #{"independent-fold"} (session/folded))
             "Session should maintain independent state")
         (is (= #{"independent-select"} (session/selection-nodes))
             "Session selection should be independent")))))

#?(:cljs
   (deftest session-swap-operations
     (testing "swap-session! updates state correctly"
       (session/reset-session!)

       (session/swap-session! assoc-in [:cursor :block-id] "new-block")
       (is (= "new-block" (get-in (session/get-session) [:cursor :block-id]))
           "swap-session! should update cursor block-id")

       (session/swap-session! update-in [:ui :folded] conj "fold-a")
       (is (contains? (session/folded) "fold-a")
           "swap-session! should add to folded set")

       (session/swap-session! update-in [:ui :folded] conj "fold-b")
       (is (= #{"fold-a" "fold-b"} (session/folded))
           "swap-session! should accumulate changes"))

     (testing "reset-session! clears all state"
       (session/swap-session! assoc-in [:ui :folded] #{"a" "b" "c"})
       (session/swap-session! assoc-in [:selection :nodes] #{"x" "y"})

       (session/reset-session!)

       (is (= #{} (session/folded))
           "reset-session! should clear folded")
       (is (= #{} (session/selection-nodes))
           "reset-session! should clear selection"))))

;; CLJ fallback tests (just verify namespace loads)
#?(:clj
   (deftest clj-placeholder
     (testing "Session tests are CLJS-only"
       (is true "Placeholder test for CLJ environment"))))
