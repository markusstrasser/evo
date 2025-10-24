# Component Architecture Research Index

**Date:** 2025-10-24
**Status:** Complete
**Objective:** Research Convex Components and WASM Component Model for insights applicable to our plugin/negotiation architecture

## Documents Created

### 1. convex-components-research-20251024.md
**Scope:** Deep dive into Convex's component architecture
**Key Findings:**
- Manifest = Config Export Pattern (`convex.config.ts`)
- Consumer-mediated composition (no direct component-to-component calls)
- Strong isolation (separate DB tables, file storage per component)
- Transactional guarantees across component boundaries
- NPM-based distribution with type generation

**Most Valuable Insight:** Consumer in the middle is intentional, not a limitation—prevents hidden dependencies and enables deterministic replay.

### 2. wasm-component-model-analysis-20251024.md
**Scope:** Comprehensive analysis of WASM Component Model
**Key Findings:**
- Deny-by-default capability model (WASI Virt)
- Composition as separate phase (registration → composition → activation)
- Interface versioning (`package@0.1.0` syntax)
- Virtualization for testing (mock capabilities without side effects)
- Canonical ABI for cross-language interop

**Most Valuable Insight:** Three-phase lifecycle (register, compose, activate) catches dependency errors early and enables atomic plugin set activation.

### 3. component-systems-synthesis-20251024.md
**Scope:** Synthesis of Convex + WASM findings with concrete recommendations
**Key Findings:**
- Universal component pattern emerges independently in both systems
- Five core pillars: manifest-driven, consumer-mediated, isolated, capability-based, type-safe
- Our unique contribution: multi-candidate selection + structured gaps for LLM repair
- 3-week implementation roadmap with Phase 1 checklist

**Most Valuable Insight:** Both systems validate our core architecture while providing three critical refinements: deny-by-default world, composition phase, capability versioning.

### 4. wasm-component-model-claude-analysis-20251024.md
**Scope:** Expert LLM analysis via Claude Sonnet 4.5
**Key Findings:**
- WASM is overengineered for our needs—cherry-pick patterns
- Adopt: semantic versioning, validated plans, adapter pattern, capability handles
- Skip: Canonical ABI, WIT IDL, build-time linking
- Consider later: transitive dependencies, virtualization, streaming

**Most Valuable Insight:** WASM is a *type system* for components, we're building a *negotiation protocol*—they're complementary.

## Consolidated Recommendations

### ADOPT IMMEDIATELY (All 3 Sources Agree)

#### 1. Deny-by-Default World Model
```clojure
;; Start with EMPTY world
(defonce world (atom #{}))

;; Host grants baseline capabilities
(defn init-host-capabilities! []
  (grant! :cap/tree.v1 :host)
  (grant! :cap/order.v1 :host))

;; Track provenance
(defn grant! [cap source]
  (swap! world-registry
         #(-> %
              (update :capabilities conj cap)
              (assoc-in [:grants cap] {:granted-by source :at (js/Date.)}))))
```

**Why:** WASI Virt proves this prevents accidental capability leaks. Convex enforces similar isolation. Claude emphasizes debuggability.

#### 2. Three-Phase Lifecycle
```clojure
;; Phase 1: REGISTRATION (validate manifest)
(defn register-plugin! [manifest] ...)

;; Phase 2: COMPOSITION (prove satisfiability)
(defn compose-plugins! [plugin-ids]
  (let [missing (check-requirements plugin-ids @world)]
    (if (empty? missing)
      {:status :ok :plugins plugin-ids}
      {:gap :unsatisfiable :missing missing})))

;; Phase 3: ACTIVATION (atomic world update)
(defn activate-composition! [composition]
  (swap! world set/union (:provides composition))
  (reset! active-plugins (:plugins composition)))
```

**Why:** WASM validates before execution. Convex separates registration from usage. Claude emphasizes fail-fast.

#### 3. Capability Versioning
```clojure
;; All capabilities versioned
:cap/tree.v1        ; ✓
:cap/order.v2       ; ✓
:cap/tree           ; ✗ Missing version

;; Manifests declare requirements
{:requires #{:cap/tree.v1 :cap/order.v1}}

;; Breaking change = new version
{:requires #{:cap/tree.v2 :cap/order.v1}}
```

