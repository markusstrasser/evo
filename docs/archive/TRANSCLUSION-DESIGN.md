# Block Transclusion Design

**Status:** Design Phase
**Priority:** High
**Complexity:** Medium

## Overview
Implement block transclusion (embedding blocks within other blocks) using `((block-id))` syntax, inspired by Logseq.

## User Story
As a user, I want to reference/embed other blocks inline so that I can:
- Reuse content without duplication
- Create bidirectional links between blocks
- Build knowledge graphs

## Syntax
```
Plain text with ((block-id-123)) embedded reference.
```

## Architecture

### 1. Data Model (Minimal Changes Needed)

**Existing:**
- Blocks already have `:id` field
- Blocks have `:text` property

**New:**
- `:refs` - Set of block IDs referenced in text (computed on parse)
- Store in `:props {:refs #{...}}`

### 2. Parser Layer

**File:** Create `src/parser/block_refs.cljc`

```clojure
(ns parser.block-refs
  "Parser for block references in text.")

(def block-ref-pattern #"\(\(([a-zA-Z0-9\-]+)\)\)")

(defn extract-refs
  "Extract all block references from text.
   Returns vector of [full-match id offset] tuples."
  [text]
  (re-seq block-ref-pattern text))

(defn parse-refs
  "Parse text and return set of referenced block IDs."
  [text]
  (set (map second (extract-refs text))))
```

### 3. Rendering Layer

**File:** Create `src/components/block_ref.cljs`

```clojure
(ns components.block-ref
  "Block reference component.")

(defn BlockRef
  "Render an embedded block reference.

   Props:
   - db: application database
   - block-id: ID of block to reference
   - ref-set: Set of block IDs in current reference chain (cycle detection)"
  [{:keys [db block-id ref-set]}]
  (let [self-ref? (contains? (or ref-set #{}) block-id)
        block (get-in db [:nodes block-id])
        text (get-in block [:props :text] "")]
    (if self-ref?
      ;; Prevent infinite loop
      [:span.block-ref.self-ref
       {:title "Self-reference (cycle detected)"}
       "((circular))"]
      ;; Render referenced block inline
      [:span.block-ref
       {:title (str "Reference to: " block-id)
        :data-block-id block-id}
       (if block
         text
         [:span.block-ref-missing "((" block-id " not found))"])])))
```

**File:** Update `src/components/block.cljs` to parse and render refs

```clojure
;; Add to block.cljs
(defn parse-and-render-text
  "Parse text for block references and render with BlockRef components.

   Returns vector of strings and BlockRef components."
  [db text ref-set]
  (let [pattern #"\(\(([a-zA-Z0-9\-]+)\)\)"
        parts (str/split text pattern)]
    ;; Interleave plain text with BlockRef components
    ;; TODO: Implement splitting and component insertion
    ))
```

### 4. Intent Layer (Update Content)

**File:** Update `src/plugins/editing.cljc`

```clojure
;; Update :update-content handler to parse refs
(intent/register-intent! :update-content
  {:handler (fn [db {:keys [block-id text]}]
              (let [refs (parser.block-refs/parse-refs text)]
                [{:op :update-node
                  :id block-id
                  :props {:text text
                          :refs refs}}]))})
```

### 5. Autocomplete (Future Enhancement)

**Not implementing in v1** - For now, users must manually type block IDs.

**Future v2:**
- Detect `((` typing trigger
- Show block search dropdown
- Insert block ID on selection

## Implementation Plan

### Phase 1: Core Parsing & Data (30 min)
1. ✅ Create `parser/block_refs.cljc` with pattern matching
2. ✅ Update `:update-content` to compute `:refs` set
3. ✅ Add tests for parser

### Phase 2: Rendering (30 min)
4. ✅ Create `components/block_ref.cljs`
5. ✅ Update `components/block.cljs` to parse text and render BlockRef components
6. ✅ Add cycle detection via `ref-set`
7. ✅ Add CSS styling for `.block-ref`

### Phase 3: Testing (15 min)
8. ✅ Test basic reference: `((a))` in block b
9. ✅ Test missing reference: `((nonexistent))`
10. ✅ Test self-reference: `((a))` in block a
11. ✅ Test mutual reference: a→b, b→a
12. ✅ Test styling and visual appearance

## Test Cases

```clojure
;; Test 1: Basic reference
{:nodes {"a" {:type :block :props {:text "Source block"}}
         "b" {:type :block :props {:text "Refers to ((a))"}}}}
;; Expected: Block b renders "Refers to Source block"

;; Test 2: Missing reference
{:nodes {"a" {:type :block :props {:text "Refers to ((missing))"}}}}
;; Expected: Block a renders "Refers to ((missing not found))"

;; Test 3: Self-reference (cycle)
{:nodes {"a" {:type :block :props {:text "Self ((a))"}}}}
;; Expected: Block a renders "Self ((circular))"

;; Test 4: Mutual reference (cycle)
{:nodes {"a" {:type :block :props {:text "A refs ((b))"}}
         "b" {:type :block :props {:text "B refs ((a))"}}}}
;; Expected: Both blocks detect cycle and show ((circular))
```

## CSS Styling

```css
.block-ref {
  background-color: #e8f4f8;
  border-left: 2px solid #4a9eff;
  padding: 2px 4px;
  border-radius: 2px;
  font-family: monospace;
  font-size: 0.9em;
  cursor: pointer;
}

.block-ref-missing {
  background-color: #ffe8e8;
  border-left: 2px solid #ff4a4a;
  color: #cc0000;
}

.block-ref.self-ref {
  background-color: #fff3cd;
  border-left: 2px solid #ffc107;
  color: #856404;
}
```

## Future Enhancements (v2)

1. **Autocomplete** - Type `((` to trigger block search
2. **Bidirectional refs** - Show "Referenced by" section
3. **Click to navigate** - Click block ref to jump to source
4. **Hover preview** - Show full block content on hover
5. **Nested refs** - Support refs within refs (with depth limit)
6. **Ref graph** - Visualize reference relationships

## Logseq Comparison

| Feature | Logseq | Evo v1 | Evo v2 (Future) |
|---------|--------|--------|-----------------|
| Syntax | `((uuid))` | `((id))` | Same |
| Autocomplete | ✅ `((` trigger | ❌ Manual | ✅ Planned |
| Rendering | ✅ Inline embed | ✅ Inline embed | Same |
| Cycle detection | ✅ ref-set | ✅ ref-set | Same |
| Click to navigate | ✅ | ❌ | ✅ Planned |
| Hover preview | ✅ | ❌ | ✅ Planned |
| Bidirectional | ✅ | ❌ | ✅ Planned |

## References
- Logseq implementation: `~/Projects/best/logseq/src/main/frontend/components/block.cljs`
- Parser research: See subagent report above
