# Component Systems Research: Synthesis & Recommendations

**Date:** 2025-10-24
**Scope:** Synthesis of Convex Components and WASM Component Model research
**Related:**
- convex-components-research-20251024.md
- wasm-component-model-analysis-20251024.md
- ADR-negotiation-(wasm)-vs-plugins.md

## The Pattern Across Both Systems

Both Convex and WASM Component Model independently arrived at the same architectural patterns:

```
┌─────────────────────────────────────────────────────────────┐
│                  UNIVERSAL COMPONENT PATTERN                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. Manifest-Driven Declaration                            │
│     ├─ WASM: WIT files define imports/exports              │
│     ├─ Convex: convex.config.ts exports defineComponent()  │
│     └─ Ours: EDN manifests with :requires/:provides        │
│                                                             │
│  2. Consumer-Mediated Composition                          │
│     ├─ WASM: `wac plug` wires imports to exports           │
│     ├─ Convex: Parent app explicitly orchestrates          │
│     └─ Ours: Host evaluates (negotiate db intent ctx)      │
│                                                             │
│  3. Strong Isolation                                       │
│     ├─ WASM: No shared memory between components           │
│     ├─ Convex: Sandboxed DB tables, independent state      │
│     └─ Ours: Plugins receive immutable db, return plans    │
│                                                             │
│  4. Explicit Capability Grants                             │
│     ├─ WASM: WASI Virt deny-by-default APIs                │
│     ├─ Convex: Can't access parent data without passing    │
│     └─ Ours: Deny-by-default world, explicit grants        │
│                                                             │
│  5. Type-Safe Boundaries                                   │
│     ├─ WASM: Canonical ABI with lifting/lowering           │
│     ├─ Convex: Runtime validators + TypeScript codegen     │
│     └─ Ours: Malli schemas validate intents/plans          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Implication:** This pattern emerges naturally when solving "safe composition of untrusted code." We're not inventing something new—we're applying battle-tested patterns.

## Unique Contributions from Each System

### WASM Teaches Us

1. **Deny-by-default is non-negotiable**
   - Start with empty capability set
   - Explicitly grant each capability
   - Track provenance (who granted what, when)

2. **Composition is a separate phase**
   - Registration ≠ activation
   - Validate dependency satisfaction before going live
   - All-or-nothing plugin set activation

3. **Versioning prevents breakage**
   - All interfaces versioned
   - Breaking changes = new version
   - Multiple versions coexist

4. **Virtualization enables testing**
   - Mock capabilities without side effects
   - Trace capability grants/revocations
   - Isolate tests from host environment

### Convex Teaches Us

1. **Consumer in the middle is intentional**
   - No direct plugin-to-plugin calls
   - Prevents hidden dependencies
   - Enables deterministic replay

2. **Transactional guarantees matter**
   - Failed operations roll back completely
   - No partial state from errors
   - Atomic multi-plugin operations

3. **Named instances enable flexibility**
   - Same plugin, multiple configurations
   - E.g., different workpool sizes
   - Runtime context determines which instance

4. **NPM as distribution is pragmatic**
   - Centralized discovery
   - Versioned dependencies
   - Standard tooling (package.json)

### What We Add

1. **Multi-candidate selection**
   - Neither WASM nor Convex optimizes across providers
   - We rank by cost function
   - Context-dependent selection (TUI vs Web vs Native)

2. **Structured gaps for LLM repair**
   - WASM: "Missing import X" (string error)
   - Convex: Exception thrown
   - Ours: `{:gap :missing-capability :delta #{...}}`

3. **Intent-level abstraction**
   - WASM: Function-level imports/exports
   - Convex: Component-level APIs
   - Ours: Intent-level with multiple competing lowerings

## Concrete Recommendations for Our ADR

### ADOPT (High Priority)

#### 1. Three-Phase Lifecycle

```clojure
;; Phase 1: REGISTRATION (validate manifest, store in registry)
(defn register-plugin! [manifest]
  (validate-manifest! manifest)  ; Schema, version format, etc.
  (swap! registry assoc (:id manifest) manifest)
  {:status :registered :id (:id manifest)})

;; Phase 2: COMPOSITION (prove satisfiability, return composition or gap)
(defn compose-plugins! [plugin-ids]
  (let [plugins (map @registry plugin-ids)
        all-requires (reduce set/union #{} (map :requires plugins))
        all-provides (reduce set/union @world (map :provides plugins))
        missing (set/difference all-requires all-provides)]
    (if (empty? missing)
      {:status :composable :plugins plugin-ids :provides all-provides}
      {:status :error
       :gap :unsatisfiable-composition
       :missing missing
       :available all-provides})))

;; Phase 3: ACTIVATION (atomic update of world and active set)
(defn activate-composition! [composition]
  (assert (= (:status composition) :composable))
  (swap! world set/union (:provides composition))
  (reset! active-plugins (:plugins composition))
  {:status :active :world @world :plugins @active-plugins})
```

**Rationale:** WASM separates compilation from composition, Convex separates registration from usage. Both validate dependencies early. We should too.

#### 2. Deny-by-Default World

```clojure
;; Start with EMPTY world
(defonce world-registry
  (atom {:capabilities #{}
         :grants {}}))

;; Host explicitly grants baseline capabilities
(defn init-host-capabilities! []
  (grant! :cap/tree.v1 :host)
  (grant! :cap/order.v1 :host)
  (grant! :cap/time.v1 :host))

;; Track who granted what, when, why
(defn grant! [capability source & {:keys [revocable? reason]}]
  (swap! world-registry
         (fn [w]
           (-> w
               (update :capabilities conj capability)
               (assoc-in [:grants capability]
                         {:granted-by source
                          :at (js/Date.)
                          :revocable? (boolean revocable?)
                          :reason reason})))))
```

**Rationale:** WASI Virt proves deny-by-default catches bugs and prevents accidental capability leaks. Explicit grants provide audit trail.

#### 3. Capability Versioning

```clojure
;; All capabilities MUST be versioned
(s/def ::capability
  (s/and keyword?
         #(re-matches #":cap/[a-z-]+\.v\d+" (str %))))

;; Examples
:cap/tree.v1        ; ✓ Valid
:cap/order.v2       ; ✓ Valid
:cap/tree           ; ✗ Invalid (missing version)

;; Manifests declare requirements
{:id :plugin/structure-wrap
 :requires #{:cap/tree.v1 :cap/order.v1}}

;; Breaking change = new version
{:id :plugin/structure-wrap-optimized
 :requires #{:cap/tree.v2 :cap/order.v1}}  ; tree v2, order v1
```

**Rationale:** Both WASM (`@0.1.0`) and Convex (package versions) version interfaces. Allows evolution without breaking existing plugins.

#### 4. Structured Gaps with Delta

```clojure
;; When negotiation fails, return actionable gap
(defn negotiate [db intent ctx]
  (let [candidates (find-viable-candidates intent @world)]
    (if-let [chosen (select-best candidates ctx)]
      {:plan ((:lower chosen) db intent)
       :trace {:candidate (:id chosen)
               :world-hash (hash @world)
               :ctx-fingerprint (select-keys ctx [:mode :platform])}}
      {:gap :missing-capability
       :intent (:intent intent)
       :required (find-all-requirements intent)
       :available (get-in @world-registry [:capabilities])
       :delta (set/difference (find-all-requirements intent)
                              (get-in @world-registry [:capabilities]))
       :explanation (generate-gap-explanation intent @world-registry)})))

;; Example gap
{:gap :missing-capability
 :intent :intent/structure.wrap.v1
 :required #{:cap/tree.v2}
 :available #{:cap/tree.v1 :cap/order.v1}
 :delta #{:cap/tree.v2}
 :explanation "Plugin requires :cap/tree.v2, but world only has :cap/tree.v1.
               Either upgrade :cap/tree to v2 or use a different plugin."}
```

**Rationale:** Neither WASM nor Convex provides machine-actionable error details. We can do better for LLM repair.

#### 5. Interface/Implementation Separation

```clojure
;; Host declares intent contracts (the interface)
(def intent-registry
  {:intent/structure.wrap.v1
   {:description "Wrap nodes in a new parent"
    :schema {:intent [:map
                      [:nodes [:vector :uuid]]
                      [:parent {:optional true} :uuid]]
             :result [:vector [:enum :op/create :op/place :op/update]]}
    :laws [(fn [db intent plan]
             ;; Verify plan preserves order
             ...)]}})

;; Plugins declare implementations
{:id :plugin/wrap-basic
 :implements #{:intent/structure.wrap.v1}  ; ← Implements interface
 :requires #{:cap/tree.v1 :cap/order.v1}
 :cost (fn [ctx] 3)
 :lower (fn [db intent] [...])}

{:id :plugin/wrap-optimized
 :implements #{:intent/structure.wrap.v1}  ; ← Same interface
 :requires #{:cap/tree.v2 :cap/simd.v1}
 :cost (fn [ctx] 1)                        ; ← Lower cost
 :lower (fn [db intent] [...])}            ; ← Different algorithm
```

**Rationale:** WASM separates interface (WIT) from implementation (component). This enables multiple competing implementations and substitutability.

### CONSIDER FOR LATER (Medium Priority)

#### 6. Virtual World for Testing

```clojure
(defn with-virtual-world [capabilities & {:keys [trace?]}]
  (let [world* (atom capabilities)
        trace* (when trace? (atom []))]
    (binding [*world* world*]
      {:world world*
       :trace trace*
       :grant! (fn [cap] ...)
       :revoke! (fn [cap] ...)})))

(deftest test-missing-capability
  (let [{:keys [world grant!]} (with-virtual-world #{:cap/tree.v1})]
    ;; Missing :cap/order.v1
    (is (thrown? ExceptionInfo (negotiate db intent ctx)))

    ;; Grant and retry
    (grant! :cap/order.v1)
    (is (= {:plan [...]} (negotiate db intent ctx)))))
```

**Rationale:** WASI Virt shows virtualization enables powerful testing patterns. Add when test complexity justifies overhead.

#### 7. Capability Provenance Tracking

```clojure
;; Track which plugin granted which capability
(defn register-with-grants! [manifest]
  (register-plugin! manifest)
  (doseq [cap (:provides manifest)]
    (grant! cap (:id manifest) :revocable? true :reason "Plugin provides")))

;; Query: What capabilities does this plugin grant?
(defn capabilities-from-plugin [plugin-id]
  (filter (fn [[cap grant]]
            (= (:granted-by grant) plugin-id))
          (:grants @world-registry)))

;; Revoke on plugin removal
(defn deactivate-plugin! [plugin-id]
  (doseq [[cap grant] (capabilities-from-plugin plugin-id)]
    (when (:revocable? grant)
      (revoke! cap))))
```

**Rationale:** Useful for debugging "where did this capability come from?" Add when plugin ecosystem grows.

### SKIP (Low Priority / YAGNI)

#### 8. WASM as Plugin Format

**Don't:** Compile Clojure plugins to WASM

**Rationale:**
- Adds compilation step
- Loses REPL workflow
- Overkill for single-language system
- Decision: EDN manifests + Clojure functions

**Reconsider if:** Need Rust/Zig for performance-critical plugins (e.g., SIMD text processing)

#### 9. Binary Distribution

**Don't:** Build plugin registry, package manager, binary formats

**Rationale:**
- Filesystem-based plugins simpler
- `plugins/*/manifest.edn` works fine
- No versioning conflicts with one developer

**Reconsider if:** Plugin ecosystem grows beyond local filesystem

#### 10. GUI Composition Tool

**Don't:** Build visual plugin composition tool (like wasmbuilder.app)

**Rationale:**
- Solo dev doesn't need GUI
- EDN config sufficient
- AI can compose plugins programmatically

**Reconsider if:** Sharing plugins with non-technical users

## Updated Manifest Schema

```clojure
;; Plugin manifest (EDN file)
{:id          :plugin/structure-wrap
 :version     "1.0.0"
 :description "Wrap nodes in a new parent"

 ;; Interface contract
 :implements  #{:intent/structure.wrap.v1}

 ;; Capability requirements (versioned)
 :requires    #{:cap/tree.v1 :cap/order.v1}

 ;; Optional: capabilities this plugin provides
 :provides    #{:cap/structure.advanced.v1}

 ;; Validation schemas
 :schema      {:intent [:map
                        [:nodes [:vector :uuid]]
                        [:parent {:optional true} :uuid]]
               :result [:vector [:enum :op/create :op/place :op/update]]}

 ;; Cost function (pure, deterministic)
 :cost        (fn [ctx]
                (if (= (:platform ctx) :web) 3 1))

 ;; Lowering function (pure, returns plan)
 :lower       (fn [db intent]
                [{:op :k/create ...}
                 {:op :k/place ...}])}
```

## Minimal Implementation Checklist

### Week 1: Core Infrastructure

- [ ] Define `::capability` spec (versioned keyword)
- [ ] Implement `world-registry` atom with grants map
- [ ] Implement `grant!` with provenance tracking
- [ ] Implement `register-plugin!` (validation only)
- [ ] Implement `compose-plugins!` (satisfiability check)
- [ ] Implement `activate-composition!` (atomic world update)
- [ ] Write property tests for composition associativity

### Week 2: Negotiation

- [ ] Implement `find-viable-candidates` (filter by world)
- [ ] Implement `select-best` (rank by cost)
- [ ] Implement `negotiate` (with structured gaps)
- [ ] Define gap types: `:missing-capability`, `:unsatisfiable-composition`, `:version-conflict`
- [ ] Write gap explanation generator
- [ ] Write tests for multi-candidate selection

### Week 3: Integration

- [ ] Define intent registry (contracts for structure/graph operations)
- [ ] Migrate existing intent handlers to plugin manifests
- [ ] Update `interpret` to use `negotiate` instead of direct dispatch
- [ ] Add tracing for chosen candidate + world hash
- [ ] Write end-to-end tests for plugin lifecycle
- [ ] Document plugin authoring guide

## Success Criteria

Our plugin architecture will be successful if:

1. **Explainable:** "Why was this handler chosen?" has a clear answer
2. **Safe:** Plugins can't access capabilities they don't declare
3. **Testable:** Can write property tests for composition laws
4. **Debuggable:** Clear separation of registration/composition/activation
5. **Evolvable:** Can add new capabilities without breaking existing plugins
6. **Simple:** EDN manifests + Clojure functions, no complex toolchain

## Failure Modes to Watch For

1. **Over-engineering:** Adding features "because WASM has them"
   - **Mitigation:** Every feature must solve a current problem
   - **Test:** "Could we ship without this?"

2. **Capability explosion:** Too many fine-grained capabilities
   - **Mitigation:** Start with coarse capabilities (`:cap/tree`, not `:cap/tree.read` + `:cap/tree.write`)
   - **Test:** "Does this capability represent a meaningful security boundary?"

3. **Hidden dependencies:** Plugins that work locally but fail in composition
   - **Mitigation:** Composition phase validates all requirements
   - **Test:** Property test that all active plugins are viable

4. **Version conflict hell:** Plugin A needs v1, Plugin B needs v2, can't activate both
   - **Mitigation:** Support multiple capability versions in world
   - **Test:** "Can we run old and new plugins simultaneously?"

## Conclusion

Both Convex and WASM Component Model independently validate our core architecture:

- ✅ **Consumer-mediated composition** prevents hidden dependencies
- ✅ **Capability-based isolation** enforces security boundaries
- ✅ **Manifest-driven registration** enables static analysis
- ✅ **Type-safe boundaries** catch errors early

They teach us three refinements we should adopt immediately:

1. **Deny-by-default world** (from WASI Virt)
2. **Composition as separate phase** (from WASM + Convex)
3. **Capability versioning** (from both)

Our contribution—**multi-candidate selection with cost-based optimization and structured gaps**—fills a gap neither system addresses.

**Next action:** Update ADR-negotiation-(wasm)-vs-plugins.md with these findings and implement Phase 1 checklist.

## References

- convex-components-research-20251024.md
- wasm-component-model-analysis-20251024.md
- ADR-negotiation-(wasm)-vs-plugins.md
- [Convex Components](https://docs.convex.dev/components)
- [WASM Component Model](https://component-model.bytecodealliance.org/)
- [WASI Virt](https://github.com/bytecodealliance/wasi-virt)
