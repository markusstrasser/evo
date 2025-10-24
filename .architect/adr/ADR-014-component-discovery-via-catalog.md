# ADR 014: Component Discovery via Catalog (No Runtime Declarations)

## Status
Accepted

## Context

Building event-sourced outliner where **LLMs generate 100+ UI components dynamically**.

### The Question
How should components declare/discover dependencies when:
- LLM can use semantic search, ripgrep, prompt context for discovery
- Values: Simple, explicit, composable
- Event sourcing = immutable data, pure functions
- Solo dev with AI assistants (not teams)

### Three Proposals Evaluated

**1. Codex: `defcomponent` Macro**
```clojure
(c/defcomponent review-card
  {:params [:card-hash :show-answer?]
   :needs {:citations [:derived :ref/citations :card-hash]}
   :emits {:show-answer [::show-answer {...}]}}
  (fn [{:keys [citations card-hash]}] [:div ...]))
```
- Runtime resolves data automatically
- Hidden data fetching
- Macro complexity

**2. Gemini: Metadata + Resolver**
```clojure
(defn review-card
  ^{:props [:db/card :card/hash :anki/intervals]
    :events [::show-answer]}
  [{:keys [db/card card/hash anki/intervals]}]
  [:div ...])
```
- Explicit data flow
- Parent must aggregate child props (ceremony)

**3. Catalog Only (No Declarations)**
```clojure
;; Component stays simple
(defn review-card [{:keys [card show-answer? card-hash state]}]
  (let [citations (get-in state [:derived :ref/citations card-hash])]
    [:div ...]))

;; Discovery via plugins/catalog.cljc
(def derived-indexes
  {:ref/citations {:path [:derived :ref/citations node-id]
                   :type :map
                   :desc "Citation count for each node"}})
```
- LLM greps catalog for discovery
- No metadata overhead
- Explicit `get-in` calls

## Decision

**Use "Catalog Only" approach** - no component declarations, just documentation.

Evaluated by GPT-5 Codex with high reasoning effort.

## Rationale

### 1. Static Catalog is Sufficient for LLM Discovery

**LLM workflow:**
```bash
# Want to show citation counts
rg "derived-indexes" plugins/catalog.cljc
# Finds: :ref/citations at [:derived :ref/citations node-id]
# Writes: (get-in state [:derived :ref/citations node-id])
```

**Key insight:** LLM can grep/search code. Discovery happens via documentation, not runtime metadata.

### 2. Components Don't Need Declarations

**Function signature IS the API contract:**
```clojure
(defn review-card [{:keys [card show-answer? card-hash state]}]
  ...)
```

- Destructuring shows what component needs
- Parent reads signature to know what to pass
- Adding metadata duplicates information already present

**Declarations would add ceremony without safety** (no runtime resolver/topo-sort to consume them).

### 3. Plugins Don't Build on Each Other

**Current architecture (works well):**
```clojure
;; All plugins read canonical db, not each other
(defn derive-indexes [db]  ; Not db[:derived]!
  {:ref/citations (count-citations (:nodes db))})
```

**Plugins run in parallel, unspecified order.**

**If Plugin B needs Plugin A's logic:** Move shared computation to pure helper namespace. Both read from canonical `db`.

### 4. Explicit > Hidden

**Current (explicit):**
```clojure
(let [citations (get-in state [:derived :ref/citations node-id])]
  ...)
```

**With macro/resolver (hidden):**
```clojure
;; Where does citations come from? Runtime magic!
(fn [{:keys [citations]}] ...)
```

For 100+ LLM-generated components: **Explicit data flow beats hidden wiring.**

## Implementation

### 1. Create `plugins/catalog.cljc`

