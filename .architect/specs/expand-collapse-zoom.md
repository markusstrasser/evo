# Expand/Collapse & Zoom - Implementation Spec

**Status:** Not Implemented
**Priority:** High (Essential for large outlines)
**Dependencies:** Basic navigation (✅), Session state (✅)
**Related Specs:** `smart-editing-behaviors.md`, `outliner-user-stories.md`

---

## Overview

Expand/collapse and zoom features enable users to manage visual complexity in large outlines. These features control which parts of the tree are visible, allowing focus on specific branches without losing context.

**Key Concepts:**
- **Expand/Collapse:** Show/hide children of blocks (folding)
- **Zoom:** Focus on a single block and its descendants (changes root context)
- **Persistence:** Fold states survive navigation and reloads
- **Visual Indicators:** Bullets show expand/collapse state

---

## 1. Data Model

### 1.1 Fold State Storage

**Location:** Session state (ephemeral, not in history)

**Schema:**
```clojure
;; In kernel/db.cljc
{:nodes {"session/ui" {:type :session
                       :props {:editing-block-id nil
                               :cursor {...}
                               :folded #{} ;; Set of collapsed block IDs
                               :zoom-stack []}}}} ;; History of zoom levels
```

**Why Set?**
- Fast lookup: `(contains? folded block-id)`
- Efficient add/remove: `(conj folded id)`, `(disj folded id)`
- Serializable for persistence

**Persistence Strategy:**
```clojure
;; LocalStorage key: "evo.fold-state.{page-id}"
{:folded #{"block-123" "block-456"}
 :zoom-stack [{:block-id "block-root" :scroll-pos 0}
              {:block-id "block-123" :scroll-pos 150}]}
```

---

### 1.2 Derived State

**Query:** Is block folded?
```clojure
;; In kernel/query.cljc
(defn folded? [db block-id]
  (contains? (get-in db [:nodes "session/ui" :props :folded] #{})
             block-id))

(defn has-children? [db block-id]
  (seq (get-in db [:children-by-parent block-id])))

(defn collapsible? [db block-id]
  (and (has-children? db block-id)
       (not (folded? db block-id))))

(defn expandable? [db block-id]
  (and (has-children? db block-id)
       (folded? db block-id)))
```

**Usage in Rendering:**
```clojure
;; In components/block.cljs
(defn Block [{:keys [db block-id ...]}]
  (let [folded? (q/folded? db block-id)
        children (when-not folded?
                   (get-in db [:children-by-parent block-id]))]
    [:div.block
     [Bullet {:folded? folded? :has-children? (q/has-children? db block-id)}]
     [Content ...]
     (when children
       [Children ...])]))
```

---

## 2. Expand/Collapse Operations

### 2.1 Toggle Single Block (Mod+;)

**Behavior:**
- If block has children AND is expanded → collapse
- If block has children AND is collapsed → expand
- If block has no children → no-op

**Intent:**
```clojure
;; New plugin: plugins/folding.cljc
(ns plugins.folding
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q])
  #?(:clj (:require [kernel.intent :refer [defintent]]))
  #?(:cljs (:require-macros [kernel.intent :refer [defintent]])))

(defintent :toggle-fold
  {:sig [db {:keys [block-id]}]
   :doc "Toggle expand/collapse state for a block."
   :spec [:map [:type [:= :toggle-fold]] [:block-id {:optional true} :string]]
   :ops (let [id (or block-id (q/focus db))
              folded-set (get-in db [:nodes const/session-ui-id :props :folded] #{})
              currently-folded? (contains? folded-set id)
              new-folded (if currently-folded?
                          (disj folded-set id)
                          (conj folded-set id))]
          (when (q/has-children? db id)
            [{:op :update-node
              :id const/session-ui-id
              :props {:folded new-folded}}]))})
```

**Keymap:**
```clojure
;; In bindings_data.cljc
:global [[{:key ";" :mod true} :toggle-fold]]
```

