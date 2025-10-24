# Proposal: Unified Plugin Architecture via :update-meta Op

## Context: The Problem

We have a tree-based editor with an event-sourced kernel. The system currently has TWO pathways for state changes:

**Current Architecture (ADR-015, ADR-016):**

1. **Structural changes** (affect tree) → Intent → Ops → Interpret
   ```clojure
   ;; Three core operations (closed instruction set)
   {:op :create-node :id "a" :type :paragraph}
   {:op :place :id "a" :under "doc" :at :last}
   {:op :update-node :id "a" :props {:text "Hello"}}

   ;; Pipeline: normalize → validate → apply → derive
   (interpret db [{:op :place :id "a" :under "b"}])
   ```

2. **Annotation changes** (UI state) → Direct function calls
   ```clojure
   ;; No ops, no validation
   (selection/select db #{:a :b})
   (viewport/zoom db 1.5)
   ```

**The Split's Rationale (ADR-016):**
- Structural changes NEED validation (cycles, parent existence, derived indexes)
- Annotations DON'T need validation (just metadata)
- Keeping them separate avoids ceremony for simple mutations

**Current DB Shape:**
```clojure
{:nodes {"a" {:type :paragraph :props {:text "Hello"}}}
 :children-by-parent {"doc" ["a" "b"]}
 :roots #{:doc :trash}
 :derived {:parent-of {...} :index-of {...}}  ; Computed from above

 ;; Plugin-managed state at DB root (ADR-015)
 :selection {:nodes #{:a :b} :focus :b :anchor :a}
 :highlights {"a" "#ffff00"}
 :viewport {:zoom 1.0 :pan [0 0]}}
```

## The Problem: Bifurcated Intent Pipeline

**What we want:**
```clojure
;; Unified: all intents compile to ops
(interpret db (compile-intents db
  [{:type :indent :id "a"}      ; structural
   {:type :select :ids [:a]}]))  ; annotation
```

**What we have:**
```clojure
;; Bifurcated: caller must know which path
(let [db (interpret db (compile-intents db [{:type :indent :id "a"}]))]
  (selection/select db :a))  ; Direct call, not through interpret
```

**Why this is awkward:**
- Plugin authors need to know: "Does my intent compile to ops or not?"
- Event handlers need special logic: "Is this structural or annotation?"
- Can't mix structural + annotation intents in one transaction
- Loses uniformity of "everything is an intent"

## Attempted Solutions

### Attempt 1: Make Selection a Node (Rejected)
```clojure
;; Put selection in :nodes map?
{:nodes {"selection" {:type :meta :props {:selected #{:a :b}}}
         "a" {:type :paragraph}}}

;; Then use existing :update-node
{:op :update-node :id "selection" :props {:selected #{:a}}}
```

**Why rejected:**
- Selection isn't a node—it's metadata
- Where does it live in the tree? (no parent/children)
- Pollutes document structure with UI state
- Forces metadata into content model

### Attempt 2: compile-intent Returns Functions (Rejected)
```clojure
;; Structural intents return ops:
(defmethod compile-intent :indent [db intent]
  [{:op :place ...}])

;; Annotation intents return functions:
(defmethod compile-intent :select [db intent]
  (fn [db] (selection/select db ...)))

;; Then interpret handles both:
(interpret db (compile-intents db intents))
;; → Returns mix of ops and functions
```

**Why rejected:**
- Interpret now handles two types (ops + functions)
- Bifurcation moves INSIDE interpret instead of outside
- Not actually simpler

## Proposed Solution: Add :update-meta Op

### Core Idea
Extend the kernel with ONE new operation for plugin-managed state:

```clojure
;; Four operations, two categories:
STRUCTURAL (tree structure):
  :create-node, :place, :update-node

METADATA (plugin state):
  :update-meta
```

### The Operation
```clojure
;; core/ops.cljc
(defn update-meta
  "Update plugin-managed metadata at DB root.

   Validates:
     - Path must start with plugin-owned namespace
     - OR target namespace allows writes from this plugin"
  [db plugin-id path f]
  (let [target-ns (first path)]
    ;; Permission check (explained below)
    (validate-plugin-write! plugin-id target-ns)
    ;; Perform update
    (update-in db path f)))
```