```clojure
(ns plugins.catalog
  "Catalog of all derived data available from plugins.

   For LLM discovery: grep this file to find what derived data exists.")

(def derived-indexes
  "Map of derived data keys to their documentation."
  {:ref/citations
   {:path [:derived :ref/citations node-id]
    :type :map
    :desc "Citation count for each node"
    :example {"node-123" 5, "node-456" 12}
    :provided-by :plugins.refs.core/derive-indexes}

   :ref/outgoing
   {:path [:derived :ref/outgoing node-id]
    :type :set
    :desc "Set of target node IDs this node links to"
    :example {"node-123" #{"node-456" "node-789"}}
    :provided-by :plugins.refs.core/derive-indexes}

   :ref/backlinks-by-kind
   {:path [:derived :ref/backlinks-by-kind kind target-id]
    :type :map-of-maps
    :desc "Backlinks grouped by :link/:highlight"
    :example {:link {"node-456" #{"node-123"}}
              :highlight {"node-789" #{"node-123"}}}
    :provided-by :plugins.refs.core/derive-indexes}

   :selection/active
   {:path [:derived :selection/active]
    :type :set
    :desc "Set of currently selected node IDs"
    :example #{"node-123" "node-456"}
    :provided-by :plugins.selection.core/derive-indexes}

   :siblings/order
   {:path [:derived :siblings/order parent-id]
    :type :vector
    :desc "Ordered list of child node IDs for a parent"
    :example {"doc-root" ["node-1" "node-2" "node-3"]}
    :provided-by :plugins.siblings-order/derive-indexes}})
```

### 2. Add CI Check (Prevent Drift)

```clojure
;; test/plugins/catalog_test.cljc
(deftest catalog-matches-reality
  (testing "Every catalog entry corresponds to real plugin key"
    (let [catalog-keys (set (keys catalog/derived-indexes))
          actual-derived (plugins.registry/run-all test-db)
          actual-keys (set (keys actual-derived))]
      (is (= catalog-keys actual-keys)
          "Catalog must match actual derived data keys"))))
```

### 3. Document Component Contract

Create `docs/components.md`:

```markdown
# Component Contract

## Data Access

Components receive `state` map and read data via `get-in`:

- Canonical: `(get-in state [:nodes node-id])`
- Derived: `(get-in state [:derived :ref/citations node-id])`

## Event Dispatch

Dispatch plain vectors: `[::event-kw arg1 arg2]`

## Discovery

See `plugins/catalog.cljc` for all available derived data.

## Example

\```clojure
(defn review-card [{:keys [card-hash state]}]
  (let [citations (get-in state [:derived :ref/citations card-hash])
        card (get-in state [:nodes card-hash])]
    [:div.card
     [:div.citations "Citations: " citations]
     [:div.content (:text card)]]))
\```
```

### 4. Component Template for LLMs

```clojure
;; Template: New component with derived data
(defn my-component
  "Component description."
  [{:keys [node-id state]}]
  (let [;; Read canonical data
        node (get-in state [:nodes node-id])
        ;; Read derived data (see plugins/catalog.cljc for available keys)
        citations (get-in state [:derived :ref/citations node-id])
        outgoing (get-in state [:derived :ref/outgoing node-id])]
    [:div.my-component
     [:h3 (:text node)]
     [:p "Citations: " citations]
     [:p "Links to: " (count outgoing)]
     [:button {:on {:click [::some-action node-id]}} "Action"]]))
```

## Minimal API (5 Pieces)

**For 100+ LLM-generated components:**

1. **Stable state shape** - `db[:nodes]`, `db[:derived]`
2. **Catalog** - `plugins/catalog.cljc` with all derived indexes
3. **Component template** - Destructuring + `get-in` examples
4. **Event naming** - Namespace-qualified keywords `::event-kw`
5. **Contract doc** - `docs/components.md` explaining patterns

**That's it. No macros, no metadata, no runtime resolution.**

## Consequences

### Positive

- **Zero runtime overhead** - No resolver, no macro expansion
- **Explicit data flow** - `get-in` calls show exactly where data comes from
- **Easy debugging** - No hidden wiring to trace
- **LLM-friendly** - Grep catalog, copy template, done
- **No ceremony** - Function signature is the API

### Negative

- **Catalog can drift** - Manual updates needed
  - *Mitigation:* CI test validates catalog matches reality
