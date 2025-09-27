### ## The Core vs. Shell Architecture


[ User / LLM Action ]
(e.g., Keypress, API call)
|
v
.--------------------------------------------------------------------------.
|                             APPLICATION SHELL                            |
|                       (Manages UI, state, complex logic)                 |
|                                                                          |
|  1. [Event Handler]                                                      |
|     (Reads current state from the master @app-db atom)                   |
|                                |                                         |
|                                v                                         |
|  2. [High-Level Command Fn]    (A pure function: db -> transaction)      |
|     e.g., `(merge-block-up current-db node-id)`                          |
|                                |                                         |
|                                `-> Generates a list of primitive steps   |
|                                |                                         |
|                                v                                         |
|                       +----------------------+                           |
|                       | Transaction ([...])  |  <-- Data crosses boundary |
|                       | [[:patch..], [:del..]]|                           |
|                       +----------------------+                           |
|                                |                                         |
'--------------------------------|-----------------------------------------'
                                 |
                                 v
.--------------------------------|-----------------------------------------.
|                               CORE / KERNEL                               |
|                     (Dumb, solid, pure data engine)                       |
|                                |                                         |
|  3. interpret(store, core-db, transaction)                               |
|                                |                                         |
|                                v                                         |
|     (Reduces over the transaction, applying each primitive command)      |
|                                |                                         |
|                                v                                         |
|  4. [Algebra Protocol] methods dispatched                                |
|     (e.g., `(patch store db op)`, `(delete store db op)`)                 |
|                                |                                         |
|                                `-> Produces a new, immutable state       |
|                                |                                         |
|                                v                                         |
|                       +----------------------+                           |
|                       | new-core-db {...}    |  <-- Data returns          |
|                       +----------------------+                           |
|                                |                                         |
'--------------------------------|-----------------------------------------'
|
v
.--------------------------------|-----------------------------------------.
|                             APPLICATION SHELL                            |
|                                |                                         |
|  5. [State Update]                                                       |
|     `swap!` the master @app-db atom with the `new-core-db`.              |
|                                |                                         |
|                                v                                         |
|  6. [Render Cycle] (Triggered by atom change)                            |
|     `(render @app-db)`                                                   |
|                                |                                         |
|                                v                                         |
|                       +----------------------+                           |
|                       |        New UI        |                           |
|                       +----------------------+                           |
|                                                                          |
'--------------------------------------------------------------------------'

This proposal correctly intuits that your system has two parts. The code provided is the **Core**: a self-contained, pure library for manipulating a tree-graph data structure. It has no knowledge of UIs, selection, or any other application-specific concerns. The rest of your application—the part that manages view state, handles user input, and defines complex user-facing commands—is the **Shell**.

This architecture perfectly realizes your goal. The protocol defines the "4 or 5 Core ops" that are dumb and solid. Your "higher level ops" will be pure functions in the Shell that compose transactions using only these primitives.

Think of the Core protocol as a CPU's instruction set (`:ins`, `:mv`, `:patch` are like `MOV`, `ADD`, `JMP`). A higher-level command in your Shell, like `(merge-block-up db "node-X")`, acts like a compiler. It doesn't perform the change itself; it analyzes the `db` and emits a sequence of primitive machine code—a transaction—for the interpreter to execute, like `[[:patch {...}] [:del {...}]]`.

---

### ## Why This Model is a Significant Improvement

1.  **Pristine Schema:** Removing UI state (like `:selection` and `:collapsed`) from the core `db` is the most important insight. That state is ephemeral and belongs in the application Shell, managed in an atom that wraps this core `db`. The Core now only cares about the canonical truth of the data structure itself.

2.  **Formalized Algebra:** Using a `protocol` establishes a formal, explicit contract for the primitive operations. This is the "solid" foundation you wanted. The `case` statement in the interpreter is appropriate here because this set of core operations should be small and fixed. It's not meant to be arbitrarily extensible like the multimethod approach; its stability is a feature.

3.  **Composable Primitives:** It provides a clear path for extensibility. You don't add high-level operations by changing the Core. You add them in the Shell by writing pure functions that generate transactions. This makes higher-level features trivial to test: you just check if `(my-new-op db params)` produces the expected vector of commands.

**Adopt this model.** It gives you the "dumb and solid" core you need while providing a clean, scalable pattern for building extensible application features on top.


## proposal