### Intent Compilation (Now Unified)
```clojure
;; plugins/selection/core.cljc
(defmethod compile-intent :select [db {:keys [ids]}]
  [{:op :update-meta
    :plugin-id :selection
    :path [:selection :nodes]
    :f (fn [_] (set ids))}])

;; plugins/struct/core.cljc
(defmethod compile-intent :indent [db {:keys [id]}]
  [{:op :place
    :id id
    :under (prev-sibling db id)
    :at :last}])

;; NOW: Both return ops! Unified pipeline!
(interpret db (compile-intents db
  [{:type :indent :id "a"}
   {:type :select :ids [:a]}]))
```

## Plugin Permission System

### The Safety Problem
Without restrictions, `:update-meta` could break everything:

```clojure
;; Plugin could destroy kernel state:
{:op :update-meta
 :plugin-id :evil-plugin
 :path [:nodes "a"]  ; DANGEROUS! Bypasses validation!
 :f (fn [_] nil)}
```

### Solution: Plugin Manifests + Registry

**Plugin Manifest (EDN):**
```clojure
;; src/plugins/selection/manifest.edn
{:plugin/id :selection
 :plugin/version "0.1.0"

 ;; This plugin owns these DB root keys
 :plugin/owns [:selection]

 ;; Who can write to this plugin's state?
 :plugin/allow-write :everyone  ; or [:other-plugin-id]

 ;; Optional: Document read dependencies
 :plugin/reads [:nodes :children-by-parent :highlights]}
```

**Registry (at startup):**
```clojure
;; plugins/registry.cljc
(defonce *plugin-registry
  (atom {:selection {:owns [:selection] :allow-write :everyone}
         :highlights {:owns [:highlights] :allow-write [:selection]}
         :viewport {:owns [:viewport] :allow-write :everyone}}))

(defn owns-namespace? [plugin-id root-key]
  (contains? (get-in @*plugin-registry [plugin-id :owns]) root-key))

(defn can-write? [from-plugin to-namespace]
  (let [owner (find-owner to-namespace)
        allow-write (get-in @*plugin-registry [owner :allow-write])]
    (or (= allow-write :everyone)
        (contains? (set allow-write) from-plugin))))
```

**Validation in update-meta:**
```clojure
(defn update-meta [db plugin-id path f]
  (let [target-ns (first path)]
    ;; CRITICAL: Prevent touching kernel state
    (when (contains? #{:nodes :children-by-parent :roots :derived} target-ns)
      (throw (ex-info "Cannot use :update-meta for kernel state"
                      {:use-instead ":update-node, :place, :create-node"})))

    ;; Check plugin permission
    (when-not (or (owns-namespace? plugin-id target-ns)
                  (can-write? plugin-id target-ns))
      (throw (ex-info "Plugin cannot write to namespace"
                      {:plugin plugin-id :namespace target-ns})))

    ;; Safe to update
    (update-in db path f)))
```

## Permission Models (Start Permissive)

### Model 1: Fully Permissive (Default)
```clojure
;; For solo development: everyone can write everything
{:plugin/allow-write :everyone}
```
**Pros:** No friction during development, REPL-friendly
**Cons:** No isolation

### Model 2: Explicit Allowlist
```clojure
;; Selection plugin allows highlights plugin to modify it
{:plugin/id :selection
 :plugin/allow-write [:highlights-plugin :search-plugin]}
```
**Use case:** Highlights plugin needs to auto-select highlighted nodes

### Model 3: Plugin-Only (Strict)
```clojure
;; No other plugin can touch this state
{:plugin/allow-write []}  ; Empty = plugin-only
```
**Use case:** Internal plugin state that should never be externally modified

## Questions for Validation

### 1. Is :update-meta Worth the Kernel Extension?

**Tradeoff:**
- **Gain:** Unified intent pipeline (everything compiles to ops)
- **Cost:** Extend "closed 3-op instruction set" to 4 ops
- **Alternative:** Keep split (structural vs annotation APIs)

**Is the unification worth breaking the "3 ops only" purity?**

### 2. Permission System: Too Complex or Just Right?

