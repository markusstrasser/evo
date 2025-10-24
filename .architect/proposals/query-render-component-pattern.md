# Proposal: Query/Render Component Pattern for Aggregations

**Date:** 2025-10-24
**Status:** Draft
**Related:** ADR-014 (Component Discovery via Catalog)

## Summary

Add **query/render config pattern** as an optional escape hatch for components that aggregate/filter data from multiple nodes.

Keep simple function components as default. Use config only when query logic is substantial.

## Motivation

Current ADR-014 recommends catalog-only with inline `get-in`:

```clojure
(defn citation-count [{:keys [block-id state]}]
  (let [count (get-in state [:derived :ref/citations block-id])]
    [:span count " citations"]))
```

This works great for **simple data lookups**. But some components need **substantial query logic**:

```clojure
;; Bibliography: filter all nodes, extract references, format
(def bibliography
  {:query (fn [db _node-id]
            (->> (:nodes db)
                 (filter (fn [[_id node]]
                           (= :component/reference (:type node))))
                 (map (fn [[id node]]
                        (assoc (:props node) :id id)))))
   :render (fn [references]
             [:div.bibliography
              [:h2 "References"]
              [:ol (for [{:keys [id authors title]} references]
                     [:li {:id id}
                      (str (clojure.string/join ", " authors) ". ")
                      [:span.italic title]])]])})
```

**Problem:** Mixing complex query logic with rendering makes testing harder and obscures intent.

## Proposal

### Three Component Patterns

**1. Simple Lookup (Default)**

Use inline `get-in` for direct data access:

```clojure
(defn citation-count [{:keys [block-id state]}]
  (let [count (get-in state [:derived :ref/citations block-id])]
    [:span count " citations"]))
```

**When:** Simple data lookups (1-3 `get-in` calls)

**2. Query/Render Config (Aggregations)**

Use config map for complex filtering/aggregation:

```clojure
(def bibliography
  {:query (fn [db _node-id]
            ;; Complex: filter, map, transform multiple nodes
            (->> (:nodes db)
                 (filter #(= :component/reference (:type %)))
                 (map transform-reference)))
   :render (fn [references]
             ;; Pure rendering
             [:div.bibliography ...])})

;; Usage
(defn paper-view [{:keys [node-id state]}]
  (let [refs ((:query bibliography) state node-id)]
    [(:render bibliography) refs]))
```

**When:**
- Query spans multiple nodes (filtering, aggregating)
- Query has >5 lines of logic
- Want to test query/render independently

**3. Query Helper Function (Middle Ground)**

Separate query as named function, keep component simple:

```clojure
(defn find-references [db]
  (->> (:nodes db)
       (filter #(= :component/reference (:type %)))
       (map transform-reference)))

(defn bibliography [{:keys [state]}]
  (let [refs (find-references state)]
    [:div.bibliography
     [:ol (for [ref refs]
            [:li (:title ref)])]]))
```

**When:**
- Query is reusable but component is not
- Don't need config formality

## Examples

### Good Use Cases

**Bibliography aggregation:**
```clojure
(def bibliography
  {:query (fn [db _] (filter-references db))
   :render (fn [refs] [:div ...])})
```

**Tag cloud:**
```clojure
(def tag-cloud
  {:query (fn [db _]
            (->> (:nodes db)
                 (mapcat #(get-in % [:props :tags]))
                 frequencies
                 (sort-by second >)))
   :render (fn [tag-freqs]
             [:div (for [[tag count] tag-freqs]
                     [:span.tag tag " (" count ")"])])})
```

**Recent activity:**
```clojure
(def recent-activity
  {:query (fn [db _]
            (->> (:nodes db)
                 vals
                 (filter #(recent? %))
                 (sort-by :edited-at)
                 (take 10)))
   :render (fn [nodes]
             [:ul (for [node nodes]
                    [:li (:text node)])])})
```

### Anti-Pattern (Overkill)

**Simple lookups don't need config:**

```clojure
;; ❌ Overkill
(def block-title
  {:query (fn [db id] (get-in db [:nodes id :props :title]))
   :render (fn [title] [:h1 title])})

;; ✅ Just use function
(defn block-title [{:keys [node-id state]}]
  (let [title (get-in state [:nodes node-id :props :title])]
    [:h1 title]))
```