**Visual Feedback:**
- Bullet changes: `▾` (expanded) ↔ `▸` (collapsed)
- Animation: smooth height transition (CSS)

---

### 2.2 Expand All Children (Mod+Down)

**Behavior:**
- Recursively expand focused block and ALL descendants
- Removes block and all descendant IDs from folded set

**Intent:**
```clojure
(defn all-descendant-ids
  "Get all descendant IDs of a block (DFS traversal)."
  [db block-id]
  (let [children (get-in db [:children-by-parent block-id] [])]
    (concat children
            (mapcat #(all-descendant-ids db %) children))))

(defintent :expand-all
  {:sig [db {:keys [block-id]}]
   :doc "Recursively expand a block and all descendants."
   :spec [:map [:type [:= :expand-all]] [:block-id {:optional true} :string]]
   :ops (let [id (or block-id (q/focus db))
              descendants (all-descendant-ids db id)
              all-ids (conj descendants id)
              folded-set (get-in db [:nodes const/session-ui-id :props :folded] #{})
              new-folded (apply disj folded-set all-ids)]
          [{:op :update-node
            :id const/session-ui-id
            :props {:folded new-folded}}])})
```

**Keymap:**
```clojure
:global [[{:key "ArrowDown" :mod true} :expand-all]]
```

**Performance:**
- Large trees (1000+ descendants): ~10ms to traverse
- Set operations are O(n log n) in CLJS
- Acceptable for interactive use

**Optimization (Future):**
```clojure
;; Memoize descendant calculation in derived state
{:derived {:descendants-of {"block-id" ["child1" "child2" ...]}}}
```

---

### 2.3 Collapse All Children (Mod+Up)

**Behavior:**
- Collapse focused block (hide all children)
- Does NOT recursively collapse descendants (only top-level fold)

**Intent:**
```clojure
(defintent :collapse
  {:sig [db {:keys [block-id]}]
   :doc "Collapse a block (hide children)."
   :spec [:map [:type [:= :collapse]] [:block-id {:optional true} :string]]
   :ops (let [id (or block-id (q/focus db))
              folded-set (get-in db [:nodes const/session-ui-id :props :folded] #{})
              new-folded (conj folded-set id)]
          (when (q/has-children? db id)
            [{:op :update-node
              :id const/session-ui-id
              :props {:folded new-folded}}]))})
```

**Keymap:**
```clojure
:global [[{:key "ArrowUp" :mod true} :collapse]]
```

**Note:** Logseq's Mod+Up is "collapse all children recursively", but simpler UX is just "collapse this block". Can add `:collapse-all` later if needed.

---

### 2.4 Toggle All Blocks on Page (T O)

**Behavior:**
- If ANY block is collapsed → expand ALL blocks on page
- If ALL blocks are expanded → collapse ALL top-level blocks

**Intent:**
```clojure
(defn all-block-ids-on-page
  "Get all block IDs under a page (recursive)."
  [db page-id]
  (all-descendant-ids db page-id))

(defn any-folded-on-page?
  "Check if any blocks on page are currently folded."
  [db page-id]
  (let [all-ids (all-block-ids-on-page db page-id)
        folded-set (get-in db [:nodes const/session-ui-id :props :folded] #{})]
    (some folded-set all-ids)))

(defintent :toggle-all-folds
  {:sig [db {:keys [page-id]}]
   :doc "Toggle all folds on a page. Expand all if any collapsed, else collapse all top-level."
   :spec [:map [:type [:= :toggle-all-folds]] [:page-id :string]]
   :ops (let [all-ids (all-block-ids-on-page db page-id)
              folded-set (get-in db [:nodes const/session-ui-id :props :folded] #{})
              any-folded? (some folded-set all-ids)
              new-folded (if any-folded?
                          ;; Expand all
                          (apply disj folded-set all-ids)
                          ;; Collapse all top-level
                          (let [top-level (get-in db [:children-by-parent page-id])]
                            (apply conj folded-set top-level)))]
          [{:op :update-node
            :id const/session-ui-id
            :props {:folded new-folded}}])})
```