**Our proposal:**
- Plugin manifests declare ownership
- Registry tracks permissions
- Runtime validation on every :update-meta

**Alternatives:**
- No permissions (just convention: don't touch others' namespaces)
- Type-based ownership (like Rust ECS)
- Full sandboxing (like VSCode extensions)

**For solo REPL-driven development, is manifest-based permissions the sweet spot?**

### 3. What About Plugin Paths Beyond Root?

**Current proposal:** Plugins own entire namespace at DB root
```clojure
{:selection {:nodes #{} :focus nil :ranges [...]}}
```

**Alternative:** Shared structure with plugin-specific subkeys
```clojure
{:meta {:selection {...} :highlights {...} :viewport {...}}}
```

**Which is cleaner? Or does it not matter?**

### 4. Edge Cases: Persistent UI State

Some UI state is actually structural (persists, syncs, exports):

**Examples:**
- **Collapsed state** in Roam/Logseq (persists across sessions)
- **Comments** in Figma (sync to other users)
- **Saved filters** (stored, shared)

**Should these use :update-meta or :update-node?**

**Guideline:**
- Use `:update-node` if: Affects document, syncs, exports, needs referential integrity
- Use `:update-meta` if: View-local, ephemeral, doesn't affect data model

**Is this distinction clear enough?**

### 5. Prior Art Comparison

**VSCode:** Isolated extension storage + explicit exports
**Rust Bevy:** Type-based resource ownership + compile-time checks
**Figma:** Sandboxed plugins + API-mediated access
**Web Components:** Shadow DOM + explicit properties/events

**Does our proposal (manifest-based ownership + runtime validation) fit this pattern well?**

### 6. Integration with Existing Plugins

**Current plugins that would use :update-meta:**
- `plugins/selection/core.cljc` (`:selection` namespace)
- Future: highlights, viewport, collapsed, zoom-stack

**Current plugins that stay structural:**
- `plugins/struct/core.cljc` (compiles to :place, :update-node)
- `plugins/siblings-order.cljc` (derives from `:children-by-parent`)

**Does this split make sense conceptually?**

## Implementation Plan

### Phase 1: Add :update-meta Op
1. Add `update-meta` function to `core/ops.cljc`
2. Add `:update-meta` case to `apply-op` in `core/interpret.cljc`
3. Initially: Skip permission checks (validate later)

### Phase 2: Add Plugin Manifests
1. Create `manifest.edn` for each plugin
2. Load manifests into `plugins/registry.cljc`
3. Add helper functions: `owns-namespace?`, `can-write?`

### Phase 3: Add Validation
1. Implement permission checks in `update-meta`
2. Hardcode kernel namespace protection
3. Add error messages for violations

### Phase 4: Convert Existing Plugins
1. Update `plugins/selection/core.cljc` to compile to `:update-meta`
2. Add selection intent methods to `plugins/struct/core.cljc`
3. Update event handlers to use unified `interpret` call

## Architectural Constraints (from CLAUDE.md)

- **Solo developer, 80/20 focus, REPL-driven development**
- **Pure functions, explicit data flow (no dynamic vars)**
- **Event-sourced kernel with structural invariants (no cycles, referential integrity)**
- **Undo/redo must work correctly**
- **All state should be inspectable**

## Your Task

Evaluate this proposal critically:

1. **Is :update-meta the right abstraction?** Or are we forcing unification where split is more honest?

2. **Is the permission system appropriate?** Too complex? Too simple? Right balance for solo REPL development?

3. **What are the hidden costs?** What problems will this create down the line?

4. **What are the alternatives we're missing?** Is there a simpler way to achieve unified intents?

5. **Edge cases and failure modes:** What breaks? What gets awkward? Where does this fall apart?

6. **Prior art insights:** What can we learn from VSCode/Rust/Figma? Are we reinventing wheels or missing patterns?

7. **Implementation complexity:** Is the juice worth the squeeze? Should we just keep the split?

Please provide:
- **Honest assessment** of the tradeoffs
- **Concrete examples** where this helps or hurts
- **Alternative approaches** we should consider
- **Red flags** to watch for during implementation
- **Recommendation:** Implement as-is, modify, or abandon?
