# Convex Components: Architecture Research

**Date:** 2025-10-24
**Context:** Research into Convex's component architecture for insights applicable to our plugin/negotiation system
**Related ADR:** ADR-negotiation-(wasm)-vs-plugins.md

## Executive Summary

Convex components are "mini self-contained backends" with strong isolation, transactional guarantees, and NPM-based distribution. Key architectural insights:

1. **Manifest = Config Export Pattern**: Components export a `defineComponent()` declaration; consumers import and register via `app.use()`; build tools generate type-safe API access
2. **Consumer Always Mediates**: Components cannot directly call each other—the host application orchestrates all inter-component communication
3. **Strong Isolation**: Each component has independent database tables, file storage, scheduled functions, and environment variables
4. **Generated Type Safety**: Config changes trigger code generation for type-safe component access via `import { components } from "./_generated/api"`
5. **Named Instances**: Same component can be installed multiple times with different configurations

## Architecture Deep Dive

### 1. The "Manifest" System

The manifest isn't a separate JSON file—it's the **component's exported config**:

```typescript
// Inside @convex-dev/aggregate package
// at: @convex-dev/aggregate/convex.config.ts
import { defineComponent } from "convex/server";
export default defineComponent("aggregate");
```

**Consumer side:**
```typescript
// In your app's convex/convex.config.ts
import { defineApp } from "convex/server";
import aggregate from "@convex-dev/aggregate/convex.config";

const app = defineApp();
app.use(aggregate, { name: "leaderboard" }); // name is optional
export default app;
```

**Code generation flow:**
1. Component publishes to NPM with `convex.config.ts` export
2. Consumer installs via `npm install @convex-dev/aggregate`
3. Consumer registers in their `convex/convex.config.ts` via `app.use()`
4. Running `npx convex dev` generates type-safe imports:
   ```typescript
   import { components } from "./_generated/api";
   // Now you have: components.leaderboard
   ```

### 2. State Isolation Architecture

**Each component gets its own sandboxed:**
- Database tables (with independent schema validation)
- File storage (independent buckets)
- Scheduled functions / cron jobs
- Environment variables

**Isolation enforcement mechanisms:**
- No global variable access
- No direct table access between components
- All data must be explicitly passed across boundaries
- Runtime validation on all boundary crossings
- Sub-mutations roll back independently without affecting callers

**Example isolation:**
```typescript
// Component stores data in its OWN tables
// Consumer can't access component's tables directly
// Component can't access consumer's tables unless passed explicitly
```

This is enforced both at runtime and via TypeScript type system.

### 3. Component Composition Model

**Critical finding:** Components **cannot directly call each other's APIs** without explicit wiring by the parent application.

Three composition patterns observed:

#### Pattern A: Consumer Orchestrates (Most Common)
```typescript
// Parent app explicitly wires components together
const result1 = await components.componentA.someFunction(ctx, data);
const result2 = await components.componentB.anotherFunction(ctx, result1);
```

#### Pattern B: Pass Component References
```typescript
// Pass one component's API as a parameter to another
const workflow = new WorkflowManager(components.workflow, {
  workpool: components.workpool  // Explicit dependency injection
});
```

#### Pattern C: Internal Bundling
```typescript
// workflow component internally uses workpool component
import { vResultValidator } from "@convex-dev/workpool";

// Inside workflow's own convex.config.ts
app.use(workpool); // Component bundles another component
```

**This prevents:**
- Hidden dependencies between components
- Circular dependencies
- Unexpected coupling
- Non-deterministic load order issues

### 4. API Definition & Boundaries

Components expose explicit APIs rather than direct database access:

**Public API Example (from aggregate component):**
```typescript
// src/client/index.ts exports TableAggregate class
export class TableAggregate {
  // Write Operations
  async insert(ctx, doc) { ... }
  async replace(ctx, oldDoc, newDoc) { ... }
  async delete(ctx, doc) { ... }

  // Query Operations
  async count(ctx, options?) { ... }
  async sum(ctx, options?) { ... }
  async max(ctx, options?) { ... }
  async at(ctx, index, options?) { ... }
  async indexOf(ctx, key) { ... }
}
```

