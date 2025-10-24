# WASM Component Model: Architectural Analysis for Plugin System

**Date:** 2025-10-24
**Context:** Analysis of WASM Component Model applicability to our plugin/negotiation architecture
**Related:**
- ADR-negotiation-(wasm)-vs-plugins.md
- convex-components-research-20251024.md

## Executive Summary

The WASM Component Model provides **strong validation** for our negotiation architecture's core decisions while offering **three critical refinements**: deny-by-default capability grants, explicit composition phases, and capability versioning. However, we should **skip WASM as a plugin format** initially—the conceptual model is valuable, the binary format is overkill.

**Key Insight:** WASM Component Model is solving the same problem we are (safe composition of untrusted code with capability isolation) but for cross-language interop. Their solutions to isolation, versioning, and composition directly apply to our single-language case with lower overhead.

## Top 5 Architectural Insights

### 1. Deny-by-Default World Model (ADOPT IMMEDIATELY)

**WASM Pattern:**
```
WASI Virt: All APIs deny-by-default
Components must explicitly request capabilities at composition time
Virtual adapters intercept calls to unpermitted APIs
```

**Our Application:**
```clojure
;; Current ADR shows world as union of provides
(defonce world (atom #{:cap/tree :cap/order}))

;; WASM teaches us: Start EMPTY, grant explicitly
(defonce world (atom #{}))

(defn init-host-capabilities! []
  (grant! :cap/tree :host)
  (grant! :cap/order :host)
  (grant! :cap/time :host))

(defn grant! [capability source]
  (swap! world-registry
         (fn [w]
           (-> w
               (update :capabilities conj capability)
               (assoc-in [:grants capability]
                         {:granted-by source
                          :at (js/Date.)
                          :revocable? (not= source :host)})))))
```

**Why This Matters:**
- **Security:** Plugins can't accidentally access capabilities they don't need
- **Testability:** Tests start with empty world, grant only what's needed
- **Debuggability:** Trace exactly which plugin granted which capability
- **LLM repair:** Gap objects show "you need :cap/X, granted by plugin Y"

**Concrete Change to ADR:**
Replace "World = union of :provides from loaded modules" with "World starts empty, host grants baseline capabilities, plugins request additional capabilities at composition time."

### 2. Composition as Separate Phase (ADOPT IMMEDIATELY)

**WASM Pattern:**
```bash
# Registration (compile components)
wasm-tools component new module.wasm -o component.wasm

# Composition (wire imports to exports)
wac plug primary.wasm --plug dependency.wasm -o composed.wasm

# Validation happens at composition time, not runtime
```

**Our Application:**
```clojure
;; DON'T: Mix registration and activation
(defn register-plugin! [manifest]
  (validate-manifest! manifest)
  (swap! registry assoc (:id manifest) manifest)
  (swap! world conj (:provides manifest))  ; ← Too eager!
  (check-requirements! manifest))          ; ← Wrong phase!

;; DO: Separate registration, composition, activation
(defn register-plugin! [manifest]
  "Register plugin without activating. Returns :ok or validation error."
  (if-let [errors (validate-manifest manifest)]
    {:status :error :errors errors}
    (do (swap! registry assoc (:id manifest) manifest)
        {:status :ok :id (:id manifest)})))

(defn compose-plugins! [plugin-ids]
  "Validate all requirements can be satisfied. Returns composition or gap."
  (let [plugins (map @registry plugin-ids)
        all-requires (reduce set/union #{} (map :requires plugins))
        all-provides (reduce set/union @world (map :provides plugins))
        missing (set/difference all-requires all-provides)]
    (if (empty? missing)
      {:status :ok :plugins plugin-ids :provides all-provides}
      {:status :error
       :gap :unsatisfiable-composition
       :missing missing
       :available all-provides})))

(defn activate-composition! [composition]
  "Activate validated composition. Updates world and active plugins."
  (reset! active-plugins (:plugins composition))
  (swap! world set/union (:provides composition))
  {:status :activated :world @world})
```

**Why This Matters:**
- **Early validation:** Catch unsatisfiable dependencies before runtime
- **Atomic activation:** All-or-nothing plugin set activation
- **Testability:** Compose without activating, verify gaps in tests
- **Debuggability:** Clear separation between "registered" and "active"

**Concrete Change to ADR:**
Add "Composition Phase" between registration and evaluation:
1. Register plugins (validate manifests)
2. Compose plugin set (prove satisfiability)
3. Activate composition (update world, enable candidates)
4. Evaluate intents (select from active candidates)

### 3. Capability Versioning (ADOPT IMMEDIATELY)

