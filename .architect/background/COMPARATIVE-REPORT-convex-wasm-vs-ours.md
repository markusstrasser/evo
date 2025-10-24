# Component Architecture Comparison: Convex vs WASM vs Ours

**TL;DR:** Both Convex and WASM validate our core design. They teach us three critical refinements we're missing: deny-by-default capabilities, composition as a separate phase, and interface versioning. We contribute multi-candidate selection and structured gaps that neither has.

## The Pattern Match

All three systems independently converged on the same architecture:

```
┌────────────────────────────────────────────────┐
│  Component declares what it needs/provides     │
│  Host mediates all interactions                │
│  Strong isolation between components           │
│  Explicit capability grants                    │
│  Type-safe boundaries                          │
└────────────────────────────────────────────────┘
```

**This is not coincidence.** It's the natural solution to "safe composition of untrusted code."

## Feature Matrix

| Feature | Convex | WASM | Ours | Winner |
|---------|--------|------|------|--------|
| **Composition Model** | Consumer orchestrates | Explicit wiring | Consumer orchestrates | Tie |
| **Isolation** | Sandboxed DB tables | No shared memory | Immutable db | All good |
| **Capability Model** | Can't access unless passed | Deny-by-default (WASI Virt) | ❌ **Missing** | WASM |
| **Versioning** | NPM semver | `@0.1.0` syntax | ❌ **Missing** | Tie (both) |
| **Composition Phase** | Implicit | **Explicit** (register→compose→activate) | ❌ **Missing** | WASM |
| **Multi-candidate Selection** | ❌ First component | ❌ First match | ✅ **Cost-based** | **Ours** |
| **Structured Errors** | Exception thrown | "Missing import X" | ✅ **Gap objects** | **Ours** |
| **Intent Abstraction** | Component-level | Function-level | ✅ **Intent-level** | **Ours** |
| **Type Safety** | Runtime + TypeScript codegen | Static (WIT) | Runtime (Malli) | WASM |
| **Transactional Rollback** | ✅ Atomic commit | ❌ None | ⚠️ **Partial** | Convex |

## What They Teach Us

### From WASM: Three-Phase Lifecycle

```
┌─────────────┐       ┌──────────────┐       ┌────────────┐
│  REGISTER   │  -->  │   COMPOSE    │  -->  │  ACTIVATE  │
│             │       │              │       │            │
│ Validate    │       │ Prove        │       │ Atomic     │
│ manifest    │       │ satisfiable  │       │ world      │
│ structure   │       │ dependencies │       │ update     │
└─────────────┘       └──────────────┘       └────────────┘
```

**Current problem:** We mix registration and activation—plugins become active on registration. This means:
- Can't validate full dependency graph before activating anything
- No atomic "activate these 5 plugins together" operation
- Hard to rollback if composition fails partway through

**Fix:**
```clojure
;; NOW: Mixed phases (bad)
(defn register-plugin! [manifest]
  (validate-manifest! manifest)
  (swap! registry assoc (:id manifest) manifest)
  (swap! world conj (:provides manifest))  ; ← Too eager!
  (check-requirements! manifest))          ; ← Wrong phase!

;; BETTER: Separate phases
(register-plugin! manifest)       ; Just validate + store
(compose-plugins! [:p1 :p2 :p3]) ; Prove satisfiability
(activate-composition! result)    ; Atomic world update
```

### From WASM: Deny-by-Default Capabilities

**Current problem:** Our world is the union of all `:provides`. Plugins automatically get access to capabilities from other plugins.

**Fix:**
```clojure
;; NOW: Accumulative (risky)
(defonce world (atom #{:cap/tree :cap/order}))
;; Any plugin can provide new capabilities, all plugins see them

;; BETTER: Explicit grants
(defonce world (atom #{}))  ; Start empty!

(defn init! []
  (grant! :cap/tree.v1 :host)      ; Host controls baseline
  (grant! :cap/order.v1 :host))

(defn grant! [cap source]
  (swap! world-registry
    #(-> %
         (update :capabilities conj cap)
         (assoc-in [:grants cap] {:granted-by source :at (now)}))))
```

