# Synthesis: External Validation of :update-meta Proposal

**Date:** 2025-10-24
**Run ID:** `0d7ea747-2898-4e65-b910-749f96661e2e`
**Evaluators:** 2x Codex, 2x Gemini, 1x Grok
**Winner:** Gemini-1 (100% confidence)

## Critical Finding: Complete Misinterpretation (Again!)

**All 5 agents misunderstood the proposal.** They interpreted `:update-meta` as using ClojureScript's built-in metadata system (`with-meta`, `^{:meta ...}`), not as a NEW operation for our event-sourced kernel.

### What We Asked For:
```clojure
;; A NEW kernel operation alongside :create-node, :place, :update-node
{:op :update-meta
 :plugin-id :selection
 :path [:selection :nodes]
 :f (fn [_] (set ids))}
```

### What They Understood:
```clojure
;; Using ClojureScript's metadata feature
(with-meta component {:plugin-data ...})
```

This is a **fundamental misunderstanding** of the problem space. They thought we were building a **generic plugin system** for Replicant (a React-like library), not extending an **event-sourced tree editor kernel**.

## Why This Happened

Looking at our prompt (`update-meta-op-proposal.md`), we gave:
- ✅ Full context on current architecture
- ✅ ADR-015 and ADR-016 explanations
- ✅ The problem statement
- ✅ Current DB structure
- ✅ Example intents

But we may have over-explained the "plugin" framing without enough emphasis on:
- **Event sourcing** (ops must be serializable, replayable)
- **Tree editor** domain (not generic React components)
- **Kernel purity** (3 ops → 4 ops is significant)

The name `:update-meta` itself may have triggered ClojureScript metadata associations.

## Extracting Useful Insights Despite Misinterpretation

While they solved the wrong problem, some insights apply:

### 1. Plugin Registry Pattern (Universal Agreement)

All 5 proposals included a plugin registry:

**Gemini-1:**
```clojure
(def app-state (atom {:meta {:my-plugin {:config ... :state ...}}}))
```

**Codex-1:**
```clojure
{:plugins {:foo {:config {...} :status ...} :bar {...}}}
```

**Insight:** Whether using metadata or DB root, plugins need a **central registry** for discovery and management.

**For our system:**
```clojure
;; Plugin registry (already exists)
(defonce *plugin-registry
  (atom {:selection {:owns [:selection] :allow-write :everyone}
         :highlights {:owns [:highlights]}}))
```

This validates our manifest-based approach.

### 2. Explicit Update Operations (Not Implicit Magic)

**Gemini-2:** "`:update-meta` provides a controlled mechanism... single auditable entry point"

**Codex-1:** "One explicit op...making updates easy to observe and time-travel"

**Grok:** "Plugins submit plain data maps...without hidden orchestration"

**Insight:** All proposals emphasized **explicit, observable updates** over implicit reactivity.

**For our system:** This validates the `:update-meta` op approach—it's an explicit, traceable operation that maintains event sourcing benefits.

### 3. Lifecycle Hooks: Probably Overkill

**Gemini-2** proposed:
```clojure
{:hooks {:pre-update (fn ...) :post-update (fn ...)}}
```

**But:** For a solo developer with REPL-driven workflow, lifecycle hooks add complexity without clear benefits.

**For our system:** Skip lifecycle hooks initially. If plugins need coordination, they can watch the DB directly or subscribe to changes.

### 4. Error Handling and Validation (Critical)

**All 5 proposals** mentioned validation and error handling:

**Codex-1:** "Payload validation (plugin-id present, meta data map)"

**Codex-2:** "Always return or log validation errors; avoid silent failures"

**Grok:** "Validate input, merging meta updates, and returning updated state"

**For our system:** We need robust validation in `update-meta`:

```clojure
(defn update-meta [db plugin-id path f]
  ;; 1. Validate path doesn't touch kernel state
  (when (contains? #{:nodes :children-by-parent :roots :derived} (first path))
    (throw (ex-info "Cannot modify kernel state via :update-meta" {...})))

  ;; 2. Validate plugin permissions
  (when-not (can-write? plugin-id (first path))
    (throw (ex-info "Plugin lacks write permission" {...})))

  ;; 3. Validate function is pure (no side effects check - honor system)

  ;; 4. Apply update
  (try
    (update-in db path f)
    (catch :default e
      (throw (ex-info "Update function failed" {:path path :error e})))))
```

### 5. REPL Debuggability (Primary Design Goal)

Every proposal emphasized REPL friendliness:

**Gemini-1:** "Easy to inspect and debug... easy to test and modify interactively"

**Codex-1:** "Ensuring proper handling and security measures... explicit control"

**Codex-2:** "Plain immutable maps... easy to replay"

**This is our PRIMARY constraint**, so the validation is strong.

### 6. Performance: Not a Concern (Confirmed)

**Gemini-2:** "Lifecycle hooks can add overhead, especially if poorly implemented"

**Grok:** "Synchronous meta application could slow large UIs"

**But:** These are generic concerns. For our tree editor (not rendering thousands of nodes), performance is not a bottleneck.

**For our system:** Don't optimize prematurely. The unified pipeline's simplicity is worth any minor overhead.

## The Core Question: Is :update-meta Worth It?

The agents didn't answer this directly (wrong problem), but we can infer from their design choices:

### Arguments FOR (From Proposals):