**Keymap:**
```clojure
;; Two-key sequence: T then O
:global [[{:key "t"} :prefix-t]  ;; Prefix handler
         [{:key "o" :prefix :t} :toggle-all-folds]]
```

**Prefix Key Implementation:**
```clojure
;; In keymap/core.cljc - add prefix support
(defn- parse-key-sequence [event prefix-state]
  "Parse key event considering prefix state."
  (if prefix-state
    {:key (:key event) :prefix prefix-state}
    {:key (:key event)}))

;; In blocks_ui.cljs - track prefix state
(defonce !prefix-state (atom nil))

(defn handle-global-keydown [e]
  (let [event (keymap/parse-dom-event e)
        intent-type (keymap/resolve-intent-type event @!prefix-state)]
    (case intent-type
      :prefix-t (reset! !prefix-state :t)
      :toggle-all-folds (do
                          (reset! !prefix-state nil)
                          (handle-intent {:type :toggle-all-folds
                                         :page-id current-page-id}))
      ;; Clear prefix on any other key
      (reset! !prefix-state nil))))
```

**Priority:** Medium (nice-to-have, not critical for MVP)

---

## 3. Zoom (Focus Mode)

### 3.1 Zoom In (Mod+. or Alt+Right)

**Behavior:**
- Change rendering root to focused block
- Push current context onto zoom stack
- Scroll to top
- Breadcrumb shows zoom path

**Data Structure:**
```clojure
;; Zoom stack tracks navigation history
{:zoom-stack [{:block-id "page-root" :scroll-pos 0}
              {:block-id "block-123" :scroll-pos 150}]}
              ;; ↑ previous level     ↑ current level
```

**Intent:**
```clojure
(defintent :zoom-in
  {:sig [db {:keys [block-id]}]
   :doc "Zoom into a block (make it the rendering root)."
   :spec [:map [:type [:= :zoom-in]] [:block-id {:optional true} :string]]
   :ops (let [id (or block-id (q/focus db))
              current-stack (get-in db [:nodes const/session-ui-id :props :zoom-stack] [])
              current-root (or (-> current-stack last :block-id) :doc)
              current-scroll (js/window.scrollY)
              new-stack (conj current-stack
                             {:block-id current-root
                              :scroll-pos current-scroll})]
          (when (q/has-children? db id)
            [{:op :update-node
              :id const/session-ui-id
              :props {:zoom-stack new-stack
                      :zoom-root id}}]))})
```

**Rendering:**
```clojure
;; In blocks_ui.cljs
(defn Outline [{:keys [db on-intent]}]
  (let [zoom-root (get-in db [:nodes const/session-ui-id :props :zoom-root] :doc)
        children (get-in db [:children-by-parent zoom-root])]
    [:div.outline
     (when (not= zoom-root :doc)
       [Breadcrumb {:db db :zoom-stack (...)}])
     (map #(Block {:db db :block-id % ...}) children)]))
```

**Keymap:**
```clojure
:global [[{:key "." :mod true} :zoom-in]
         [{:key "ArrowRight" :alt true} :zoom-in]]  ;; Windows/Linux
```

**Edge Cases:**
- Zooming into leaf block (no children): no-op
- Maximum zoom depth: no limit (trust user)
- Fold state preserved when zooming

---

### 3.2 Zoom Out (Mod+, or Alt+Left)

**Behavior:**
- Pop from zoom stack
- Restore previous rendering root
- Restore scroll position
- If stack empty: no-op

**Intent:**
```clojure
(defintent :zoom-out
  {:sig [db _]
   :doc "Zoom out to previous level."
   :spec [:map [:type [:= :zoom-out]]]
   :ops (let [current-stack (get-in db [:nodes const/session-ui-id :props :zoom-stack] [])
              new-stack (pop current-stack)
              previous-level (last new-stack)
              new-root (or (:block-id previous-level) :doc)]
          (when (seq current-stack)
            [{:op :update-node
              :id const/session-ui-id
              :props {:zoom-stack new-stack
                      :zoom-root new-root}}
             ;; Restore scroll after render
             ;; (requires DOM effect - see below)
             ]))})
```