**WASM Pattern:**
```wit
// Exact version matching
import docs:adder/add@0.1.0;

// Breaking changes = new interface
world calculator-v1 { import compute@0.1.0; }
world calculator-v2 { import compute@0.2.0; }  // Different contract
```

**Our Application:**
```clojure
;; Current ADR
{:provides #{:intent/structure.wrap}
 :requires #{:cap/tree :cap/order}}

;; WASM teaches: Version capabilities
{:provides #{:intent/structure.wrap.v1}
 :requires #{:cap/tree.v1 :cap/order.v1}}

;; Breaking change = new version
{:provides #{:intent/structure.wrap.v2}
 :requires #{:cap/tree.v2 :cap/order.v1}}  ; tree v2, order v1

;; Host can support multiple versions
(defn evaluate [db intent ctx]
  (let [candidates (find-candidates intent)]
    ;; Try newest version first, fallback to older
    (or (negotiate candidates :intent/structure.wrap.v2 ctx)
        (negotiate candidates :intent/structure.wrap.v1 ctx))))
```

**Why This Matters:**
- **Evolution:** Plugins can depend on old capability versions while new ones use v2
- **Migration:** Gradual upgrade path, no flag day
- **Compatibility:** Multiple plugins with different capability requirements coexist
- **Explainability:** Gap says "needs :cap/tree.v2, you have :cap/tree.v1"

**Concrete Change to ADR:**
- All capabilities MUST have version suffix (`:cap/tree.v1`)
- Breaking capability changes REQUIRE new version
- Manifests declare minimum version: `{:requires #{:cap/tree.v1+}}`
- World grants can satisfy version ranges or exact versions

### 4. Virtualization for Testing (CONSIDER FOR LATER)

**WASM Pattern:**
```
Test component with virtualized WASI environment
Mock filesystem, network, time without touching host
Virtual adapters intercept and record calls
```

**Our Application:**
```clojure
;; Virtual world for testing
(defn with-virtual-world [capabilities & {:keys [trace?]}]
  (let [world* (atom capabilities)
        trace* (when trace? (atom []))]
    (binding [*world* world*
              *trace* trace*]
      {:world world*
       :trace trace*
       :grant! (fn [cap]
                 (when trace* (swap! trace* conj {:op :grant :cap cap}))
                 (swap! world* conj cap))
       :revoke! (fn [cap]
                  (when trace* (swap! trace* conj {:op :revoke :cap cap}))
                  (swap! world* disj cap))})))

;; Test with controlled capabilities
(deftest test-plugin-requires-filesystem
  (let [{:keys [world grant!]} (with-virtual-world #{:cap/tree.v1})]
    (is (thrown? ExceptionInfo (negotiate db intent ctx)))

    ;; Grant capability, retry
    (grant! :cap/filesystem.v1)
    (is (= {:plan [...]} (negotiate db intent ctx)))))

;; Test capability revocation
(deftest test-capability-revocation
  (let [{:keys [world grant! revoke! trace]}
        (with-virtual-world #{} :trace? true)]
    (grant! :cap/tree.v1)
    (grant! :cap/order.v1)
    (revoke! :cap/tree.v1)

    (is (= [{:op :grant :cap :cap/tree.v1}
            {:op :grant :cap :cap/order.v1}
            {:op :revoke :cap :cap/tree.v1}]
           @trace))))
```

**Why This Matters:**
- **Isolation:** Test plugins without side effects
- **Mocking:** Simulate missing capabilities, test gap handling
- **Tracing:** Record capability grants/revocations for audit
- **Property testing:** Generate random capability sets, test laws

**Recommendation:** Don't implement immediately. Add when testing complexity justifies it.

### 5. Interface ≠ Implementation (CRITICAL INSIGHT)

**WASM Pattern:**
```wit
// Interface declares contract (what, not how)
interface add {
    add: func(x: u32, y: u32) -> u32;
}

// Multiple implementations possible
component adder-naive { export add; }
component adder-simd { export add; }
component adder-gpu { export add; }

// Composition picks ONE implementation
wac plug calculator.wasm --plug adder-simd.wasm
```

**Our Current ADR:**
```clojure
;; We conflate interface and implementation
{:provides #{:intent/structure.wrap}  ; ← This IS the interface
 :lower (fn [db intent] [...])}       ; ← This IS the implementation
```