**Boundary enforcement:**
- Runtime validation via Convex validators
- TypeScript types for compile-time safety
- All cross-boundary data transfers comply with specified schemas
- Components define validators that specify TypeScript types end-to-end

**Internal vs External:**
- Components use the `internal.*` namespace for private functions
- Only explicitly exported APIs are accessible to consumers
- Internal implementation details (sharding, batching, denormalization) are hidden

### 5. Transactional Guarantees

**Key innovation:** Transactional integrity across component boundaries without distributed protocols.

```typescript
// Changes commit atomically across component calls
try {
  await components.leaderboard.insert(ctx, player);
  await components.analytics.track(ctx, event);
  // Both commit together or both roll back
} catch (e) {
  // Both operations rolled back automatically
}
```

**Properties:**
- Sub-mutations in components are isolated transactions
- Thrown exceptions always roll back the component's sub-transaction
- No distributed commit protocols needed
- No data inconsistencies across component boundaries
- Calling code can catch and handle component errors without corruption

### 6. Named Instances & Configuration

Components support multiple named instances with different configurations:

```typescript
const app = defineApp();

// Multiple instances of same component
app.use(aggregate, { name: "aggregateScores" });
app.use(aggregate, { name: "aggregateByGame" });
app.use(workpool, { name: "smallPool" });
app.use(workpool, { name: "bigPool" });
app.use(workpool, { name: "serializedPool" });

export default app;
```

**Access pattern:**
```typescript
import { components } from "./_generated/api";

// Type-safe access to each instance
components.aggregateScores.count(ctx);
components.aggregateByGame.sum(ctx);
components.smallPool.enqueue(ctx, task);
```

### 7. Distribution & Discovery

**NPM as the distribution mechanism:**
- Each component is a standalone NPM package
- Versioning follows semver
- Dependencies declared in `package.json`
- Central discovery at convex.dev/components

**Component categories:**
- AI & Agents (workflow orchestration, message history)
- Background Tasks (durable execution with retries)
- Integrations (Resend, Twilio, Cloudflare R2, Expo Push)
- Database Utilities (aggregations, migrations)
- Backend Tools (rate limiting, sharded counters)

**Example dependency from workflow component:**
```json
{
  "name": "@convex-dev/workflow",
  "dependencies": {
    "@convex-dev/workpool": "^0.1.0"
  }
}
```

### 8. Data Migration & Compatibility

**Component replacement:**
- Can replace a component and maintain its data by **reusing the same name**
- Requires compatible schema with existing data
- Data remains accessible via Convex dashboard
- Export/import functionality for data portability

**Schema evolution:**
```typescript
// Old component version
defineTable("items", v.object({
  key: v.string(),
  value: v.number()
}));

// New component version - compatible
defineTable("items", v.object({
  key: v.string(),
  value: v.number(),
  metadata: v.optional(v.object({ ... })) // Added optional field
}));
```

## Applicability to Our Plugin Architecture

### Direct Mappings

| Convex Concept | Our Equivalent | Notes |
|----------------|----------------|-------|
| `defineComponent()` | Plugin manifest (EDN/Malli) | Both declare capabilities/API |
| `app.use()` registration | Plugin registry | Central coordinator |
| Generated `components.*` | World registry | Type-safe capability access |
| Component sandbox | Capability isolation | Enforce requires/provides |
| Transactional sub-mutations | Plan rollback | Atomic operation execution |
| Named instances | Multiple plugin instances | Same handler, different configs |
| NPM distribution | Plugin discovery | Versioned distribution |

### Architectural Insights

**1. Consumer-Mediated Composition**
- **Insight:** No direct plugin-to-plugin calls prevents hidden dependencies
- **Our application:** All intent evaluation goes through central `(negotiate db intent ctx world)`
- **Benefit:** Deterministic, traceable, testable composition

**2. Explicit Capability Declaration**
- **Insight:** Components declare `requires`/`provides` upfront in manifest
- **Our application:** Already in our negotiation ADR—manifests declare `:requires #{:cap/tree}` and `:provides #{:intent/structure.wrap}`
- **Benefit:** Solver can prove satisfiability or return structured gaps