- **No compile-time checks** - Typos in paths not caught
  - *Mitigation:* Runtime errors caught in dev, tests exercise all paths
- **Repeated `get-in` calls** - More verbose than auto-resolution
  - *Mitigation:* Acceptable trade-off for explicitness

### Escape Hatch: Data Functions for Complex Derivations

For components with expensive or reusable data extraction, use **data-fn pattern**:

```clojure
;; Reusable data extraction (can be shared across components)
(defn card-analytics-data [state card-id]
  "Compute expensive analytics - test independently"
  {:citations (get-in state [:derived :ref/citations card-id])
   :backlinks (get-in state [:derived :ref/backlinks-by-kind :link card-id])
   :related-cards (find-related-cards state card-id)  ; expensive computation
   :activity-score (compute-activity state card-id)})

;; Component uses data-fn for complex data, inline get-in for simple
(defn review-card-advanced [{:keys [card-hash state]}]
  (let [card (get-in state [:nodes card-hash])           ; Simple - inline
        analytics (card-analytics-data state card-hash)] ; Complex - data-fn
    [:div.card
     [:h3 (:text card)]
     [:div.analytics analytics]]))
```

**Use data-fn when:**
- Data derivation is complex/expensive to compute
- Same computation needed by multiple components
- Testing derivation logic independently is valuable

**Default to inline `get-in` when:**
- Simple data access (single get-in call)
- Component-specific data needs
- Clarity benefits from seeing data access in situ

### When This Breaks Down

**If future requirements emerge:**