**WASM Teaches Us:**
```clojure
;; Separate interface from implementation
;; Interface (declared by host or standard)
(def intent-registry
  {:intent/structure.wrap.v1
   {:schema [:map
             [:nodes [:vector :uuid]]
             [:parent :uuid]]
    :result [:vector [:enum :k/create :k/place :k/update]]}})

;; Implementation (provided by plugin)
(def plugin-manifest
  {:id :structure/wrap.basic
   :version "1.0.0"
   :implements #{:intent/structure.wrap.v1}  ; ← Implements interface
   :requires #{:cap/tree.v1 :cap/order.v1}
   :cost (fn [ctx] 3)
   :lower (fn [db intent] [...])})

;; Multiple implementations compete
(def alternative-plugin
  {:id :structure/wrap.optimized
   :implements #{:intent/structure.wrap.v1}  ; ← Same interface
   :requires #{:cap/tree.v2 :cap/simd.v1}    ; ← Different requirements
   :cost (fn [ctx] 1)                         ; ← Lower cost
   :lower (fn [db intent] [...])})            ; ← Different algorithm
```

**Why This Matters:**
- **Substitutability:** Any plugin implementing `:intent/X.v1` is valid
- **Optimization:** Choose fastest implementation available for context
- **Testing:** Mock implementations for tests
- **Standardization:** Host defines intent contracts, plugins compete to implement

**Concrete Change to ADR:**
- Add "Intent Registry": Host declares intent schemas and contracts
- Plugins `:implements` intents rather than `:provides` them
- Multiple plugins can implement same intent
- Negotiation selects best implementation for context

## Adopt vs. Skip vs. Consider-Later

### ADOPT IMMEDIATELY

1. **Deny-by-default world model** ✅
   - World starts empty
   - Host grants baseline capabilities
   - Plugins request capabilities at composition time
   - Track grants with provenance

2. **Composition as separate phase** ✅
   - Register → Compose → Activate
   - Validate satisfiability before activation
   - Atomic plugin set activation

3. **Capability versioning** ✅
   - All capabilities versioned: `:cap/tree.v1`
   - Breaking changes = new version
   - Version ranges in requirements

4. **Interface/implementation separation** ✅
   - Host declares intent contracts
   - Plugins implement intents
   - Multiple implementations compete

5. **Structured gap with delta** ✅
   - Return `:required`, `:available`, `:delta`
   - Include version mismatches
   - Machine-actionable for LLM

### SKIP (FOR NOW)

1. **WASM as plugin format** ❌
   - Overkill for Clojure-only
   - Adds compilation step
   - Loses REPL workflow
   - Decision: EDN manifests + Clojure fns

2. **Canonical ABI** ❌
   - Designed for cross-language interop
   - We have Clojure data structures
   - Malli schemas sufficient

3. **Binary distribution** ❌
   - Start with filesystem-based plugins
   - Load from `plugins/*/manifest.edn`
   - Reconsider if ecosystem grows

4. **Static analysis tooling** ❌
   - WASM has `wasm-tools validate`
   - We'll use Malli validation
   - Don't build separate toolchain

### CONSIDER FOR LATER

1. **Virtual world for testing** ⏸️
   - Valuable for complex plugin tests
   - Adds complexity now
   - Add when test isolation becomes pain point

2. **WASM plugins for performance** ⏸️
   - If critical path needs Rust/Zig
   - After Clojure plugin system stabilized
   - Treat as "native extension" option

3. **Capability hierarchies** ⏸️
   - WASM uses package namespacing
   - We could use `:cap.tree/order` vs `:cap.tree/index`
   - Start flat, add hierarchy if needed

4. **Plugin dependencies** ⏸️
   - WASM allows component sub-components
   - Convex disallows, requires consumer mediation
   - Start with Convex model, reconsider if strong need

5. **GUI composition tool** ⏸️
   - WASM has wasmbuilder.app
   - Not needed for solo dev
   - Could build for plugin marketplace later

## Design Mistakes to Avoid

### 1. Don't Conflate Registration and Composition

**WASM Mistake (avoided):**
They separate compilation from composition, but composition is still manual wiring.

**Our Risk:**
```clojure
;; BAD: Activate on registration
(defn register! [manifest]
  (swap! registry assoc (:id manifest) manifest)
  (swap! world conj (:provides manifest))  ; ← Too eager
  (check-requirements! manifest))          ; ← Wrong phase
```

**Solution:**
Registration validates manifest structure, composition validates dependencies, activation updates world. Three distinct phases.

### 2. Don't Allow Implicit Dependencies

**WASM Mistake (avoided):**
All imports must be explicitly satisfied at composition time. No "maybe it'll be there at runtime."

