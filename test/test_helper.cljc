(ns test-helper
  "Test harness and golden fixtures for refactor-proof integration tests.

   Provides:
   - demo-db: Standard page + blocks structure for testing
   - dispatch*: Convenience wrapper for api/dispatch
   - Assertion helpers for DB state validation"
  (:require [kernel.db :as DB]
            [kernel.transaction :as tx]
            [kernel.api :as api]
            [kernel.schema :as schema]
            [kernel.history :as H]
            [malli.core :as m]))

;; ── Golden Fixture ────────────────────────────────────────────────────────────

(defn demo-db
  "Create a demo database with page + 4 blocks structure.

   Structure:
   page
   ├─ a (text: 'Block A')
   ├─ b (text: 'Block B')
   ├─ c (text: 'Block C')
   └─ d (text: 'Block D')
       └─ d1 (nested under d)

   Returns DB with history initialized (can undo/redo).

   This is the golden fixture - changes to its shape should be intentional
   and reflected in tests across the refactor."
  []
  (-> (DB/empty-db)
      (tx/interpret [{:op :create-node :id "page" :type :page :props {:title "Test Page"}}
                     {:op :place :id "page" :under :doc :at :last}

                     {:op :create-node :id "a" :type :block :props {:text "Block A"}}
                     {:op :place :id "a" :under "page" :at :last}

                     {:op :create-node :id "b" :type :block :props {:text "Block B"}}
                     {:op :place :id "b" :under "page" :at :last}

                     {:op :create-node :id "c" :type :block :props {:text "Block C"}}
                     {:op :place :id "c" :under "page" :at :last}

                     {:op :create-node :id "d" :type :block :props {:text "Block D"}}
                     {:op :place :id "d" :under "page" :at :last}

                     {:op :create-node :id "d1" :type :block :props {:text "Nested under D"}}
                     {:op :place :id "d1" :under "d" :at :last}])
      :db
      (H/record)))

;; ── Dispatch Helper ───────────────────────────────────────────────────────────

(defn dispatch*
  "Thread api/dispatch through multiple intents, returning final DB.

   Usage:
     (dispatch* db
       {:type :select :ids \"a\"}
       {:type :navigate-down})

   Returns final DB (not result map). Throws if any dispatch produces issues."
  [db & intents]
  (reduce
   (fn [db* intent]
     (let [{:keys [db issues]} (api/dispatch db* intent)]
       (when (seq issues)
         (throw (ex-info "Dispatch produced issues"
                         {:intent intent
                          :issues issues})))
       db))
   db
   intents))

;; ── Validation Helpers ────────────────────────────────────────────────────────

(defn valid-db?
  "Check if DB conforms to kernel.schema/Db schema.
   Returns true if valid, throws ex-info with explanation if invalid."
  [db]
  (if (m/validate schema/Db db)
    true
    (let [explanation (m/explain schema/Db db)]
      (throw (ex-info "DB schema validation failed"
                      {:explanation explanation})))))

(defn derive-consistent?
  "Check if DB :derived matches fresh derivation from canonical state.
   Returns true if consistent, false otherwise."
  [db]
  (let [fresh-derived (:derived (DB/derive-indexes (dissoc db :derived)))]
    (= (:derived db) fresh-derived)))
