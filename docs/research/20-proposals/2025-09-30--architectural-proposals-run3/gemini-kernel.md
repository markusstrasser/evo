Here is an architectural proposal to simplify the 3-operation kernel.

***

### **Proposal: Unify `create` and `place` into a single `graft` operation.**

This proposal refactors the kernel's write operations to more closely match user intent, separating the act of *introducing* a new node from the act of *re-arranging* existing nodes.

#### 1. Core Idea

The current model requires two operations to add a new node to the tree: `create-node` to establish its existence, and `place` to give it a position. The `place` operation is complex, as its three-phase logic (remove, resolve, insert) is designed for moving an *existing* node, yet it's also used for the initial placement of a new one. This creates a semantic mismatch and unnecessary complexity.

This proposal introduces a new, single operation called `graft` that both creates a node and places it in the tree atomically. The existing `place` operation would be renamed to `move` to clarify its purpose, but its internal logic would remain the same. The kernel's instruction set would become `graft`, `move`, and `update`. This change makes the transaction log a clearer narrative of events: `graft` is for birth, `move` is for relocation, and `update` is for modification.

#### 2. Key Benefits

*   **Simplicity & Readability:** Operations become more declarative. A `graft` op clearly signifies "a new node is being added here," while a `move` op means "an existing node is changing position." This eliminates the ambiguity of the current `place` operation, which serves two different conceptual purposes. The transaction log becomes a more readable history of the tree's evolution.
*   **Debuggability:** When a transaction fails, the source of the error is more obvious. A failing `graft` is unambiguously related to node creation or initial placement (e.g., duplicate ID, invalid target parent). A failing `move` is strictly about relocation errors (e.g., cycle detected, node not found). This separation simplifies debugging by narrowing the scope of what can go wrong within a single operation.
*   **Expressiveness:** The kernel primitives map more directly to the fundamental intents of structural manipulation. Higher-level APIs like `core.struct` can compile their intents into a more semantic and less verbose sequence of operations. For example, adding a new child becomes a single `graft` instead of a `[:create-node, :place]` pair.
*   **Reduced State Space:** This change eliminates the intermediate state where a node is created but not yet placed in the tree (i.e., it has no parent). All nodes in the `:nodes` map (outside of `:trash`) will always have a corresponding entry in the `:children-by-parent` structure, strengthening system invariants.

#### 3. Implementation Sketch

The schemas and application logic would be adjusted as follows.

**A. New Operation Schemas (in `core.schema`)**

The `Op` schema would be changed from `[:or Op-Create Op-Place Op-Update]` to `[:or Op-Graft Op-Move Op-Update]`.

```clojure
;; New operation to create and place in one step
(def Op-Graft
  [:map
   [:op [:= :graft]]
   [:id Id]
   [:node Node] ; The full node data is included
   [:under Parent]
   [:at At]])

;; The old "place" is renamed to "move"
(def Op-Move
  [:map
   [:op [:= :move]]
   [:id Id]
   [:under Parent]
   [:at At]])

;; Update remains the same
(def Op-Update
  [:map [:op [:= :update-node]] [:id Id] [:props :map]])
```

**B. New Interpreter Logic (in `core.ops` and `core.interpret`)**

The `apply-op` multimethod would implement the new `:graft` case and rename the `:place` case to `:move`.

```clojure
;; Pseudocode for the new apply-op logic

(defmethod apply-op :graft [db {:keys [id node under at]}]
  ;; 1. CREATE PHASE
  ;; No need to check for existence if validation already did.
  (let [db-with-node (assoc-in db [:nodes id] node)]
    ;; 2. INSERT PHASE (Simplified "place", no "remove" step)
    (let [children (get-in db-with-node [:children-by-parent under] [])
          index    (resolve-anchor children at)]
      (assoc-in db-with-node [:children-by-parent under]
                (insert-at children index id)))))

(defmethod apply-op :move [db {:keys [id under at]}]
  ;; This implementation is identical to the current `place` op.
  ;; It performs the full remove -> resolve -> insert cycle.
  (let [db-removed (remove-child db id)
        children   (get-in db-removed [:children-by-parent under] [])
        index      (resolve-anchor children at {:moving-id id})]
    (assoc-in db-removed [:children-by-parent under]
              (insert-at children index id))))
```

**C. Validation Logic (`core.interpret`)**

Validation rules would be simpler and more targeted.

*   **`validate-op` for `:graft`:**
    *   `[:duplicate-create]` if `(:id op)` already exists in `:nodes`.
    *   `[:parent-not-found]` if `(:under op)` is not a valid parent.
    *   (No need for cycle detection, as a new node cannot create a cycle).
*   **`validate-op` for `:move`:**
    *   `[:node-not-found]` if `(:id op)` does not exist.
    *   `[:parent-not-found]` if `(:under op)` is not a valid parent.
    *   `[:cycle-detected]` using the existing `would-create-cycle?` logic.

#### 4. Tradeoffs and Risks

*   **Breaking Change:** This is a fundamental change to the kernel's API. All code that generates transactions, including the `core.struct` layer and all tests, would need to be updated to emit `graft` and `move` instead of `create-node` and `place`.
*   **Loss of "Homeless" Nodes:** The ability to create a node without immediately placing it is removed from the kernel's public API. While this is generally a simplification, it removes a pattern where one might pre-allocate a pool of nodes before deciding on their final structure. This seems like a minor loss for a significant gain in clarity.
*   **No Reduction in Op Count:** The proposal results in three operations, the same as the original kernel. The simplification comes from their semantic clarity and reduced internal complexity, not from a reduction in the size of the instruction set.

#### 5. How It Improves Developer Experience

*   **Interactive REPL Workflow:** Adding a new node becomes a single, intuitive command. Instead of constructing and interpreting a two-element vector `[{:op :create...} {:op :place...}]`, the developer can issue a single `{:op :graft ...}`. This makes interactive tree building and experimentation much faster and less error-prone.
*   **Clearer Debugging Stories:** When inspecting a failed transaction or a historical log, the developer's cognitive load is lower. A `graft` operation tells a simple story: "We tried to add a new node." A `move` tells a different one: "We tried to rearrange things." There is no longer a need to cross-reference a `place` operation with a preceding `create-node` to understand its true intent.
*   **More Focused Testing:** Tests can be written more cleanly. Tests for `graft` focus on the invariants of node creation. Tests for `move` focus on the complexities of structural rearrangement (e.g., cycle detection, anchor resolution during moves). This separation of concerns makes tests easier to write, read, and maintain.
