# ADR 012: Selection as Boolean Property vs Typed Refs

## Status
Accepted

## Context
We need to implement node selection for UI highlighting. Two approaches considered:

1. **Typed refs**: `{:refs [{:target "node" :kind :selection}]}` from cursor to node
2. **Boolean property**: `{:selected? true}` on the node itself

## Decision
Use **boolean properties** for selection. Reserve typed refs for actual relationships (links, citations, references).

## Rationale

### Selection is State, Not Relationship
Selection answers: "Is this node currently selected?"

This is **temporal UI state**, not a permanent relationship. Like `:collapsed?` or `:focused?`, it's a property of the node at this moment.

### Typed Refs are for Relationships
Refs answer: "What does this point to?" or "What points here?"

They model **stable relationships**:
- Document links: "Page A references Page B"
- Citations: "Paper X cites Paper Y"
- Backlinks: "What pages link to this one?"

These persist across sessions and have meaning independent of current UI state.

### Complexity vs Use Case
For single-user, local-first outliner:

**Boolean selection needs:**
- Simple toggle: `{:selected? (not current-selected?)}`
- Query: Filter nodes where `:selected?` is true
- Render: Add CSS class if selected

**Typed refs would add:**
- Cursor node management
- Source tracking (which cursor selected what)
- Backlink computation for every selection change
- Multi-cursor coordination

**We don't need:** Multiple cursors, selection provenance, collaborative editing.

### Plugin API Unchanged
Both approaches use the same plugin pattern:

```clojure
;; Plugin contract stays the same
(defn derive-indexes [db]
  {:selection/active #{...}})
```

Implementation can change without affecting kernel or consumers.

## Implementation

### Storage (Boolean Props)
```clojure
{:nodes
 {"doc-node-123" {:type :paragraph
                  :props {:text "Hello"
                          :selected? true}}}}
```

### Derived Index
```clojure
{:derived
 {:selection/active #{"doc-node-123" "doc-node-456"}}}
```

Computed by filtering nodes with `:selected? true`.

### Intent Compiler
```clojure
(defn toggle-selection-op [db node-id]
  {:op :update-node
   :id node-id
   :props {:selected? (not (get-in db [:nodes node-id :props :selected?]))}})
```

### UI Query
```clojure
(let [selected? (contains? (get-in db [:derived :selection/active]) node-id)]
  [:div {:class (when selected? "selected")} ...])
```

## When to Use Typed Refs

Use typed refs when you need:

1. **Provenance**: "Who/what created this relationship?"
2. **Multiplicity**: Multiple sources pointing to same target
3. **Persistence**: Relationship survives across sessions
4. **Bidirectional queries**: "What does X point to?" AND "What points to X?"
5. **Metadata**: Anchors, timestamps, weights on relationships

Examples:
- `:kind :link` - Document hyperlinks
- `:kind :citation` - Academic citations
- `:kind :highlight` - Text highlights with `:anchor {:range [10 24]}`
- `:kind :embed` - Transclusions

## When to Use Boolean Props

Use boolean properties when you need:

1. **Simple state**: On/off, true/false
2. **Single source of truth**: Only the node cares about this state
3. **Ephemeral**: Doesn't need to persist or may reset on load
4. **No relationships**: Doesn't point to another node

Examples:
- `:selected?` - Currently selected in UI
- `:collapsed?` - Collapsed/expanded state
- `:focused?` - Has keyboard focus
- `:dirty?` - Unsaved changes marker

## Consequences

### Positive
- **Simpler code**: No cursor node management
- **Less ceremony**: Direct property access vs ref traversal
- **Clearer intent**: Boolean for state, refs for relationships
- **Better performance**: No backlink computation for every selection change

### Negative
- **No multi-cursor**: Can't track which cursor selected what (acceptable for MVP)
- **No selection history**: Can't ask "what did cursor-A select 5 steps ago?" (not needed)

### Mitigations
- If multi-cursor needed later, typed refs are already implemented
- Migration path: Boolean → Refs is straightforward (add cursor nodes, convert props to refs)

## Examples

### Toggle Selection
```clojure
;; Generate op
(def op (toggle-selection-op db "doc-node-123"))
;=> {:op :update-node :id "doc-node-123" :props {:selected? true}}

;; Apply
(interpret db [op])
```

### Query Selected Nodes
```clojure
;; From derived index
(get-in db [:derived :selection/active])
;=> #{"doc-node-123" "doc-node-456"}

;; Direct from nodes (before derive)
(->> (:nodes db)
     (filter (fn [[id node]] (get-in node [:props :selected?])))
     (map first)
     set)
```

### Render with Selection
```clojure
(defn node-view [{:keys [db node-id]}]
  (let [selected? (contains? (get-in db [:derived :selection/active]) node-id)
        node (get-in db [:nodes node-id])]
    [:div {:class (when selected? "selected")}
     (get-in node [:props :text])]))
```

## Comparison Table

| Aspect | Boolean Props | Typed Refs |
|--------|--------------|------------|
| Use case | Ephemeral UI state | Persistent relationships |
| Storage | `:props {:selected? true}` | `:props {:refs [{:target ... :kind :selection}]}` |
| Query | Filter by property | Backlinks from derived |
| Provenance | None | Cursor/source tracked |
| Multi-source | No | Yes |
| Complexity | Low | High |
| Best for | Single-user, local | Collaborative, multi-cursor |

## References
- ADR 011: Refs as Policy (typed refs implementation)
- `plugins.refs.core` (ref infrastructure, still used for links/citations)
- `plugins.selection.core` (new boolean-based selection plugin)