**Scroll Restoration:**
```clojure
;; In blocks_ui.cljs - after zoom-out intent
(when (= intent-type :zoom-out)
  (let [scroll-pos (get-in @!db [:nodes const/session-ui-id
                                  :props :zoom-stack]
                           (-> @!db
                               (get-in [:nodes const/session-ui-id :props :zoom-stack])
                               last
                               :scroll-pos))]
    (handle-intent {:type :zoom-out})
    (js/setTimeout #(js/window.scrollTo 0 scroll-pos) 0)))
```

**Keymap:**
```clojure
:global [[{:key "," :mod true} :zoom-out]
         [{:key "ArrowLeft" :alt true} :zoom-out]]
```

---

### 3.3 Breadcrumb Component

**UI:**
```
Home > Projects > Evo Implementation > Block Editing
         └─────────── Zoom Path ────────────┘
```

**Implementation:**
```clojure
(defn Breadcrumb [{:keys [db zoom-stack]}]
  (let [path (map (fn [{:keys [block-id]}]
                   {:id block-id
                    :text (get-in db [:nodes block-id :props :text] "Untitled")})
                 zoom-stack)]
    [:div.breadcrumb
     {:style {:padding "10px"
              :background "#f5f5f5"
              :border-bottom "1px solid #ddd"}}
     (interpose " > "
       (map (fn [{:keys [id text]}]
              [:a {:href "#"
                   :on {:click (fn [e]
                                 (.preventDefault e)
                                 (on-intent {:type :zoom-to :block-id id}))}}
               (subs text 0 (min 30 (count text)))])
            path))]))
```

**Intent:** `:zoom-to` (jump directly to level in stack)
```clojure
(defintent :zoom-to
  {:sig [db {:keys [block-id]}]
   :doc "Zoom to specific block in zoom stack (breadcrumb click)."
   :spec [:map [:type [:= :zoom-to]] [:block-id :string]]
   :ops (let [current-stack (get-in db [:nodes const/session-ui-id :props :zoom-stack])
              target-idx (first (keep-indexed
                                 (fn [i {:keys [block-id] :as level}]
                                   (when (= block-id block-id) i))
                                 current-stack))
              new-stack (when target-idx (subvec current-stack 0 (inc target-idx)))]
          (when new-stack
            [{:op :update-node
              :id const/session-ui-id
              :props {:zoom-stack new-stack
                      :zoom-root block-id}}]))})
```

---

## 4. Visual Design

### 4.1 Bullet Indicators

**States:**
```
• = Leaf (no children)
▾ = Expanded (has children, showing them)
▸ = Collapsed (has children, hidden)
```

**CSS:**
```css
.block-bullet {
  cursor: pointer;
  user-select: none;
  margin-right: 8px;
  font-size: 12px;
  color: #666;
}

.block-bullet:hover {
  color: #0066cc;
}

.block-bullet.collapsed::before {
  content: '▸';
}

.block-bullet.expanded::before {
  content: '▾';
}

.block-bullet.leaf::before {
  content: '•';
  cursor: default;
}
```

**Component:**
```clojure
(defn Bullet [{:keys [db block-id on-intent]}]
  (let [has-children? (q/has-children? db block-id)
        folded? (q/folded? db block-id)
        class (cond
                (not has-children?) "leaf"
                folded? "collapsed"
                :else "expanded")]
    [:span.block-bullet
     {:class class
      :on (when has-children?
            {:click (fn [e]
                      (.stopPropagation e)
                      (on-intent {:type :toggle-fold :block-id block-id}))})}]))
```

---

### 4.2 Collapse Animation

**CSS Transition:**
```css
.block-children {
  overflow: hidden;
  transition: max-height 0.2s ease-out, opacity 0.15s ease-out;
}

.block-children.collapsed {
  max-height: 0;
  opacity: 0;
}

.block-children.expanded {
  max-height: 10000px; /* Large enough for any content */
  opacity: 1;
}
```