I've slightly simplified your original `db` schema for the core library. The `:view` state (like `:selection` or `:collapsed`) is a concern of the UI shell, not the core data model, so it's been removed. The core should only manage the canonical truth.

-----

```clojure
(ns evolver.core
  (:require [malli.core :as m]
            [clojure.data :as data]
            [clojure.pprint :refer [pprint]]))

;;; =================================================================
;;; 1. SCHEMA - The Constitution
;;; =================================================================

(def db-schema
  [:map
   [:nodes [:map-of string? [:map [:type keyword?]]]]
   [:children-by-parent [:map-of string? [:vector string?]]]
   [:references [:map-of string? [:set string?]]]]) ; from-id -> #{to-id-1, to-id-2}

;;; =================================================================
;;; 2. PROTOCOL - The Core Algebra
;;; =================================================================
;; This defines the pure, host-agnostic API for manipulating your data.
;; All functions are pure: they take a `db` and return a new `db`.

(defprotocol ITreeGraphStore
  ;; Core Queries
  (node [this db node-id])
  (parent-of [this db node-id])
  (children-of [this db node-id])

  ;; Tree Mutations (The Algebra)
  (insert [this db op])
  (move [this db op])
  (patch [this db op])
  (delete [this db op])

  ;; Graph Mutations (The Algebra)
  (add-ref [this db op])
  (rm-ref [this db op]))

;;; =================================================================
;;; 3. IMPLEMENTATION - The Concrete Logic
;;; =================================================================
;; A concrete implementation of the algebra.

(defrecord InMemoryStore []
  ITreeGraphStore
  (node [this db node-id]
    (get-in db [:nodes node-id]))

  (parent-of [this db node-id]
    ;; ... implementation finds the parent in :children-by-parent ...
    db)

  (children-of [this db node-id]
    (get-in db [:children-by-parent node-id] []))

  (insert [this db {:keys [node-id parent-id node-data at]}]
    ;; ... implementation adds to :nodes and :children-by-parent ...
    db)

  (move [this db {:keys [node-id new-parent-id at]}]
    ;; ... implementation moves node-id in :children-by-parent ...
    db)

  (patch [this db {:keys [node-id updates]}]
    (update-in db [:nodes node-id] merge updates))

  (delete [this db {:keys [node-id promote-children?]}]
    ;; ... implementation removes from :nodes and handles children ...
    db)

  (add-ref [this db {:keys [from to]}]
    (update-in db [:references from] (fnil conj #{}) to))

  (rm-ref [this db {:keys [from to]}]
    (update-in db [:references from] (fnil disj #{}) to)))

;;; =================================================================
;;; 4. INTERPRETER - The Single Entry Point for Writes
;;; =================================================================
;; This is the only function that should ever be called to mutate state.
;; It ensures all operations are run through the same validation and logic pipeline.

(defn interpret
  "Takes a store instance, a db state, and a transaction (vector of commands).
   Returns the new db state."
  [store db transaction]
  (reduce
   (fn [current-db command]
     ;; Here you would add invariant checks (e.g., no cycles) before each op.
     (let [[op-type op-payload] command]
       (case op-type
         :ins     (insert store current-db op-payload)
         :mv      (move store current-db op-payload)
         :patch   (patch store current-db op-payload)
         :del     (delete store current-db op-payload)
         :add-ref (add-ref store current-db op-payload)
         :rm-ref  (rm-ref store current-db op-payload)
         ;; else
         (throw (ex-info "Unknown operation" {:command command})))))
   db
   transaction))

;;; =================================================================
;;; 5. WORKBENCH - Your REPL-Driven Test Environment
;;; =================================================================
;; Use this block to manually test and verify your logic from your editor.

(comment

  ;; a. Create an instance of your store implementation
  (def my-store (->InMemoryStore))

  ;; b. Define an initial state
  (def db-before
    {:nodes            {"root" {:type :root} "a" {:type :p} "b" {:type :p}}
     :children-by-parent {"root" ["a" "b"]}
     :references       {}})

  ;; c. Define a transaction (a sequence of commands)
  (def my-tx
    [[:patch {:node-id "a" :updates {:text "Hello"}}]
     [:add-ref {:from "b" :to "a"}]])

  ;; d. Run the transaction through the interpreter
  (def db-after (interpret my-store db-before my-tx))

  ;; e. Verify the result
  (let [[removed added] (data/diff db-before db-after)]
    (println "--- REMOVED ---")
    (pprint removed)
    (println "\n--- ADDED ---")
    (pprint added))

  )
```