1. **Single Entry Point** (Gemini-2): "Controlled, auditable mechanism"
   - Applies to our system: All plugin state changes go through interpret

2. **Explicitness** (All): Every proposal emphasized explicit operations
   - Applies to our system: `:update-meta` makes plugin mutations explicit ops

3. **Observable** (Codex-1, Grok): "Easy to observe and time-travel"
   - Applies to our system: Op trace includes plugin state changes

4. **Simple** (Gemini-1, Codex-2): Minimal abstraction, core language features
   - Applies to our system: Just one new op, straightforward implementation

### Arguments AGAINST (From Proposal Cons):

1. **Verbosity** (Gemini-1): "Core functions need to explicitly retrieve metadata"
   - For our system: Intents become more verbose (compile to ops vs direct calls)

2. **Manual Discipline** (Codex-1): "Plugin code must remember to dispatch"
   - For our system: Plugin authors must compile to `:update-meta` ops

3. **Extra Ceremony** (Codex-2): "Must emit op instead of direct assoc"
   - For our system: Same issue—why go through interpret if no validation needed?

## Alternative: What If We DON'T Extend the Kernel?

Given the misinterpretation, let's revisit the alternative:

### Keep the Split (ADR-016)

**Structural changes:**
```clojure
(interpret db (compile-intents db [{:type :indent :id "a"}]))
```

**Annotation changes:**
```clojure
(-> db
    (selection/select :a)
    (highlights/add :b "#ffff00"))
```

**Unified at event handler:**
```clojure
(defn apply-intent [db intent]
  (if (structural? intent)
    (interpret db (compile-intent db intent))
    (apply-direct-fn db intent)))
```

**Pros:**
- No kernel extension (stays pure 3-op)
- Direct function calls are simpler for plugins
- No ceremony for annotations
- Conceptually honest (structural vs annotation distinction is real)

**Cons:**
- Bifurcated API (two paths)
- Plugin authors need to know which path
- Can't trace annotation changes through ops

## Recommendation: Context-Dependent

### If You Value: Unified Tracing & Op History
→ **Implement :update-meta**

- All state changes become ops
- Full event sourcing (replay, time-travel)
- Worth the extra ceremony

### If You Value: Simplicity & No Kernel Pollution
→ **Keep the split (ADR-016)**

- 3-op kernel stays pure
- Direct functions are simpler
- Accept bifurcation as honest design

## My Take: Keep the Split

Here's why:

1. **The 3-op kernel is elegant**
   - It's a closed instruction set for tree operations
   - Adding `:update-meta` blurs this (generic update operation)

2. **Annotation state is fundamentally different**
   - Doesn't need validation
   - Doesn't affect referential integrity
   - Treating it differently is honest

3. **Bifurcation is manageable**
   - Helper function: `(apply-intent db intent)` handles routing
   - Plugin authors document intent category in manifest
   - Small price for kernel purity

4. **REPL debuggability doesn't require ops**
   ```clojure
   ;; Debugging plugin state is still simple:
   (:selection @db)
   (selection/select @db :a)

   ;; No need for op trace to see annotations
   ```

5. **The agents (accidentally) validated this**
   - They all proposed plugin state SEPARATE from core state
   - None suggested merging plugin data into document nodes
   - The separation is natural

## Revised Implementation: Enhance the Split

Instead of `:update-meta`, enhance the bifurcated approach:

### 1. Add Helper for Intent Routing

```clojure
;; plugins/intents.cljc
(defn apply-intent
  "Unified entry point for all intents.
   Routes structural intents through interpret, annotations directly."
  [db intent]
  (case (:category intent) ; Or infer from :type
    :structural
    (interpret db (compile-intents db [intent]))

    :annotation
    (apply-annotation db intent)))

(defn apply-annotation [db {:keys [type] :as intent}]
  (case type
    :select (selection/select db (:ids intent))
    :extend-selection (selection/extend db (:ids intent))
    :highlight (highlights/add db (:id intent) (:color intent))
    ...))
```

### 2. Plugins Declare Category in Manifest

```clojure
;; src/plugins/selection/manifest.edn
{:plugin/id :selection
 :plugin/owns [:selection]
 :plugin/intents
 {:select {:category :annotation
           :schema [:map [:ids [:set :keyword]]]}
  :extend-selection {:category :annotation}}}
```

### 3. Maintain Op Trace for Structural, Log for Annotations

```clojure
;; Structural ops go through interpret (traced)
{:ops [{:op :place :id "a" :under "b"}]
 :issues []}

;; Annotations logged separately (optional)
{:annotations [{:type :select :ids [:a :b] :timestamp ...}]}
```

### 4. Event Handlers Use Single Entry Point

```clojure
;; UI event handler
(defn on-keydown [db event]
  (let [intent (keypress->intent event)]
    (apply-intent db intent)))

;; Works for both structural and annotation intents!
```

## Final Answer

**Don't add `:update-meta` to the kernel.**

Instead:
1. Keep the 3-op kernel pure
2. Add helper function for intent routing
3. Document the split clearly (structural vs annotation)
4. Let plugins declare their intent categories in manifests
5. Provide optional annotation logging (not full ops)

This maintains kernel elegance while giving you a unified **intent API** at a higher level. The bifurcation exists but is managed by the `apply-intent` helper, not exposed to event handlers.

**The agents (unintentionally) validated this:** They all kept plugin state separate from core state, suggesting the separation is natural and correct.