**Note:** Using `max-height` instead of `height: auto` because `auto` doesn't animate. Value should be larger than any realistic block tree.

**Alternative (Better Performance):**
```css
/* Use scale + visibility for GPU-accelerated animation */
.block-children.collapsed {
  transform: scaleY(0);
  transform-origin: top;
  visibility: hidden;
}

.block-children.expanded {
  transform: scaleY(1);
  visibility: visible;
}
```

---

## 5. Persistence

### 5.1 LocalStorage Strategy

**Save on Change:**
```clojure
;; In blocks_ui.cljs - watch fold state
(add-watch !db :fold-persistence
  (fn [_ _ old-db new-db]
    (let [old-folded (get-in old-db [:nodes const/session-ui-id :props :folded])
          new-folded (get-in new-db [:nodes const/session-ui-id :props :folded])
          page-id "page"] ;; Get current page ID from db
      (when (not= old-folded new-folded)
        (js/localStorage.setItem
          (str "evo.fold-state." page-id)
          (pr-str {:folded new-folded}))))))
```

**Load on Mount:**
```clojure
(defn load-fold-state [page-id]
  (when-let [stored (js/localStorage.getItem (str "evo.fold-state." page-id))]
    (try
      (cljs.reader/read-string stored)
      (catch js/Error e
        (js/console.error "Failed to load fold state:" e)
        nil))))

;; In main
(defn main []
  (let [fold-state (load-fold-state "page")]
    (when fold-state
      (swap! !db assoc-in
             [:nodes const/session-ui-id :props :folded]
             (:folded fold-state))))
  ...)
```

**Priority:** Medium (fold state should persist across reloads)

---

### 5.2 Zoom Stack Persistence

**Not persisting zoom stack initially** - resets to root on reload.

**Reason:** Zoom is transient navigation, not document state.

**Future:** If users request it, add to localStorage alongside fold state.

---

## 6. Implementation Checklist

### Phase 1: Core Folding
- [ ] Add `:folded` set to session state
- [ ] Implement `folded?` and `has-children?` queries
- [ ] Create `plugins/folding.cljc` with intents
- [ ] Update Bullet component with fold indicators
- [ ] Implement `:toggle-fold` intent
- [ ] Add Mod+; keybinding
- [ ] Add CSS for bullet states

### Phase 2: Bulk Operations
- [ ] Implement `:expand-all` intent (Mod+Down)
- [ ] Implement `:collapse` intent (Mod+Up)
- [ ] Add keybindings
- [ ] Test with large trees (performance)

### Phase 3: Zoom
- [ ] Add `:zoom-stack` to session state
- [ ] Implement `:zoom-in` and `:zoom-out` intents
- [ ] Update Outline component to respect zoom root
- [ ] Implement Breadcrumb component
- [ ] Add Mod+. / Mod+, keybindings
- [ ] Implement scroll position restoration

### Phase 4: Persistence
- [ ] LocalStorage save on fold change
- [ ] LocalStorage load on mount
- [ ] Test across page reloads

### Phase 5: Polish
- [ ] Smooth collapse/expand animations
- [ ] Hover states on bullets
- [ ] Toggle all blocks (T O) with prefix keys
- [ ] Keyboard shortcut hints in UI

---

## 7. Testing Strategy

### Unit Tests
```clojure
(deftest toggle-fold-adds-to-set
  (let [db (-> (db/empty-db)
               (tx/interpret [{:op :create-node :id "a"}
                              {:op :create-node :id "b"}
                              {:op :place :id "b" :under "a"}]))
        result (api/dispatch db {:type :toggle-fold :block-id "a"})]
    (is (contains? (get-in result [:db :nodes const/session-ui-id :props :folded])
                   "a"))))

(deftest expand-all-removes-descendants
  ;; Setup: tree with "a" > "b" > "c", all folded
  ;; Action: expand-all on "a"
  ;; Assert: folded set is empty
  )

(deftest zoom-in-pushes-to-stack
  ;; Action: zoom into block "a"
  ;; Assert: zoom-stack has one entry, zoom-root is "a"
  )
```

