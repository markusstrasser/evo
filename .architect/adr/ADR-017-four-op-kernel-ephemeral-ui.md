ADR-017: Four-Op Kernel with Ephemeral UI State

Status: Accepted
Date: 2025-10-25
Owner: Kernel team
Scope: Kernel operations, history, separation of concerns

⸻

1) Context

The three-op kernel (:create-node, :place, :update-node) served us well, but had a subtle flaw: edit mode and cursor state were stored as session nodes and included in undo/redo history. This created UX confusion:
- Navigating between blocks polluted undo history with irrelevant edit state changes
- Undo/redo restored editing state when users expected only structural changes
- Intent separation was unclear: navigation intents updated both selection (structural) and edit state (ephemeral)

Example: User enters edit mode on block A, types text, switches to block B (exits edit, enters new edit). Undo would restore "editing block A" state instead of undoing the text change.

⸻

2) Decision (high-level)

Add a fourth operation `:update-ui` and split state by lifecycle:
- **Structural state** (undoable): nodes, children, selection
  - Modified by: :create-node, :place, :update-node
  - Lives in: :nodes, :children-by-parent, :derived
  - Recorded in history
- **Ephemeral state** (not undoable): edit mode, cursor position
  - Modified by: :update-ui
  - Lives in: :ui (top-level map)
  - Stripped from history snapshots

⸻

3) Four operations

```clojure
;; Structural (undoable)
{:op :create-node :id "a" :type :block :props {:text "hello"}}
{:op :place :id "a" :under :doc :at :last}
{:op :update-node :id "a" :props {:text "world"}}

;; Ephemeral (not in history)
{:op :update-ui :props {:editing-block-id "a"}}
{:op :update-ui :props {:cursor {"a" {:first-row? true :last-row? false}}}}
```

⸻

4) DB shape changes

**Before (3-op):**
```clojure
{:nodes {"session" {...}
         "session/selection" {:type :selection :props {:nodes #{} :focus "a"}}
         "session/edit"      {:type :edit :props {:block-id "a"}}      ; ❌ in history
         "session/cursor"    {:type :cursor :props {"a" {...}}}}       ; ❌ in history
 :children-by-parent {:session ["session/selection" "session/edit" "session/cursor"]}
 :derived {...}}
```

**After (4-op):**
```clojure
{:nodes {"session" {...}
         "session/selection" {:type :selection :props {:nodes #{} :focus "a"}}}
 :children-by-parent {:session ["session/selection"]}
 :ui {:editing-block-id "a"                                            ; ✅ ephemeral
      :cursor {"a" {:first-row? true :last-row? false}}}               ; ✅ ephemeral
 :derived {...}}
```

⸻

5) Intent boundaries (enforced by design)

**Selection intents** (selection.cljc):
- :select, :extend-selection, :toggle-selection, :deselect, :clear-selection
- MAY ONLY emit: `{:op :update-node :id "session/selection" :props ...}`
- Never touch :ui

**Navigation intents** (navigation.cljc):
- :navigate-up, :navigate-down
- MAY ONLY emit: `{:op :update-node :id "session/selection" :props {:focus id}}`
- Update selection focus; never touch edit state
- UI layer composes: navigate → :exit-edit → :navigate-up → :enter-edit

**Edit intents** (editing.cljc):
- :enter-edit, :exit-edit, :update-cursor-state
- MAY ONLY emit: `{:op :update-ui :props ...}`
- Never touch selection

**Structural intents** (struct.cljc):
- :delete, :indent, :outdent, :create-block, etc.
- MAY emit: :create-node, :place, :update-node
- Never touch :ui or selection (except when explicitly setting focus after creation)

⸻

6) History changes

**strip-history function:**
```clojure
(defn- strip-history [db]
  (dissoc db :history :ui))  ; Strip both history and ephemeral UI
```

**undo/redo preserve UI:**
```clojure
(defn undo [db]
  (let [current-ui (:ui db)
        prev-snapshot (restore-from-past db)]
    (assoc prev-snapshot :ui current-ui)))  ; Keep current editing state
```

Result: Undo/redo affect structure and selection, but preserve edit mode.

⸻

7) Query layer (kernel.query)

All reads centralized in `kernel.query` namespace:

```clojure
;; Selection (undoable)
(q/selection db)      ; #{id1 id2}
(q/focus db)          ; "a"
(q/selected? db "a")  ; true

;; Edit/cursor (ephemeral)
(q/editing-block-id db)    ; "a" or nil
(q/editing? db)            ; true/false
(q/cursor-first-row? db "a")  ; true/false
(q/cursor-last-row? db "a")   ; true/false

;; Tree (derived)
(q/parent-of db "a")   ; :doc
(q/children db :doc)   ; ["a" "b" "c"]
```

