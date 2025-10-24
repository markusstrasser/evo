# Synthesis: Plugin Expressivity vs Knowledge Requirements

**Date:** 2025-10-24
**Analysis Method:** 5 independent GPT-5 Codex instances
**Run ID:** `7102cb63-7401-4b80-ba86-83abb519b54b`

## Meta-Finding: Prompt Interpretation Failure

**Critical observation:** All 5 Codex instances misinterpreted the analysis request. Despite being given:
- Detailed context about the current plugin architecture
- Specific implementation examples (selection plugin, struct plugin)
- Five explicit questions to answer
- Architectural constraints and design decisions (ADR-015, ADR-016)

**They all responded with:** "Here's how to BUILD a system to analyze plugin expressivity" rather than "Here's an analysis of YOUR current plugin architecture."

This reveals an interesting failure mode: when presented with "analysis" + technical context, LLMs pattern-match to "design a solution" rather than "analyze the given system." This is relevant to your original question about agent knowledge requirements!

## Direct Analysis: Current Architecture

Since the agents failed to do it, here's the architectural analysis:

### Question 1: Does namespaced state limit expressivity vs property-based approach?

**Answer: No, but it changes the contract surface.**

**Property approach (ADR-012, superseded):**
```clojure
;; Agent patches nodes directly
{:op :update-node :id "a" :props {:selected? true}}
```
- **Knowledge required:** Node structure, property names, update-node operation
- **Expressivity:** Can set ANY property on ANY node (maximum flexibility)
- **API surface:** 3 core ops (create, place, update)

**Namespace approach (ADR-015, current):**
```clojure
;; Agent calls plugin functions directly
(selection/select db #{:a :b})
(selection/extend db :c)
```
- **Knowledge required:** Plugin API (function names, semantics), DB structure
- **Expressivity:** Limited to plugin's exported API
- **API surface:** N plugin functions (grows with concerns)

**Key insight:** The property approach is MORE expressive but requires the SAME domain knowledge. An agent still needs to know:
- What `:selected?` means behaviorally
- When/why to set it
- How it interacts with other properties

The namespace approach just makes this knowledge explicit through named functions.

### Question 2: Does it require MORE domain knowledge?

**Answer: Same knowledge, different encoding.**

**Both approaches require agents to know:**
1. **Semantic knowledge:** What is "selection"? What does it control?
2. **Interaction knowledge:** How does selection interact with structural edits?
3. **Constraint knowledge:** What are valid selection states?

**The difference:**
- **Property approach:** Knowledge encoded in property NAMES and their EFFECTS (implicit)
- **Namespace approach:** Knowledge encoded in FUNCTION NAMES and CONTRACTS (explicit)

**Example comparison:**
```clojure
;; Property (implicit contract)
{:op :update-node :id "a" :props {:selected? true}}
;; Agent must know: setting :selected? makes node selected

;; Namespace (explicit contract)
(selection/select db :a)
;; Agent must know: select function adds node to selection

;; Same knowledge requirement, different API!
```