**Our Risk:**
```clojure
;; BAD: Check requirements at evaluation time
(defn negotiate [db intent ctx]
  (let [candidates (find-candidates intent)]
    (when-not (satisfied? (:requires chosen) @world)  ; ← Too late!
      (throw (ex-info "Missing capability" {...})))))
```

**Solution:**
Check all requirements at composition time. Evaluation assumes all active plugins are viable.

### 3. Don't Mix Versions Implicitly

**WASM Approach:**
Exact version matching: `import foo@0.1.0` won't match `export foo@0.2.0`

**Our Risk:**
```clojure
;; BAD: Unversioned capabilities match anything
{:requires #{:cap/tree}}  ; ← v1? v2? both?
```

**Solution:**
Always version capabilities. Support version ranges explicitly:
```clojure
{:requires #{:cap/tree.v1+}}  ; v1 or higher
{:requires #{:cap/tree.v2}}   ; exactly v2
```

### 4. Don't Share State Without Explicit Mediation

**WASM Approach:**
No shared memory between components. All state passed explicitly via function calls.

**Our Risk:**
```clojure
;; BAD: Plugins share mutable world
(defn plugin-a [db]
  (swap! global-cache assoc :foo "bar"))  ; ← Side effect!

(defn plugin-b [db]
  (get @global-cache :foo))  ; ← Implicit dependency!
```

**Solution:**
Plugins receive immutable `db` and `ctx`, return pure plans. All state mediated by host.

## World vs. Capability Model Comparison

### WASM Component Model

```
World = Set of Interfaces
├─ Interface A (export)
│  └─ func-1: (u32) -> u32
│  └─ func-2: (string) -> result<..., ...>
├─ Interface B (import)
└─ Interface C (import)

Composition: Wire exports to imports
Selection: First match (no optimization)
Failure: "Missing import X"
```

### Our Capability/Intent Model

```
World = Set of Capabilities
├─ :cap/tree.v1 (granted by host)
├─ :cap/order.v1 (granted by host)
└─ :cap/filesystem.v1 (granted by plugin-a)

Intent = Operation Request
├─ :intent/structure.wrap.v1
│  └─ Schema: [:map [:nodes [:vector :uuid]]]
└─ :intent/graph.backlink.v1

Evaluation: Filter viable, rank by cost, pick argmin
Selection: Multi-candidate with optimization
Failure: {:gap :missing-capability :delta #{...}}
```

### Key Differences

| Aspect | WASM | Ours |
|--------|------|------|
| **Granularity** | Function-level (fine) | Intent-level (coarse) |
| **Composition** | Explicit wiring (manual) | Declarative matching (auto) |
| **Selection** | First match | Ranked by cost |
| **Optimization** | None | Cost function + context |
| **Failure** | String error | Structured gap object |
| **Versioning** | Package@version | Capability.vN |
| **State** | No shared memory | No shared state |
| **Type safety** | Static (WIT) | Runtime (Malli) |

### What We Can Learn

1. **Versioning strategy:** Their `package@0.1.0` is cleaner than our `:cap.v1` suffix
2. **Interface contracts:** Separate schema definition from implementation
3. **Composition validation:** Prove satisfiability before activation
4. **Deny-by-default:** Start with empty world, grant explicitly

### What We Do Better

1. **Multi-candidate selection:** Cost-based optimization vs first-match
2. **Structured gaps:** Machine-actionable errors for LLM repair
3. **Runtime flexibility:** Choose implementation based on context (TUI vs Web)
4. **Simpler model:** Capability sets vs complex import graphs

## Reconsidering Our ADR

### Changes Recommended

1. **Add "Composition Phase" section:**
   ```
   ## Composition Phase

   Between registration and evaluation, plugins are composed into an active set:

   1. Register(manifest) → Validate structure, add to registry
   2. Compose([plugin-ids]) → Prove satisfiability, return composition or gap
   3. Activate(composition) → Update world, enable candidates
   4. Evaluate(intent) → Select from active candidates
   ```

2. **Update "World Registry" section:**
   ```
   ## World Registry (Deny-by-Default)

   World starts empty. Host grants baseline capabilities:

   (init-world!)
     → grant :cap/tree.v1 :host
     → grant :cap/order.v1 :host
     → grant :cap/time.v1 :host

   Plugins request capabilities at composition time.
   Composition fails if requirements unsatisfied.
   ```

3. **Add "Capability Versioning" section:**
   ```
   ## Capability Versioning

   All capabilities MUST be versioned:
   - Format: :cap/name.vN (e.g., :cap/tree.v1)
   - Breaking changes REQUIRE new version
   - Requirements support ranges: :cap/tree.v1+ (v1 or higher)
   - World grants exact versions
   ```