**3. Generated Type Safety**
- **Insight:** Build step creates type-safe access to components
- **Our application:** Could generate Malli schemas for plugin APIs from manifests
- **Benefit:** Compile-time guarantees, IDE support

**4. Isolation via Separate State**
- **Insight:** Each component has its own DB tables/storage
- **Our application:** Plugins could have namespaced state (`:plugin-id/table-name`)
- **Benefit:** No state leakage, easier reasoning

**5. Transactional Rollback**
- **Insight:** Failed component calls roll back their changes
- **Our application:** Failed plan execution should roll back all ops in that plan
- **Benefit:** Consistency guarantees, no partial states

**6. Named Instances Pattern**
- **Insight:** Same component code, multiple configurations
- **Our application:** Same lowering handler, different contexts (TUI vs Web vs Native)
- **Benefit:** Code reuse without duplication

### Gaps & Differences

**What Convex has that we might need:**

1. **Runtime validation at boundaries**
   - They use Convex validators on all cross-boundary data
   - We should consider Malli validation at intent/plan boundaries

2. **Deterministic hashing for replay**
   - They hash world state for deterministic replay
   - We should hash `{:world-hash ... :intent-hash ... :ctx-fingerprint ...}` for audit trail

3. **Structured error types**
   - They distinguish different error categories
   - Our gaps should be typed: `:gap/missing-capability`, `:gap/unsatisfiable-constraint`, etc.

**What we have that Convex doesn't:**

1. **Multi-candidate selection**
   - They typically have one implementation per component
   - We have ranked selection via cost functions

2. **Constraint solving**
   - They do simple subset checking (`requires ⊆ provides`)
   - We add optimization (argmin by cost) and policy pruning