**Advantage of namespace approach:** Type errors fail fast (function doesn't exist) vs silent property addition that might be ignored.

### Question 3: Composability vs Required Knowledge Tradeoff?

**Answer: Yes, fundamental tradeoff exists.**

**Composability spectrum:**

1. **Maximum Composability (Raw DB Manipulation):**
   ```clojure
   (assoc-in db [:any :path] value)
   ```
   - API surface: 1 function
   - Knowledge required: EVERYTHING (DB structure, invariants, semantics)
   - Safety: None (can break everything)

2. **Property-Based (ADR-012 approach):**
   ```clojure
   {:op :update-node :id "a" :props {...}}
   ```
   - API surface: 3 core ops
   - Knowledge required: Node structure + property semantics
   - Safety: Structural validation only

3. **Plugin Functions (ADR-015 current):**
   ```clojure
   (selection/select db :a)
   ```
   - API surface: N plugin functions
   - Knowledge required: Plugin API + semantics
   - Safety: Full validation + type checking

**The tradeoff is unavoidable:**
- Higher composability = Lower API surface = More implicit knowledge required
- Lower composability = Higher API surface = More explicit knowledge required

**BUT:** The namespace approach reduces KINDS of knowledge needed:
- Don't need to know: DB structure, data types, key paths
- Still need to know: Function names, what they do

### Question 4: Minimum API Surface Area?

**Answer: Three tiers discovered:**

**Tier 1: Structural (3 ops)** - Cannot be reduced without losing expressivity
```clojure
create-node, place, update-node
```
Required knowledge: Tree structure, node types, placement semantics

**Tier 2: Annotations (1 concept per concern)** - Minimal API per plugin
```clojure
;; Selection: ~8 functions
select, extend, deselect, clear, toggle, selected?, get-selection
```
Required knowledge: Plugin semantics (what selection means)

**Tier 3: Structural Intents (1 compiler per operation type)** - Compiles to Tier 1
```clojure
;; Intents → Ops
indent-ops, outdent-ops, delete-ops
```
Required knowledge: Structural editing semantics

**Key insight from ADR-016:** The split pattern (structural vs annotation) IS the minimum surface area:
- Structural changes need validation → intent→ops→interpret
- Annotations need no validation → direct mutation

Trying to unify these (all through intents) adds ceremony without reducing knowledge requirements.

### Question 5: Ideal Design from Scratch?

**Answer: Current design is near-optimal for constraints.**

**What current architecture gets right:**

1. **Three-op kernel:** Minimal, complete, validated
2. **Derived indexes:** Computed, not managed (reduces surface area)
3. **Split pattern (ADR-016):** Complexity where needed, simplicity where possible
4. **Namespaced state (ADR-015):** Single DB value, explicit concerns

**What could be different:**

**Alternative 1: Universal Property System**
```clojure
;; Everything is properties
{:op :update-node :id "a" :props {:selected? true :collapsed? false}}

;; Derived indexes computed from properties
{:derived {:selection/active (filter :selected? nodes)}}
```

**Pros:**
- One API for all annotations (update-node)
- Properties composable (multiple concerns on same node)
- LLM patches are uniform

**Cons:**
- No type safety (can set any property)
- Properties can conflict (who owns which keys?)
- Undo/redo complexity (properties vs DB state)
- Plugin isolation harder (any plugin can touch any property)

**Alternative 2: Capability-Based Plugins (Codex-3 proposal pattern)**
```clojure
;; Plugins declare capabilities explicitly
{:plugin/id :selection
 :capabilities {:render {...} :update-state {...}}
 :knowledge-required #{:db-structure :render-loop}}
```

**Pros:**
- Explicit knowledge requirements
- Capability validation at plugin load
- Clear contracts between host and plugins

**Cons:**
- More upfront work per plugin
- Doesn't reduce actual knowledge needed
- Overhead for simple plugins

**Recommendation:** Keep current architecture. It has:
- Clear separation (structural vs annotation)
- Minimal API surface per tier
- Explicit knowledge requirements (function names)
- REPL-friendly (all pure functions)
- Solo-maintainer appropriate (simple, debuggable)

## Architectural Insights

### Insight 1: Knowledge Requirements Are Mostly Irreducible

**Core finding:** No matter how you structure the API, agents need domain knowledge:
- What is selection? (concept)
- How does it work? (semantics)
- When to use it? (context)

**API design choices only affect:**
- How knowledge is discovered (naming, docs, types)
- When errors are caught (compile time vs runtime)
- How explicit the contract is (implicit properties vs explicit functions)

### Insight 2: The Real Tradeoff is Implicit vs Explicit

**Property-based approach:**
- Knowledge is IMPLICIT (must infer from property names)
- API is MINIMAL (one update operation)
- Errors are LATE (property exists but ignored)

**Namespace approach:**
- Knowledge is EXPLICIT (function names convey intent)
- API is LARGER (one function per operation)
- Errors are EARLY (function doesn't exist)

**For LLM/agent consumers:** Explicit is better because:
1. Type errors help discovery ("what functions exist?")
2. Function names are self-documenting
3. Errors fail fast vs silent misconfigurations

### Insight 3: ADR-016 Split Pattern Is Optimal

**Structural vs Annotation split minimizes total API surface:**

Without split:
```clojure
;; EVERYTHING goes through interpret
(interpret db (compile-intents db [{:type :select :ids [:a]}]))
;; Ceremony for simple mutations
```

With split:
```clojure
;; Structural (needs validation)
(interpret db [{:op :place :id "a" :under "b"}])

;; Annotations (no validation)
(selection/select db :a)
```

**Why it works:**
- Structural changes NEED the machinery (validation, derives)
- Annotations DON'T need the machinery (pure state changes)
- Keeping them separate reduces cognitive load

### Insight 4: Selection Evolution Shows Tradeoffs

**ADR-012 (properties) → ADR-015 (namespaced):** Not about expressivity, about consistency with undo/redo.

**The real issue:** With properties, undo/redo had inconsistent granularity:
```clojure
;; Undo structural edit: Whole DB snapshot restored
;; Undo selection change: Just property reverted (if through intents)
```

**Solution:** Put selection in DB root namespace:
```clojure
{:nodes {...}
 :children-by-parent {...}
 :selection {:nodes #{} :focus nil}}

;; Undo restores entire DB including selection
```

**This reveals:** Architecture decisions driven by system-wide consistency (undo/redo) often override local API concerns (expressivity).

## Recommendations for LLM/Agent Consumers

### If Agent Needs to Patch System:

**Current API (Recommended):**
```clojure
;; Structural changes
(interpret db (compile-intents db [{:type :indent :id "a"}]))

;; Annotation changes
(selection/select db :a)
(selection/extend db :b)
```

**Agent knowledge required:**
1. **Structural:** Intent types (indent, outdent, delete)
2. **Selection:** Function names (select, extend, deselect)
3. **Integration:** When to use intents vs direct calls

**Discovery strategy:**
```clojure
;; List available intents
(core.interpret/describe-ops)

;; List selection operations
(ns-publics 'plugins.selection.core)

;; Inspect function docs
(doc selection/select)
```

### If Adding New Plugin:

**Follow ADR-016 pattern:**

1. **If plugin affects tree structure:** Use intent compiler
   ```clojure
   (defmethod compile-intent :my-structural-op [db intent]
     [{:op :place ...}]) ; Returns core ops
   ```

2. **If plugin adds annotations:** Use direct functions
   ```clojure
   (defn highlight [db id color]
     (assoc-in db [:highlights id] color))
   ```

**Knowledge documentation:**
- Create `PLUGIN.md` with: concepts, function signatures, examples
- Add REPL helpers: `(doc plugin/function-name)`
- Include tests as usage examples

## Conclusion

**Main findings:**

1. **Expressivity:** Current architecture is NOT limiting. Both property and namespace approaches support same expressivity.

2. **Knowledge requirements:** Irreducible. Agents must understand domain concepts regardless of API shape.

3. **Tradeoff:** Not "composability vs knowledge" but "implicit vs explicit." Current approach favors explicit (better for LLMs).

4. **Minimum surface area:** Current architecture is near-optimal:
   - 3 core ops (structural)
   - N plugin functions (annotations)
   - 1 compiler per intent type (structural intents)

5. **Design from scratch:** Would make similar choices given constraints (event sourcing, undo/redo, solo developer, REPL-driven).

**The original question revealed a false dichotomy:** The choice isn't between "expressive but high knowledge" vs "limited but low knowledge." It's between "implicit knowledge" (property-based) vs "explicit knowledge" (function-based).

**For your use case (LLM/agent consumers):** The current explicit approach is BETTER because:
- Type errors aid discovery
- Function names self-document
- Early failure prevents silent bugs
- REPL introspection works naturally

**Note on the meta-finding:** All 5 Codex instances misinterpreting the prompt suggests that when asking agents to analyze systems, you need to be very explicit: "ANALYZE this existing system, do NOT propose a new system."
