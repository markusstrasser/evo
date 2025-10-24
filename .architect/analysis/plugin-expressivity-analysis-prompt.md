# Architectural Analysis: Plugin Expressivity vs Knowledge Requirements

## Context

You are analyzing a tree-based editor with an event-sourced kernel. The system has evolved through several architectural decisions about how to handle plugins and extensions.

## Core System

**Three-operation kernel:**
```clojure
;; Canonical data (managed by kernel only)
{:nodes {"a" {:type :paragraph :props {:text "Hello"}}}
 :children-by-parent {"doc" ["a" "b"]}
 :roots #{:doc :trash}}
```

**Three core operations:**
1. `:create-node` - Create new node
2. `:place` - Move node to new parent/position
3. `:update-node` - Update node properties

**Pipeline:** normalize → validate → apply → derive

## Current Plugin Architecture

### Pattern 1: Derived Index Plugins (Read-Only Computation)

```clojure
;; Plugin computes derived data FROM canonical data
(defn derived [db]
  {:siblings {:child-order-of (compute-order (:children-by-parent db))}})

;; Result:
{:nodes {...}
 :children-by-parent {...}
 :derived {:siblings {:child-order-of {"parent" ["a" "b" "c"]}}}}
```

### Pattern 2: State Management Plugins (Own Namespace)

**Current approach (ADR-015):**
```clojure
;; Selection manages its own namespace at DB root
(defn select [db ids]
  (assoc db :selection {:nodes (set ids)
                        :focus (last ids)
                        :anchor (last ids)}))

;; Result:
{:nodes {...}
 :children-by-parent {...}
 :selection {:nodes #{:a :b} :focus :b :anchor :a}}
```

**Previous approach (ADR-012):**
```clojure
;; Selection as boolean property on nodes
{:nodes {"a" {:props {:text "Hello" :selected? true}}}}

;; Derived index computed from properties:
{:derived {:selection/active #{"a" "b"}}}
```

### Pattern 3: Structural Edit Plugins (Intent Compilation)

```clojure
;; Compiles high-level intent to core ops
(defmethod compile-intent :indent [db {:keys [id]}]
  [{:op :place :id id :under prev-sibling :at :last}])

;; Usage:
(interpret db (compile-intents db [{:type :indent :id "a"}]))
```

## API Split (ADR-016)

**Structural changes** → intent→ops→interpret:
- Creates/moves/updates nodes
- Requires validation (cycles, invariants)
- Updates derived indexes
- Example: indent, outdent, delete

**Annotations** → direct mutation:
- UI state, no structural impact
- No validation needed
- Example: selection, highlights, collapsed state

## The Core Question

**Does the current plugin architecture limit expressivity or require more knowledge than alternatives?**

### Example Comparison

**With namespaced state (current):**
```clojure
;; LLM-generated patch must know:
(-> db
    (assoc-in [:selection :nodes] #{:a :b})
    (assoc-in [:selection :focus] :b))
```

**With property annotations (previous):**
```clojure
;; LLM-generated patch could be more general:
(interpret db [{:op :update-node :id "a" :props {:selected? true}}
               {:op :update-node :id "b" :props {:selected? true}}])
```

### Questions to Consider

1. **Knowledge Surface Area:**
   - In both approaches, does an agent need to know what "selection" means and how it's used?
   - Is there a meaningful difference between knowing `:selection` namespace vs `:selected?` property?
   - Could a general "update-props" API reduce required knowledge, or would the agent still need domain knowledge?

2. **Composability:**
   - Does the property approach allow more generic operations (any property can be set)?
   - Does the namespace approach provide better isolation and consistency?
   - Which approach makes it easier to add new concerns without kernel changes?

3. **Expressivity:**
   - With properties: Agent can set ANY property on ANY node (maximum flexibility)
   - With namespaces: Agent must know specific namespace structure
   - Does this flexibility matter if agents need domain knowledge anyway?

4. **Minimum API Surface:**
   - Is there a way to have composable extensions without much domain knowledge?
   - Or is domain knowledge inherent to any useful system?
   - What's the practical minimum knowledge an agent needs?

5. **Evolution:**
   - The system evolved from properties → namespaced state
   - This change was made for consistency with undo/redo (ADR-015)
   - Was expressivity traded for architectural purity?

## Your Task

Analyze this architecture and answer:

1. **Does the current approach (namespaced state) limit expressivity compared to the property-based approach?**

2. **Does it require MORE domain knowledge from agents/LLMs that patch the system?**

3. **Is there a fundamental tradeoff between composability and required knowledge, or can we have both?**

4. **What is the minimum API surface area needed for a truly composable plugin system?**

5. **If you were designing this from scratch, what approach would maximize expressivity while minimizing required knowledge?**

## Architectural Constraints

- Solo developer, 80/20 focus, REPL-driven development
- Pure functions, explicit data flow (no dynamic vars)
- Event-sourced kernel with structural invariants (no cycles, referential integrity)
- Undo/redo must work correctly
- All state should be inspectable

## Evaluation Criteria

- **Expressivity:** Can agents add new concerns easily?
- **Knowledge requirement:** How much domain-specific knowledge is needed?
- **Composability:** Can concerns be added without kernel changes?
- **Simplicity:** Is the mental model clear?
- **Maintainability:** Can a solo developer reason about it?

Provide a structured analysis with concrete examples and architectural recommendations.