3. **Explainable gaps**
   - Their errors are "missing capability"
   - Our gaps explain *why* (which constraints failed, what's needed)

### Concrete Recommendations

**Adopt these patterns:**

1. **Manifest shape** (matches our ADR already):
   ```clojure
   {:module :structure/wrap
    :provides #{:intent/structure.wrap}
    :requires #{:cap/tree :cap/order}
    :cost (fn [ctx] 3)  ; pure, deterministic
    :lower (fn [db intent ctx] [{:op :k/create ...}])}
   ```

2. **World registry as union of provides**:
   ```clojure
   (def world (atom #{:cap/tree :cap/order :cap/view-diff}))

   (defn load-plugin! [manifest]
     (swap! world clojure.set/union (:provides manifest)))
   ```

3. **Generated API access** (optional, future):
   ```clojure
   ;; Auto-generated from manifests
   (def plugins
     {:structure/wrap {:requires #{:cap/tree :cap/order}
                       :invoke (fn [db intent ctx] ...)}
      :graph/backlinks {:requires #{:cap/graph}
                        :invoke (fn [db intent ctx] ...)}})
   ```

4. **Boundary validation**:
   ```clojure
   (defn validate-plan [plan world]
     (when-not (clojure.set/subset? (:requires plan) world)
       {:gap :missing-capability
        :required (:requires plan)
        :available world
        :delta (clojure.set/difference (:requires plan) world)}))
   ```

5. **Deterministic tracing**:
   ```clojure
   (defn trace-decision [plan world ctx]
     {:candidate (:id plan)
      :world-hash (hash world)
      :ctx-fingerprint (select-keys ctx [:mode :platform :latency-budget])
      :timestamp (ctx :time)})
   ```

**Don't adopt:**

1. **NPM distribution** (too heavyweight for now)
   - Start with filesystem-based plugins
   - Add registry later if needed

2. **Separate DB tables per plugin** (overkill initially)
   - Use namespaced keys in main DB
   - Promote to separate stores if performance demands it

3. **Complex schema migration** (YAGNI)
   - Plugins declare schema requirements
   - Host validates compatibility
   - Don't build migration system until needed

## Implementation Sketch

Minimal bridge from current ADR to Convex-style isolation:

```clojure
;; Plugin manifest (EDN file or defonce)
{:id :structure/wrap
 :version "1.0.0"
 :provides #{:intent/structure.wrap}
 :requires #{:cap/tree :cap/order}
 :schema {:intent [:map [:nodes [:vector :uuid]]]
          :result [:vector :op/create]}
 :cost (fn [ctx] 3)
 :lower (fn [db intent ctx] [{:op :k/create ...}])}

;; World registry
(defonce world (atom #{}))
(defonce plugins (atom {}))

(defn register-plugin! [manifest]
  (let [id (:id manifest)]
    ;; Validate manifest schema
    (when-not (s/valid? ::manifest manifest)
      (throw (ex-info "Invalid manifest" (s/explain-data ::manifest manifest))))

    ;; Check requirements satisfied
    (when-not (clojure.set/subset? (:requires manifest) @world)
      (throw (ex-info "Unsatisfied requirements"
                      {:missing (clojure.set/difference (:requires manifest) @world)})))

    ;; Register
    (swap! plugins assoc id manifest)
    (swap! world clojure.set/union (:provides manifest))

    ;; Return API handle
    {:id id
     :invoke (fn [db intent ctx]
               (validate-and-call db intent ctx manifest))}))

(defn negotiate [db intent ctx]
  (let [intent-key (:intent intent)
        ;; Collect candidates
        candidates (keep (fn [[id manifest]]
                          (when (contains? (:provides manifest) intent-key)
                            (assoc manifest :id id)))
                        @plugins)

        ;; Filter by capability requirements
        viable (filter #(clojure.set/subset? (:requires %) @world)
                       candidates)

        ;; Rank by cost
        ranked (sort-by #((:cost % (constantly 0)) ctx) viable)]

    (if-let [chosen (first ranked)]
      ;; Execute and trace
      (let [plan ((:lower chosen) db intent ctx)
            trace (trace-decision chosen @world ctx)]
        {:plan plan
         :trace trace})

      ;; Return gap
      {:gap :missing-capability
       :required (reduce clojure.set/union #{} (map :requires candidates))
       :available @world
       :intent intent-key})))

(defn validate-and-call [db intent ctx manifest]
  ;; Validate intent against schema
  (when-not (s/valid? (:schema manifest :intent) intent)
    (throw (ex-info "Invalid intent"
                    {:problems (s/explain-data (:schema manifest :intent) intent)})))

  ;; Call lowering function
  (let [result ((:lower manifest) db intent ctx)]

    ;; Validate result against schema
    (when-not (s/valid? (:schema manifest :result) result)
      (throw (ex-info "Invalid result from plugin"
                      {:plugin (:id manifest)
                       :problems (s/explain-data (:schema manifest :result) result)})))

    result))
```

## Open Questions

1. **How do we handle plugin dependencies on other plugins?**
   - Convex punts this to the consumer (explicit wiring)
   - Could we allow plugins to declare `:depends-on #{:plugin/other}`?
   - Tradeoff: convenience vs. complexity and hidden coupling

2. **Should we support multiple instances of same plugin?**
   - Convex uses named instances
   - Use case: multiple view modes, multiple backends
   - Implementation: registry maps `[plugin-id instance-name]` → manifest

3. **How do we version plugin APIs?**
   - Convex uses semver in NPM
   - We could use namespaced capabilities: `:intent/structure.wrap.v1` vs `.v2`
   - Breaking changes = new capability key

4. **Do we need transactional rollback?**
   - Convex guarantees atomic commit across components
   - We apply ops in sequence—failed op could leave partial state
   - Solution: wrap plan execution in transaction (validate all before applying any)

5. **Should plugins have isolated state?**
   - Convex: separate DB tables per component
   - Ours: shared DB with namespaced keys?
   - Tradeoff: isolation vs. query complexity

## References

- Convex Components Docs: https://docs.convex.dev/components
- Convex Backend Components: https://stack.convex.dev/backend-components
- Example Components:
  - Aggregate: https://github.com/get-convex/aggregate
  - Workflow: https://github.com/get-convex/workflow
  - Workpool: https://github.com/get-convex/workpool

## Related ADRs

- ADR-negotiation-(wasm)-vs-plugins.md - Core negotiation architecture
- ADR-three-op-kernel.md - Kernel operations that plugins lower to
- ADR-refs-as-policy.md - Capability system foundations

## Changelog

- 2025-10-24: Initial research from web search and GitHub exploration