**Why:** WASM uses `@0.1.0`. Convex uses NPM semver. Claude emphasizes independent interface evolution.

#### 4. Structured Gaps with Delta
```clojure
{:gap :missing-capability
 :intent :intent/structure.wrap.v1
 :required #{:cap/tree.v2}
 :available #{:cap/tree.v1 :cap/order.v1}
 :delta #{:cap/tree.v2}
 :explanation "Plugin requires :cap/tree.v2, world has :cap/tree.v1"}
```

**Why:** Neither WASM nor Convex has this. Claude emphasizes machine-actionable errors for LLM repair.

#### 5. Interface/Implementation Separation
```clojure
;; Host declares intent contracts
(def intent-registry
  {:intent/structure.wrap.v1
   {:schema {:intent [...] :result [...]}}})

;; Plugins implement intents
{:id :plugin/wrap-basic
 :implements #{:intent/structure.wrap.v1}  ; ← Not :provides
 :requires #{:cap/tree.v1}
 :cost (fn [ctx] 3)
 :lower (fn [db intent] [...])}

;; Multiple implementations compete
{:id :plugin/wrap-optimized
 :implements #{:intent/structure.wrap.v1}  ; ← Same interface
 :cost (fn [ctx] 1)}                       ; ← Different cost
```

**Why:** WASM separates WIT from implementation. Convex has component configs. Claude emphasizes substitutability.

### SKIP (All Sources Agree)

1. **WASM/Binary formats** - Overkill for Clojure-only
2. **Complex packaging** - Filesystem EDN files sufficient
3. **Cross-language interop** - Not needed yet
4. **GUI composition tools** - Solo dev doesn't need it

### CONSIDER LATER (2+ Sources Suggest)

1. **Virtual world for testing** - WASM has WASI Virt, Claude suggests for mocking
2. **Capability provenance tracking** - Claude suggests, aligns with Convex's explicit grants
3. **Adapter/shim registry** - WASM has adapters, Claude emphasizes first-class shims
4. **WASM plugins for performance** - If need Rust/Zig later

## Key Architectural Insights

### 1. The Universal Component Pattern

Both Convex and WASM independently arrived at the same five pillars:
1. **Manifest-driven** - Declare capabilities upfront
2. **Consumer-mediated** - Host orchestrates, no direct plugin-to-plugin calls
3. **Strong isolation** - Plugins can't access host/each other without grants
4. **Capability-based** - Deny-by-default, explicit grants
5. **Type-safe boundaries** - Runtime/compile-time validation

**Implication:** This pattern emerges naturally when solving "safe composition of untrusted code." We're not inventing—we're applying battle-tested patterns.

### 2. What We Add

Neither Convex nor WASM has:
- **Multi-candidate selection** - We rank by cost, they use first-match
- **Structured gaps** - We return `{:gap :missing :delta #{...}}`, they throw errors
- **Intent-level abstraction** - We work at intent level, they work at function/component level

**Implication:** Our "negotiation" layer adds value on top of their composition models.

### 3. Critical Refinements

Three patterns we MUST adopt:
1. **Deny-by-default** - Start with empty world, grant explicitly
2. **Composition phase** - Validate dependencies before activation
3. **Capability versioning** - Allow evolution without breaking

**Implication:** These are not optional—all three sources agree they prevent entire classes of bugs.

## Updated Manifest Schema

```clojure
{:id          :plugin/structure-wrap
 :version     "1.0.0"
 :description "Wrap nodes in a new parent"

 ;; Interface (what it implements)
 :implements  #{:intent/structure.wrap.v1}

 ;; Capabilities (what it needs)
 :requires    #{:cap/tree.v1 :cap/order.v1}

 ;; Capabilities (what it provides)
 :provides    #{:cap/structure.advanced.v1}

 ;; Validation
 :schema      {:intent [:map [:nodes [:vector :uuid]]]
               :result [:vector [:enum :op/create :op/place]]}

 ;; Selection
 :cost        (fn [ctx] (if (= (:platform ctx) :web) 3 1))

 ;; Execution
 :lower       (fn [db intent] [{:op :k/create ...}])}
```