### Integration Tests
```clojure
(deftest fold-unfold-cycle
  ;; 1. Create block with children
  ;; 2. Toggle fold (collapse)
  ;; 3. Assert children not rendered
  ;; 4. Toggle fold (expand)
  ;; 5. Assert children rendered
  )

(deftest zoom-navigation
  ;; 1. Zoom into block "a"
  ;; 2. Zoom into child "b"
  ;; 3. Zoom out
  ;; 4. Assert: rendering root is "a"
  ;; 5. Zoom out
  ;; 6. Assert: rendering root is :doc
  )
```

### Performance Tests
```clojure
(deftest large-tree-expand-all
  ;; Create tree with 1000 blocks
  ;; Measure time to expand all
  ;; Assert: < 50ms on average hardware
  )
```

---

## 8. Edge Cases & Gotchas

### 8.1 Folding
- **Deleting folded block:** Fold state remains (harmless)
- **Moving folded block:** Fold state follows block ID
- **Folding while editing:** Should still work (fold affects children only)
- **Undo folding:** Fold state is NOT in history (by design - it's UI state)

### 8.2 Zoom
- **Zooming into deleted block:** Check existence before zoom
- **Zooming while editing:** Exit edit mode first? Or allow?
  - **Decision:** Allow (editing is independent of zoom level)
- **Deep zoom (10+ levels):** Stack grows but no performance issue
- **Breadcrumb overflow:** Truncate long block titles to 30 chars

### 8.3 Persistence
- **Page deleted:** Orphaned localStorage keys (cleanup on mount)
- **Block IDs change:** UUIDs are stable, but temp IDs break
  - **Mitigation:** Don't persist fold state for temp IDs
- **localStorage full:** Catch quota exceeded error, fall back to in-memory

---

## 9. Performance Optimizations

### Current Approach (Simple)
- Fold state is plain set: O(log n) lookup
- Rendering skips folded children: saves React reconciliation
- No special memoization

### Future Optimizations (If Needed)
1. **Memoize descendant calculation:**
   ```clojure
   {:derived {:descendants-of {"block-id" [...]}}}
   ```
   - Recalculate only on tree structure change
   - Saves O(n) traversal on every expand-all

2. **Virtualize large collapsed sections:**
   - If 1000+ children folded, don't even create React elements
   - Measure: only optimize if profiling shows need

3. **Debounce localStorage writes:**
   - Currently writes on every fold change
   - Could batch writes every 500ms
   - Tradeoff: risk losing state if tab crashes

---

## 10. Open Questions

1. **Should zoom affect selection?**
   - Option A: Keep current selection (even if outside zoom)
   - Option B: Reset selection to zoom root
   - **Decision:** Option A (less surprising)

2. **Collapse while children are selected?**
   - Option A: Collapse and move selection to parent
   - Option B: Don't allow collapsing
   - **Decision:** Option A (user intent is clear)

3. **Animate fold transitions?**
   - Option A: Smooth animation (0.2s)
   - Option B: Instant
   - **Decision:** Option A (better UX), but make fast (0.15s max)

4. **Fold state in undo history?**
   - Current: No (fold is UI state)
   - Alternative: Yes (some apps do this)
   - **Decision:** No - fold state is like scroll position (ephemeral)

---

## 11. References

- Logseq source: `~/Projects/best/logseq/src/main/frontend/handler/editor.cljs` (lines 2301-2418)
- Fold state: `~/Projects/best/logseq/src/main/frontend/state.cljs`
- Breadcrumb: `~/Projects/best/logseq/src/main/frontend/components/block.cljs`
- Zoom implementation: Search for "zoom" in Logseq codebase
- Current DB schema: `src/kernel/db.cljc`
- Session constants: `src/kernel/constants.cljc`
