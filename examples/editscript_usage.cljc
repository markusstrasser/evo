(ns examples.editscript-usage
  "Examples of using editscript for event sourcing in Evo.

  Editscript can generate EDN operations from state diffs - perfect for
  event sourcing where you want granular, auditable change logs."
  (:require [editscript.core :as es]
            [editscript.edit :as edit]))

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 1: Auto-generate operations from state changes
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  ;; Instead of manually crafting operations, let editscript diff the states

  (def old-db
    {:nodes {"a" {:type :block :props {:text "Hello"}}}
     :children-by-parent {:doc ["a"]}})

  (def new-db
    {:nodes {"a" {:type :block :props {:text "Hello World"}}
             "b" {:type :block :props {:text "New block"}}}
     :children-by-parent {:doc ["a" "b"]}})

  ;; Generate the diff
  (def script (es/diff old-db new-db))

  ;; View as EDN operations
  (es/get-edits script)
  ;; => [[:+ [:nodes "b"] {:type :block :props {:text "New block"}}]
  ;;     [:r [:nodes "a" :props :text] "Hello World"]
  ;;     [:+ [:children-by-parent :doc 1] "b"]]
  ;;
  ;; Legend:
  ;;   :+ = add
  ;;   :r = replace
  ;;   :- = delete

  ;; Apply the diff to old state
  (= new-db (es/patch old-db script))
  ;; => true

  ;; Get a compact representation for storage/network
  (pr-str (es/get-edits script))
  ;; Much smaller than sending full state!
  )

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 2: Use as audit log - see exactly what changed
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  (def before
    {:nodes {"block-1" {:type :block
                        :props {:text "Original text"
                                :collapsed false}}}})

  (def after
    {:nodes {"block-1" {:type :block
                        :props {:text "Edited text"
                                :collapsed true}}}})

  (def changes (es/diff before after))

  ;; Human-readable change log
  (es/get-edits changes)
  ;; => [[:r [:nodes "block-1" :props :text] "Edited text"]
  ;;     [:r [:nodes "block-1" :props :collapsed] true]]
  ;;
  ;; Perfect for:
  ;; - Undo/redo stacks
  ;; - Change history
  ;; - Network sync (send only changes)
  ;; - Debugging (see exact mutations)
  )

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 3: Integrate with Evo's event system
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  ;; Option A: Use editscript as a lower-level operation layer

  (defn capture-changes
    "Wrap a transaction and auto-generate editscript operations."
    [db f]
    (let [new-db (f db)
          diff-script (es/diff db new-db)]
      {:db new-db
       :changes (es/get-edits diff-script)
       :patch diff-script}))

  (defn apply-captured-changes
    "Replay changes from another session/user."
    [db {:keys [patch]}]
    (es/patch db patch))

  ;; Usage:
  (def result
    (capture-changes old-db
                     (fn [db]
                       (-> db
                           (assoc-in [:nodes "new"] {:type :block})
                           (update-in [:children-by-parent :doc] conj "new")))))

  (:changes result)
  ;; => [[:+ [:nodes "new"] {:type :block}]
  ;;     [:+ [:children-by-parent :doc 1] "new"]]

  ;; Send (:patch result) over network, apply on other client:
  (apply-captured-changes old-db result)
  ;; => new-db (same state)
  )

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 4: Efficient sync - only send what changed
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  (require '[cognitect.transit :as transit])

  ;; Server-side: Generate diff when DB changes
  (defn prepare-sync-payload [old-db new-db]
    (let [script (es/diff old-db new-db)]
      {:version (:version new-db)
       :patches (es/get-edits script)
       :size (count (pr-str (es/get-edits script)))}))

  (def sync-data (prepare-sync-payload old-db new-db))

  (:size sync-data)
  ;; => Much smaller than full DB!

  ;; Client-side: Apply patches
  (defn apply-sync [db {:keys [patches]}]
    (es/patch db (es/edits->script patches)))

  ;; Compare sizes:
  (let [full-db-size (count (pr-str new-db))
        patch-size (:size sync-data)]
    {:full-db-kb (/ full-db-size 1024)
     :patch-kb (/ patch-size 1024)
     :compression-ratio (float (/ full-db-size patch-size))})
  ;; With large DBs, patches can be 10-100x smaller!
  )

;; ═══════════════════════════════════════════════════════════════════════════
;; Example 5: Combine with Evo's operation primitives
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  ;; You don't have to replace Evo's ops - use editscript for specific use cases

  (defn editscript->evo-ops
    "Convert editscript patches to Evo's operation format.
    Useful for generated operations that need to go through kernel validation."
    [patches]
    (mapv (fn [[op path value]]
            (case op
              :+ {:op :create-or-update :path path :value value}
              :r {:op :update :path path :value value}
              :- {:op :delete :path path}))
          patches))

  (def es-patches (es/get-edits (es/diff old-db new-db)))
  (def evo-ops (editscript->evo-ops es-patches))

  ;; Now you can:
  ;; 1. Keep high-level intents (user-facing operations)
  ;; 2. Use editscript for low-level sync/replication
  ;; 3. Convert between formats as needed
  )

;; ═══════════════════════════════════════════════════════════════════════════
;; When to use editscript vs manual operations
;; ═══════════════════════════════════════════════════════════════════════════

(comment
  ;; ✅ USE EDITSCRIPT FOR:
  ;; - Network sync between clients
  ;; - Undo/redo (capture exact state diffs)
  ;; - Audit logs (see precise changes)
  ;; - Testing (compare expected vs actual state)
  ;; - Conflict resolution in multi-user scenarios

  ;; ✅ USE EVO'S MANUAL OPS FOR:
  ;; - User-facing operations with business logic
  ;; - Operations that need validation/normalization
  ;; - Complex multi-step transformations
  ;; - Operations with side effects (focus, scroll, etc.)

  ;; 💡 BEST OF BOTH WORLDS:
  ;; - User actions → Evo operations (semantic, validated)
  ;; - DB snapshots → Editscript diffs (efficient, granular)
  ;; - Network transport → Convert Evo ops to editscript patches
  )