**Changes from current ADR:**
- `:provides` → `:implements` for intents
- Added `:provides` for capabilities plugin grants
- All capabilities versioned (`:cap/tree.v1`)
- Separate `:schema` from implementation

## Implementation Roadmap

### Week 1: Core Infrastructure
- [ ] Define `::capability` spec (versioned keyword)
- [ ] Implement world registry with grants
- [ ] Implement three-phase lifecycle (register/compose/activate)
- [ ] Write property tests for composition

### Week 2: Negotiation
- [ ] Implement candidate filtering (requires ⊆ world)
- [ ] Implement candidate ranking (cost-based)
- [ ] Implement structured gaps
- [ ] Write tests for multi-candidate selection

### Week 3: Integration
- [ ] Define intent registry (structure/graph operations)
- [ ] Migrate existing handlers to plugin manifests
- [ ] Update interpret to use negotiate
- [ ] Add tracing (chosen candidate + world hash)

## Success Criteria

Our plugin architecture succeeds if:
1. ✅ **Explainable** - Clear answer to "why this handler?"
2. ✅ **Safe** - Plugins can't access undeclared capabilities
3. ✅ **Testable** - Property tests for composition laws
4. ✅ **Debuggable** - Clear registration/composition/activation phases
5. ✅ **Evolvable** - New capabilities don't break existing plugins
6. ✅ **Simple** - EDN manifests + Clojure functions, no complex toolchain

## Cross-Cutting Themes

### Debuggability (All Sources)
- **Convex:** Type-safe generated imports
- **WASM:** Detailed link errors
- **Claude:** Negotiation trace/audit log
- **Our takeaway:** Make negotiation observable

### Isolation (All Sources)
- **Convex:** Sandboxed DB tables, no global access
- **WASM:** No shared memory, capability-based
- **Claude:** Handle pattern for capability access
- **Our takeaway:** Deny-by-default world

### Evolution (All Sources)
- **Convex:** Named instances, schema compatibility
- **WASM:** Semantic versioning, multiple versions coexist
- **Claude:** Version interfaces independently
- **Our takeaway:** Capability versioning

### Composition (All Sources)
- **Convex:** Consumer orchestrates, no direct calls
- **WASM:** Explicit wiring, validated before execution
- **Claude:** Fail-fast, validated plans
- **Our takeaway:** Three-phase lifecycle

## Gaps in Our Current ADR

Based on research, our ADR needs:

1. **Capability Versioning Section** - MUST have
2. **Composition Phase Section** - MUST have
3. **Gap Types Section** - MUST have
4. **Interface Registry** - SHOULD have
5. **Negotiation Trace** - SHOULD have
6. **Adapter/Shim Registry** - COULD have

## Next Actions

1. **Update ADR-negotiation-(wasm)-vs-plugins.md** with findings (1 hour)
2. **Implement Phase 1 checklist** from roadmap (1 week)
3. **Test with real plugins** (structure editing, graph ops) (1 week)
4. **Iterate based on learnings** (ongoing)

## Conclusion

Research validates our core architecture while providing critical refinements:

**Validated:**
- ✅ Consumer-mediated composition
- ✅ Capability-based isolation
- ✅ Manifest-driven registration
- ✅ Type-safe boundaries

**Refined:**
- 🔄 Add deny-by-default world
- 🔄 Add composition phase
- 🔄 Add capability versioning
- 🔄 Separate interface from implementation

**Unique:**
- 🆕 Multi-candidate selection
- 🆕 Structured gaps for LLM
- 🆕 Intent-level abstraction

**Status:** Ready to implement Phase 1.

## References

- convex-components-research-20251024.md
- wasm-component-model-analysis-20251024.md
- component-systems-synthesis-20251024.md
- wasm-component-model-claude-analysis-20251024.md
- ADR-negotiation-(wasm)-vs-plugins.md
- [Convex Components](https://docs.convex.dev/components)
- [WASM Component Model](https://component-model.bytecodealliance.org/)