**Why this matters:**
- Plugins can't accidentally access capabilities they don't need
- Security: trace who granted what, when, why
- Testing: start with empty world, grant only what test needs

### From Both: Capability Versioning

**Current problem:** Unversioned capabilities. Breaking changes to `:cap/tree` break all plugins.

**Fix:**
```clojure
;; NOW: Unversioned
:cap/tree
:intent/structure.wrap

;; BETTER: Versioned
:cap/tree.v1
:cap/tree.v2              ; Breaking change
:intent/structure.wrap.v1

;; Plugins declare requirements
{:requires #{:cap/tree.v1 :cap/order.v1}}

;; World can have multiple versions
@world => #{:cap/tree.v1 :cap/tree.v2 :cap/order.v1}

;; Negotiation picks compatible version
```

**Why this matters:**
- Old plugins keep working when we add new capabilities
- Can migrate plugins gradually (v1 → v2)
- Gap messages explain version mismatches: "needs :cap/tree.v2, you have :cap/tree.v1"

### From Convex: Transactional Guarantees

**Current insight:** We apply ops sequentially. If op 3 of 5 fails, ops 1-2 already mutated DB.

**Their solution:**
```javascript
// Convex: Either all commit or all rollback
try {
  await components.leaderboard.insert(ctx, player);
  await components.analytics.track(ctx, event);
  // Both commit atomically
} catch (e) {
  // Both rolled back automatically
}
```

**Our consideration:**
```clojure
;; Option A: Validate plan before applying
(defn interpret [db plan]
  (validate-plan! plan db)  ; Throws if any op would fail
  (reduce apply-op db (:ops plan)))

;; Option B: Transaction wrapper (later)
(defn interpret [db plan]
  (transact!
    (reduce apply-op db (:ops plan))))
```

**Decision:** Start with Option A (validate), add transactions later if needed.

## What We Contribute

### 1. Multi-Candidate Selection

```
Convex: One component per capability
WASM:   One export per import
Ours:   Multiple plugins compete, cost-based selection

┌─────────────────────────────────────────────┐
│ intent/structure.wrap.v1                    │
├─────────────────────────────────────────────┤
│ Plugin A: cost=3, requires={:cap/tree.v1}   │  ← Chosen for TUI
│ Plugin B: cost=1, requires={:cap/tree.v2}   │  ← Chosen for Web
│ Plugin C: cost=5, requires={:cap/tree.v1}   │  ← Never chosen
└─────────────────────────────────────────────┘
```

**Use case:** TUI uses Plugin A (simple, fast), Web uses Plugin B (optimized, needs tree.v2).

### 2. Structured Gaps for LLM Repair

```
Convex: throw new Error("Missing capability")
WASM:   Error: unsatisfied import `logger`
Ours:   {:gap :missing-capability
         :intent :intent/structure.wrap.v1
         :required #{:cap/tree.v2}
         :available #{:cap/tree.v1}
         :delta #{:cap/tree.v2}
         :explanation "Plugin needs tree.v2, upgrade or use different plugin"}
```

**Use case:** LLM agent sees gap, decides to:
- Upgrade `:cap/tree.v1` → `:cap/tree.v2`, OR
- Choose different plugin that works with v1, OR
- Ask human what to do

### 3. Intent-Level Abstraction

```
WASM:   Function-level (fine-grained)
        import add: func(u32, u32) -> u32

Convex: Component-level (coarse)
        import @convex-dev/aggregate

Ours:   Intent-level (semantic)
        :intent/structure.wrap.v1
        Multiple plugins implement, negotiation picks best
```

**Use case:** "Wrap these nodes" is a user intent. We don't care if implementation uses tree ops, graph ops, or CRDT—pick best for context.

## Critical Gaps in Our Current ADR