## Implementation

### No Helper Needed (Keep Simple)

Just use the config directly:

```clojure
;; Define
(def bibliography
  {:query (fn [db _] ...)
   :render (fn [refs] ...)})

;; Use
(let [refs ((:query bibliography) state node-id)]
  [(:render bibliography) refs])
```

**Rationale:** Adding helpers adds abstraction. Keep it explicit.

### Optional: Helper if It Gets Repetitive

```clojure
(defn use-query
  "Run component query and render result."
  [component state node-id]
  (let [data ((:query component) state node-id)]
    [(:render component) data]))

;; Usage
(use-query bibliography state node-id)
```

**Decision:** Start without helper. Add only if pattern repeats >10 times.

## Testing

**Benefit: Test query and render independently**

```clojure
(deftest bibliography-query-test
  (let [db {:nodes {"ref1" {:type :component/reference
                            :props {:title "Paper"}}}}
        result ((:query bibliography) db nil)]
    (is (= 1 (count result)))
    (is (= "Paper" (:title (first result))))))

(deftest bibliography-render-test
  (let [mock-refs [{:id "ref1" :title "Paper" :authors ["Smith"]}]
        hiccup ((:render bibliography) mock-refs)]
    (is (= :div (first hiccup)))
    (is (some #(= "Paper" %) (flatten hiccup)))))
```

## Comparison to ADR-014 Patterns

| Pattern | When to Use | Example |
|---------|-------------|---------|
| **Inline get-in** | Simple lookups | `(get-in state [:derived :ref/citations id])` |
| **Query helper fn** | Reusable query, simple render | `(find-references db)` |
| **Query/render config** | Complex aggregation, want test split | `{:query ... :render ...}` |
| **Data-fn** | Expensive computation, multiple consumers | `(compute-relevance state id)` |

**All four patterns coexist.** Choose based on complexity.

## Consequences

### Positive

- **Testability** - Query and render separate
- **Clarity** - Complex query logic has clear boundary
- **Reusability** - Could swap queries, keep render (or vice versa)
- **No magic** - Just data, no runtime resolution

### Negative

- **More patterns** - Now 3 ways to write components (simple, query/render, data-fn)
- **Boilerplate** - Config map more verbose than single function
- **Awkward invocation** - `((:query comp) db id)` vs `(query-fn db id)`

### Neutral

- **Not opinionated** - Pattern available but not required
- **Opt-in complexity** - Use only when query warrants it

## Decision

**Add query/render pattern to ADR-014 as optional pattern for aggregations.**

Update ADR-014 section "Escape Hatch: Data Functions for Complex Derivations" to include this.

Don't add helpers yet - wait for >10 uses.

## Open Questions

1. **Should we standardize query signature?**
   - Currently: `(fn [db node-id] ...)`
   - Could require: `(fn [{:keys [db node-id]}] ...)` for consistency?
   - **Lean:** Keep flexible for now

2. **Should render take map or positional args?**
   - Map: `(fn [{:keys [references]}] ...)`
   - Positional: `(fn [references] ...)`
   - **Lean:** Positional (simpler, query output = render input)

3. **Where do these component configs live?**
   - With components: `src/components/bibliography.cljs`
   - Separate: `src/queries/bibliography.cljs`
   - **Lean:** Same file as usage, no separate namespace

4. **Naming convention?**
   - Config: `bibliography` (noun)
   - Function: `bibliography-component` (suffix)
   - **Lean:** Noun for config, function for simple components

## References

- ADR-014: Component Discovery via Catalog
- Svelte 5 pattern: Explicit reactivity (validates separation of concerns)
- re-frame subscriptions: Similar query/view split (but reactive)
- Current codebase: `src/app/blocks_ui.cljs` (simple function components)

## Next Steps

**If accepted:**
1. Update ADR-014 with this pattern
2. Implement bibliography example in codebase
3. Document in `docs/components.md`
4. Add to LLM component template

**If rejected:**
- Keep ADR-014 as-is (inline get-in only)
- Use helper functions when query is complex