4. **Update "Manifest Shape" section:**
   ```
   ## Manifest Shape

   {:id          :plugin/structure-wrap
    :version     "1.0.0"
    :implements  #{:intent/structure.wrap.v1}  ; ← Changed from :provides
    :requires    #{:cap/tree.v1 :cap/order.v1}
    :schema      {:intent [...] :result [...]}
    :cost        (fn [ctx] 3)
    :lower       (fn [db intent] [...])}
   ```

5. **Add "Gap Types" section:**
   ```
   ## Gap Types

   When negotiation fails, return structured gap:

   {:gap :missing-capability
    :intent :intent/structure.wrap.v1
    :required #{:cap/tree.v2}
    :available #{:cap/tree.v1 :cap/order.v1}
    :delta #{:cap/tree.v2}
    :reason "Plugin requires :cap/tree.v2, world has :cap/tree.v1"}

   {:gap :unsatisfiable-composition
    :plugins [:plugin/a :plugin/b]
    :missing #{:cap/network.v1}
    :available #{:cap/tree.v1 :cap/order.v1}}
   ```

### Things That DON'T Need Changing

1. **Core negotiation algorithm:** Filter → rank → select still valid
2. **Pure handlers:** Functions return plans, don't mutate
3. **Transaction tracing:** Log chosen candidate + world hash
4. **Property tests:** Associativity, idempotence, derive==recompute

### New Risks to Test

1. **Composition circularity:** Plugin A requires cap from Plugin B, B requires cap from A
2. **Version conflicts:** Plugin A needs :cap/x.v1, Plugin B needs :cap/x.v2
3. **Grant ordering:** Does grant order affect composition?
4. **Revocation:** What happens if capability revoked while plugins active?

## Concrete Implementation Roadmap

### Phase 1: Minimal Viable (Week 1)

```clojure
;; 1. Capability versioning
(def world (atom #{}))

(defn grant! [cap]
  (when-not (versioned? cap)
    (throw (ex-info "Capabilities must be versioned" {:cap cap})))
  (swap! world conj cap))

;; 2. Registration without activation
(def registry (atom {}))

(defn register-plugin! [manifest]
  (validate-manifest! manifest)
  (swap! registry assoc (:id manifest) manifest)
  {:status :ok :id (:id manifest)})

;; 3. Composition phase
(defn compose-plugins! [plugin-ids]
  (let [plugins (map @registry plugin-ids)
        missing (find-missing-requirements plugins @world)]
    (if (empty? missing)
      {:status :ok :plugins plugin-ids}
      {:status :error :gap :unsatisfiable :missing missing})))

;; 4. Structured gaps
(defn negotiate [db intent ctx]
  (let [candidates (find-viable-candidates intent @world)]
    (if-let [chosen (select-best candidates ctx)]
      {:plan ((:lower chosen) db intent) :trace {...}}
      {:gap :missing-capability
       :required (find-requirements intent)
       :available @world
       :delta (set/difference (find-requirements intent) @world)})))
```

### Phase 2: Full Features (Week 2)

1. Add provenance tracking to grants
2. Add composition validation tests
3. Add version range support
4. Add interface registry (intent contracts)
5. Add gap explanation strings

### Phase 3: Advanced (Later)

1. Virtual world for testing
2. Capability hierarchies
3. Plugin dependency support
4. WASM plugin loader (if needed)

## Conclusion

**The WASM Component Model validates our core architecture** while providing three critical refinements:

1. **Deny-by-default world** prevents accidental capability leaks
2. **Composition phase** catches errors early, enables atomic activation
3. **Capability versioning** allows evolution without breaking changes

**We should NOT adopt WASM as a plugin format** but we SHOULD adopt their conceptual model for capability isolation, composition validation, and versioning.

**Recommended next step:** Update ADR-negotiation-(wasm)-vs-plugins.md with:
- Deny-by-default world model
- Explicit composition phase
- Capability versioning scheme
- Interface/implementation separation
- Structured gap types

Then implement Phase 1 (minimal viable) to validate the design with actual code.

## References

- [WASM Component Model Docs](https://component-model.bytecodealliance.org/)
- [WASI Virt](https://github.com/bytecodealliance/wasi-virt)
- [WIT Specification](https://component-model.bytecodealliance.org/design/wit.html)
- Our ADR: ADR-negotiation-(wasm)-vs-plugins.md
- Convex research: convex-components-research-20251024.md

## Changelog

- 2025-10-24: Initial analysis based on comprehensive WASM Component Model research