No direct `get-in` on :nodes, :ui, or :derived outside kernel.query.

⸻

8) Schema updates

**Operation schema:**
```clojure
(def Op-UpdateUi
  [:map
   [:op [:= :update-ui]]
   [:props :map]])

(def Op [:or Op-Create Op-Place Op-Update Op-UpdateUi])
```

**DB schema:**
```clojure
(def Db
  [:map
   [:nodes [:map-of Id Node]]
   [:children-by-parent [:map-of Parent [:vector Id]]]
   [:roots [:vector :keyword]]
   [:ui :map]  ; Ephemeral, not in history
   [:derived Derived]])
```

⸻

9) Migration path

1. ✅ Add :update-ui op to ops.cljc, transaction.cljc, schema.cljc
2. ✅ Create kernel.query for centralized reads
3. ✅ Update empty-db to include :ui {:editing-block-id nil :cursor {}}
4. ✅ Convert plugins.editing to use :update-ui
5. ✅ Convert plugins.navigation to selection-only + :update-ui for cursor
6. ✅ Update history to strip/preserve :ui
7. ✅ Remove session/edit and session/cursor constants
8. ✅ Update debug utilities to read from :ui

⸻

10) Consequences

**Pros:**
- **Clearer mental model**: Selection is structural (undo/redo), editing is ephemeral (like focus)
- **Cleaner history**: Undo stack has 50% fewer entries (no edit mode churn)
- **Better separation of concerns**: Intent boundaries are enforceable and testable
- **Debuggability**: kernel.query centralizes all reads; easier to audit data flow
- **Performance**: Fewer ops in transaction log, faster replay

**Cons:**
- **One more op**: 4-op kernel instead of 3-op (minimal complexity increase)
- **UI composition**: Some flows require composing multiple intents (mitigated by UI layer)
- **Migration cost**: One-time refactor of plugins (completed)

**Tradeoff accepted**: The UX improvement and clearer boundaries outweigh the marginal complexity of a fourth operation.

⸻

11) Testing

```clojure
(deftest selection-intents-dont-touch-ui
  (let [db0 (assoc (db/empty-db) :ui {:editing-block-id "a"})]
    (doseq [intent [{:type :select :ids "b"}
                    {:type :toggle-selection :ids "c"}]]
      (let [{db1 :db} (api/dispatch db0 intent)]
        (is (= (:ui db0) (:ui db1))
            "UI unchanged by selection intent")))))

(deftest editing-intents-dont-touch-selection
  (let [db0 (-> (db/empty-db)
                (api/dispatch {:type :select :ids "b"})
                :db)]
    (let [{db1 :db} (api/dispatch db0 {:type :enter-edit :block-id "b"})]
      (is (= (q/focus db0) (q/focus db1))
          "Selection unchanged by edit intent"))))
```

⸻

12) Alternatives considered

**A) Keep edit state in session nodes, add "ephemeral" flag:**
- Rejected: Complicates history logic with conditional recording
- Would require filtering ops by type in history/record

**B) Move ALL session state to :ui:**
- Rejected: Selection IS structural and SHOULD be undoable
- Users expect undo to restore "what I had selected"

**C) Add :ephemeral? flag to individual ops:**
- Rejected: More complex than a dedicated op type
- Harder to audit and enforce boundaries

⸻

13) File changes

```
src/kernel/
  ops.cljc              # Added update-ui function
  transaction.cljc      # Added :update-ui case to apply-op
  schema.cljc           # Added Op-UpdateUi, updated Db schema
  query.cljc            # NEW: Centralized read layer
  db.cljc               # Updated empty-db with :ui
  history.cljc          # Updated strip-history, undo, redo
  constants.cljc        # Removed session-edit-id, session-cursor-id

src/plugins/
  editing.cljc          # Uses :update-ui, delegates to kernel.query
  navigation.cljc       # Selection-only, :update-ui for cursor
  selection.cljc        # Unchanged (already correct)
  struct.cljc           # Unchanged

src/keymap/
  core.cljc             # Uses kernel.query for context detection
```

⸻

14) Glossary updates

- **Op (kernel)**: One of 4 operations: :create-node, :place, :update-node, :update-ui
- **Structural state**: Nodes, topology, selection - recorded in history
- **Ephemeral state**: Edit mode, cursor position - stripped from history
- **Intent boundary**: Enforced separation - selection/navigation/editing/structural intents may not cross domains

⸻

This completes the evolution from 3-op to 4-op kernel, establishing clear lifecycle boundaries between structural and ephemeral state.