| Gap | Impact | Urgency |
|-----|--------|---------|
| **No capability versioning** | Breaking changes break all plugins | 🔴 High |
| **No composition phase** | Can't validate dependencies upfront | 🔴 High |
| **No deny-by-default** | Plugins access capabilities they don't need | 🟡 Medium |
| **No interface/impl separation** | Can't have competing implementations | 🟡 Medium |
| **No negotiation trace** | Hard to debug "why this plugin?" | 🟢 Low |

## Actionable Changes to ADR

### 1. Add to Manifest Schema

```clojure
;; BEFORE
{:provides #{:intent/structure.wrap}
 :requires #{:cap/tree :cap/order}}

;; AFTER
{:implements #{:intent/structure.wrap.v1}  ; What interface
 :requires #{:cap/tree.v1 :cap/order.v1}   ; Versioned deps
 :provides #{:cap/advanced.v1}}            ; What capabilities plugin grants
```

### 2. Add Composition Phase

```clojure
;; Add to ADR: "Plugin Lifecycle"
1. REGISTER: Validate manifest, store in registry
2. COMPOSE:  Prove all requirements satisfiable, return composition or gap
3. ACTIVATE: Atomic world update, enable plugins
4. EVALUATE: Select from active plugins per intent
```

### 3. Add Capability Versioning

```clojure
;; Add to ADR: "Capability Versioning"
- Format: :cap/name.vMAJOR.MINOR.PATCH or :cap/name.vMAJOR
- Breaking changes REQUIRE new major version
- Plugins can require ranges: :cap/tree.v1+ (any v1.x)
- World can provide multiple versions simultaneously
```

### 4. Add Gap Types

```clojure
;; Add to ADR: "Structured Gaps"
{:gap :missing-capability          ; No provider for requirement
 :gap :unsatisfiable-composition   ; Circular deps, conflicts
 :gap :version-mismatch            ; Have v1, need v2
 :gap :no-viable-candidate}        ; All candidates filtered out
```

## What to Skip

1. **WASM as plugin format** - Overkill for Clojure-only system
2. **Binary distribution** - Filesystem EDN files work fine
3. **Canonical ABI** - Clojure data structures sufficient
4. **Build-time linking** - We need runtime flexibility

## Implementation Priority

### Week 1: Must Have
- [ ] Capability versioning (all caps → `.v1` suffix)
- [ ] Three-phase lifecycle (register/compose/activate)
- [ ] Structured gaps with `:delta`

### Week 2: Should Have
- [ ] Deny-by-default world (start empty, grant explicitly)
- [ ] Interface registry (host declares intent contracts)
- [ ] Negotiation trace (debug "why this plugin?")

### Week 3: Nice to Have
- [ ] Adapter/shim registry (version bridging)
- [ ] Capability provenance (who granted what, when)
- [ ] Virtual world for testing

## Conclusion

**Validated Decisions:**
- ✅ Consumer-mediated composition (both use it)
- ✅ Capability-based isolation (both enforce it)
- ✅ Manifest-driven (both require it)

**Critical Additions:**
- 🔴 Deny-by-default capabilities
- 🔴 Composition phase
- 🔴 Capability versioning

**Our Unique Value:**
- 🆕 Multi-candidate selection
- 🆕 Structured gaps for LLM
- 🆕 Intent-level abstraction

**Next Action:** Update ADR with three critical additions (2 hours), implement Phase 1 (1 week).

---

## Quick Reference

### Convex Strengths
- Transactional rollback
- Type-safe codegen
- NPM ecosystem integration

### WASM Strengths
- Explicit composition phase
- Deny-by-default (WASI Virt)
- Cross-language interop

### Our Strengths
- Multi-candidate selection
- Structured gaps
- Runtime flexibility

### Our Weaknesses (Pre-Research)
- ❌ No versioning
- ❌ No composition phase
- ❌ Accumulative world model

### Our Weaknesses (Post-Fix)
- ✅ Versioning added
- ✅ Composition phase added
- ✅ Deny-by-default added
- 🚀 Ready to ship