1. **Plugin-on-plugin dependencies** (Plugin B reads Plugin A's derived data)
   - Add topo-sort to `plugins.registry/run-all`
   - Plugins declare dependencies via metadata
   - Update catalog with dependency graph

2. **100+ derived indexes** (catalog too large)
   - Generate catalog from plugin metadata
   - Keep hand-written summaries for common indexes

3. **Cross-plugin composition** (complex derived data pipelines)
   - Introduce "computed" plugins that read `db[:derived]`
   - Document dependencies in catalog

**Current system has none of these problems.**

## Comparison to Alternatives

| Aspect | Catalog Only | Macro | Metadata |
|--------|--------------|-------|----------|
| Discovery | Grep catalog | Grep `defcomponent` | Read `^{:props}` |
| Data resolution | Manual `get-in` | Runtime auto-resolve | Resolver layer |
| Data flow | Explicit | Hidden | Explicit |
| Complexity | Low | High (macro) | Medium (aggregation) |
| Boilerplate | Medium | Low | High (prop threading) |
| Debuggability | High | Low | Medium |
| LLM learnability | High | Medium | Medium |

**For solo dev + LLM generation: Catalog Only wins.**

### Why Manifests Were Rejected: No Runtime Resolver

**The critical issue with manifests:** They're only valuable if something **consumes** them.

**Manifests WITH resolver (like re-frame):**
```clojure
;; Manifest declares needs
(c/defcomponent review-card
  {:needs {:citations [:derived :ref/citations :card-hash]}}
  (fn [{:keys [citations]}]  ; Auto-resolved by framework!
    [:div citations]))

;; Consumer just passes params - framework wires everything
[review-card {:card-hash "123"}]  ; Works!
```

**Manifests WITHOUT resolver (our system):**
```clojure
;; Manifest is just documentation - nothing reads it!
(c/defcomponent review-card
  {:needs {:citations [:derived :ref/citations :card-hash]}}  ; Unused!
  (fn [{:keys [citations]}]
    [:div citations]))

;; Consumer STILL has to do manual wiring:
(let [citations (get-in state [:derived :ref/citations card-hash])]
  [review-card {:citations citations :card-hash card-hash}])
;; The manifest saved zero work - it's pure ceremony!
```

**What you'd need to build for manifests to be useful:**
1. Runtime wiring layer to read manifests and auto-resolve data
2. Template substitution (replace `:card-hash` placeholder with actual value)
3. Error handling when paths don't exist
4. Debugging tools when auto-resolution fails

**Verdict:** Manifests without resolver = boilerplate without benefit. Function signatures already document what components need (via destructuring).

### Industry Validation: Svelte 5 (2024)

Svelte 5 faced the same decision: auto-wiring vs explicit data flow.

**Svelte 4 (implicit/auto-wiring):**
```javascript
let count = 0;  // Compiler magically makes this reactive
$: doubled = count * 2;  // Compiler infers dependency
```

**Svelte 5 (explicit):**
```javascript
let count = $state(0);           // Developer declares reactivity
let doubled = $derived(count * 2);  // Explicit dependency
```

**Why they switched to explicit (from Svelte 5 docs):**
- "Figuring out which values are reactive and which aren't can get tricky"
- Large component trees require debuggability over convenience
- Explicit dependencies make data flow clear
- Type safety requires visible contracts

**Key quote:** "Prop drilling is documentation, not a code smell."

**This validates our choice:** When generating 100+ components (especially via LLMs), explicit data flow beats hidden magic.

**Sources:**
- Svelte 5 Runes Introduction (2024)
- Frontend Masters: Fine-Grained Reactivity in Svelte 5
- Research report: `.architect/analysis/svelte-5-component-patterns.md` (2025-10-24)

## Examples

### Component Reading Derived Data

```clojure
(defn backlinks-panel
  "Shows all nodes that link to current node."
  [{:keys [node-id state]}]
  (let [;; Look up in catalog: :ref/backlinks-by-kind at [:derived ...]
        all-backlinks (get-in state [:derived :ref/backlinks-by-kind])
        link-backlinks (get-in all-backlinks [:link node-id])
        highlight-backlinks (get-in all-backlinks [:highlight node-id])]
    [:div.backlinks
     [:h3 "Backlinks"]
     [:div "Links: " (count link-backlinks)]
     [:div "Highlights: " (count highlight-backlinks)]
     (for [source-id link-backlinks]
       ^{:key source-id}
       [:div [:a {:href source-id} source-id]])]))
```

### Intent Using Derived Data

```clojure
(defn smart-link-intent
  "Only create link if target has < 10 citations."
  [db source-id target-id]
  (let [citations (get-in db [:derived :ref/citations target-id] 0)]
    (when (< citations 10)
      {:op :update-node
       :id source-id
       :props {:refs (conj (get-in db [:nodes source-id :props :refs] [])
                           {:target target-id :kind :link})}})))
```

### LLM Discovery Flow

```
LLM: "I want to show citation counts"
→ greps: rg ":ref/citations" plugins/catalog.cljc
→ finds: {:ref/citations {:path [:derived :ref/citations node-id]}}
→ writes: (get-in state [:derived :ref/citations node-id])
```

**No metadata needed. No declarations. Just grep + copy.**

## References

- ADR 012: Selection as Boolean vs Refs (plugin patterns)
- `plugins/registry.cljc` (plugin system)
- `src/core/interpret.cljc` (derive-db pipeline)
- Tournament evaluation: `/tmp/codex-architecture-review.txt` (2025-10-17)

## Decision Record

- **Date:** 2025-10-17
- **Decided by:** Solo dev + GPT-5 Codex (high reasoning)
- **Context:** Component API design for LLM-driven generation at scale
- **Alternatives considered:** defcomponent macro, metadata annotations
- **Outcome:** Catalog-only approach, no runtime declarations

### Addendum: 2025-10-24

**Re-evaluation:** Tournament comparison of all three approaches (Gemini explicit, Codex manifests, Grok slots) + Svelte 5 research

**Findings:**
- Tournament showed approaches are **architecturally equivalent** (INVALID status = unanimous agreement)
- Key difference: Where complexity lives (component vs consumer vs framework)
- Svelte 5 independently validated explicit approach in 2024 for same reasons
- Manifests require runtime resolver to provide value; without it, they're ceremony without benefit
- Data-fn pattern identified as useful escape hatch for complex derivations

**Validation:** Original decision reaffirmed. Catalog-only approach remains optimal for this project's constraints.

**Tournament results:** `/tmp/component-architecture-tournament-*.json`
