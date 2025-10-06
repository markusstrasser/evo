# Before/After Examples: Key Refactorings

## Example 1: Using medley/filter-vals for Type Filtering

### Before
```clojure
(defn nodes-by-type
  "Filter nodes by type from db."
  [db node-type]
  (filter #(= node-type (:type (val %))) (:nodes db)))
```

### After
```clojure
(defn nodes-by-type
  "Return map of nodes filtered by type.
   Returns seq of [node-id node] tuples for easy reduce/map operations."
  [db node-type]
  (->> (:nodes db)
       (m/filter-vals #(= node-type (:type %)))))
```

### Why Better?
- **Threading macro** (`->>`) shows clear data flow: db → nodes → filtered
- **medley/filter-vals** is more idiomatic than `filter` + `val`
- **Better docstring** explains return value structure
- **Clearer intent**: filtering by value predicate, not entry predicate

---

## Example 2: Extracting Repeated Pattern to Helper

### Before (pattern repeated 5 times)
```clojure
(let [children (get children-by-parent card-id [])]
  ...)
```

### After (single helper)
```clojure
(defn children-of-parent
  "Get children IDs for a parent node ID.
   Returns empty vector if parent has no children."
  [db parent-id]
  (get-in db [:children-by-parent parent-id] []))

;; Usage
(let [children (children-of-parent db card-id)]
  ...)
```

### Why Better?
- **DRY principle**: Single source of truth for default empty vector
- **Descriptive name**: `children-of-parent` vs inline `get`
- **Consistent API**: Takes `db` parameter like other helpers
- **Easier to change**: Update default behavior in one place

---

## Example 3: Threading + keep + when Pattern

### Before (compute-review-history)
```clojure
(let [review-history (reduce
                      (fn [idx [card-id _node]]
                        (let [children (get children-by-parent card-id [])
                              review-children (children-by-type db children :review)]
                          (if (seq review-children)
                            (assoc idx card-id (vec review-children))
                            idx)))
                      {}
                      cards)]
  (wrap-index :srs/review-history review-history))
```

### After
```clojure
(let [review-history (->> cards
                          (keep (fn [[card-id _node]]
                                  (let [children (children-of-parent db card-id)
                                        reviews (child-ids-by-type db children :review)]
                                    (when (seq reviews)
                                      [card-id (vec reviews)]))))
                          (into {}))]
  {:srs/review-history review-history})
```

### Why Better?
- **Threading macro**: Clear transformation pipeline: cards → keep → into
- **keep + when**: Idiomatic filter-and-transform pattern (vs reduce with conditional)
- **Less nesting**: No if-else in reduce function
- **Clearer logic**: "Keep only cards with reviews" vs "assoc if reviews else return unchanged"
- **Direct map literal**: `{:srs/review-history ...}` vs `wrap-index` indirection

---

## Example 4: map + into vs reduce + assoc

### Before (compute-cards-by-deck)
```clojure
(let [cards-by-deck (reduce
                     (fn [idx [deck-id _node]]
                       (let [children (get children-by-parent deck-id [])
                             card-children (children-by-type db children :card)]
                         (assoc idx deck-id (set card-children))))
                     {}
                     decks)]
  (wrap-index :srs/cards-by-deck cards-by-deck))
```

### After
```clojure
(let [cards-by-deck (->> decks
                         (map (fn [[deck-id _node]]
                                (let [children (children-of-parent db deck-id)
                                      card-children (child-ids-by-type db children :card)]
                                  [deck-id (set card-children)])))
                         (into {}))]
  {:srs/cards-by-deck cards-by-deck})
```

### Why Better?
- **More idiomatic**: `map` + `into {}` is the standard pattern for transforming to maps
- **Clearer intent**: "Map each deck to [id, cards] then collect into map"
- **Less manual**: Don't need to manually thread accumulator through reduce
- **Functional style**: Emphasizes transformation over accumulation

---

## Example 5: medley/filter-kv for Map Filtering

### Before (get-due-cards)
```clojure
(->> due-index
     (filter (fn [[due-date _cards]]
               (neg? (compare due-date before-date))))
     (mapcat val)
     set)
```

### After
```clojure
(->> due-index
     (m/filter-kv (fn [due-date _cards]
                    (neg? (compare due-date before-date))))
     vals
     (reduce into #{}))
```

### Why Better?
- **medley/filter-kv**: Purpose-built for filtering maps by key-value predicate
- **Clearer binding**: Direct `due-date` and `_cards` parameters (vs destructuring entry)
- **Efficient union**: `reduce into #{}` combines sets efficiently
- **Better performance**: Avoids creating intermediate seq with `mapcat val`

---

## Example 6: Eliminating Unnecessary Abstraction

### Before (every compute function)
```clojure
(defn wrap-index
  "Wrap computed index result in a namespaced key map."
  [k v]
  {k v})

(defn compute-due-cards [db]
  (let [due-index ...]
    (wrap-index :srs/due-index due-index)))
```

### After
```clojure
(defn compute-due-cards [db]
  (let [due-index ...]
    {:srs/due-index due-index}))
```

### Why Better?
- **Less indirection**: Direct map literal is clearer than helper function
- **Fewer abstractions**: Don't need to learn/remember `wrap-index`
- **More explicit**: Immediately see the map structure being created
- **Standard Clojure**: Using basic language constructs, not custom helpers

---

## Example 7: Threading + keep for Filter-and-Transform

### Before (compute-due-cards)
```clojure
(reduce
  (fn [idx [card-id node]]
    (if-let [due-date (get-in node [:props :srs/due-date])]
      (update idx due-date (fnil conj #{}) card-id)
      idx))
  {}
  cards)
```

### After
```clojure
(->> cards
     (keep (fn [[card-id node]]
             (when-let [due-date (get-in node [:props :srs/due-date])]
               [due-date card-id])))
     (reduce (fn [idx [due-date card-id]]
               (update idx due-date (fnil conj #{}) card-id))
             {}))
```

### Why Better?
- **Separation of concerns**: Filtering separated from grouping
- **keep + when-let**: Idiomatic "map and filter nils" pattern
- **Clearer flow**: "Extract due dates, then group by date"
- **Two-stage pipeline**: Each stage does one thing well
- **Easier debugging**: Can inspect intermediate `[due-date card-id]` tuples

---

## Example 8: Anonymous Function Clarity

### Before (compute-media-by-card)
```clojure
(->> (children-by-type db children :media)
     (map #(hash-map :id % :props (get-in db [:nodes % :props]))))
```

### After
```clojure
(->> (child-ids-by-type db children :media)
     (map (fn [media-id]
            {:id media-id
             :props (get-in db [:nodes media-id :props])})))
```

### Why Better?
- **Named parameter**: `media-id` vs anonymous `%`
- **Map literal**: `{:id ...}` vs `hash-map :id ...`
- **More readable**: Explicit function with descriptive binding
- **Easier to debug**: Can add `println` or `tap>` with `media-id` name

---

## Pattern Summary

### Patterns Introduced
1. **Threading macros** (`->>`) - 8 functions use threading for clarity
2. **keep + when** - 3 functions use this filter-and-transform pattern
3. **map + into** - 3 functions use this for building maps
4. **medley utilities** - 2 functions use `filter-vals` and `filter-kv`

### Patterns Eliminated
1. **wrap-index** - Removed unnecessary abstraction
2. **reduce + assoc** - Replaced with map + into where appropriate
3. **reduce + if** - Replaced with keep + when for filtering

### Result
- More idiomatic Clojure code
- Consistent patterns across functions
- Improved readability and debuggability
- Leverages community-standard utilities (medley)
