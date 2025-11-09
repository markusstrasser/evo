# Replicant Quick Reference

## Event Handler Gotchas

### ✅ Correct syntax for input values
```clojure
[:input {:on {:input [[:action-name :event/target.value]]}}]
```

### ❌ Common mistakes
```clojure
;; WRONG - dot instead of slash
:event.target/value

;; WRONG - this is for internal use
[:replicant/input-value]
```

## Required Middleware

You MUST add `interpolate-actions` to resolve event placeholders:

```clojure
(defn interpolate-actions [event actions]
  (clojure.walk/postwalk
   (fn [x]
     (case x
       :event/target.value (.. event -target -value)
       :event/target.checked (.. event -target -checked)
       x))
   actions))

(r/set-dispatch!
 (fn [event-data handler-data]
   (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
     (let [dom-event (:replicant/dom-event event-data)
           enriched-actions (interpolate-actions dom-event handler-data)]
       (handle-actions event-data enriched-actions)))))
```

## Common Event Placeholders

- `:event/target.value` - Input/select/textarea value
- `:event/target.checked` - Checkbox checked state
- `:event/key` - Keyboard key pressed
- `:event/prevent-default` - Call preventDefault()

## Lifecycle Hooks

Replicant provides lifecycle hooks similar to React's component lifecycle, but uses a different API.

### ⚠️ NOT React's `:ref`

```clojure
;; ❌ WRONG - This is React, not Replicant
[:span {:ref (fn [elem] (.focus elem))}]

;; ✅ CORRECT - Use :replicant/on-mount or :replicant/on-render
[:span {:replicant/on-render (fn [{:replicant/keys [node]}]
                                (.focus node))}]
```

### Available Hooks

- `:replicant/on-mount` - Called when element is first added to DOM
- `:replicant/on-render` - Called on every render (mount and updates)
- `:replicant/on-update` - Called only on updates (not initial mount)
- `:replicant/on-unmount` - Called when element is removed from DOM

### Hook Parameters

Lifecycle hooks receive a map with these keys:

```clojure
{:replicant/trigger :replicant.trigger/life-cycle
 :replicant/life-cycle :replicant.life-cycle/mount  ; or :update, :unmount
 :replicant/node <DOM element>
 :replicant/remember (fn [memory] ...)}  ; For storing state
```

Always destructure `:replicant/keys [node]` to get the DOM element:

```clojure
:replicant/on-mount (fn [{:replicant/keys [node]}]
  (.focus node)
  (.scrollIntoView node))
```

### Required: Enable Lifecycle Hooks

You MUST call `set-dispatch!` during app initialization to enable lifecycle hooks.

```clojure
(require '[replicant.dom :as d])

(defn main []
  ;; Enable lifecycle hooks
  (d/set-dispatch!
   (fn [event-data handler-data]
     (cond
       ;; Handle lifecycle hooks
       (= :replicant.trigger/life-cycle (:replicant/trigger event-data))
       (when (fn? handler-data)
         (handler-data event-data))

       ;; Handle DOM events (optional - for data-driven event handlers)
       (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (when (fn? handler-data)
         (handler-data (:replicant/dom-event event-data))))))

  ;; Rest of initialization...
  )
```

### Example: Auto-focus contenteditable

```clojure
[:span {:contentEditable true
        :replicant/on-render (fn [{:replicant/keys [node]}]
                               ;; Set initial content
                               (when (empty? (.-textContent node))
                                 (set! (.-textContent node) "Hello"))

                               ;; Auto-focus for immediate typing
                               (.focus node))}]
```

**See**: `src/components/block.cljs` for real-world usage.

---

## How We Use Replicant in Evo

### Architecture: Replicant as View Layer Only

```
User Interaction
      ↓
Component (Replicant)         <-- Pure render function
      ↓
Dispatch Intent              <-- Describe what happened
      ↓
Plugin (Intent Handler)      <-- Calculate operations
      ↓
Kernel (Transaction)         <-- Apply ops to DB
      ↓
Component Re-renders         <-- Replicant diffs & patches DOM
```

**Key Principle:** Components never mutate state directly. They dispatch intents.

### Pattern: Event Handlers Dispatch Intents

```clojure
;; components/block.cljs
(defn handle-arrow-up [e db block-id on-intent]
  (let [target (.-target e)
        cursor-pos (detect-cursor-row-position target)]
    (when (:first-row? cursor-pos)
      (.preventDefault e)
      ;; Dispatch intent with context
      (on-intent {:type :navigate-with-cursor-memory
                  :direction :up
                  :current-block-id block-id
                  :current-text (.-textContent target)
                  :current-cursor-pos (.-anchorOffset (.getSelection js/window))}))))

;; Component definition
(defn Block [{:keys [db block-id on-intent] :as props}]
  [:div.block
   {:on {:keydown (fn [e] (handle-keydown e db block-id on-intent))}}
   ...])
```

**Why:** Component describes the event, plugin decides what ops to apply.

### Pattern: Lifecycle Hooks for DOM Interaction

We use `:replicant/on-render` for **ephemeral DOM state only**:
- Focus management
- Cursor positioning
- Scroll-into-view
- Updating mock-text for cursor detection

```clojure
;; Editing mode - focus and set cursor
[:span.content-edit
 {:contentEditable true
  :replicant/on-render (fn [{:replicant/keys [node]}]
                         ;; Focus element
                         (.focus node)

                         ;; Position cursor based on DB state
                         (when-let [cursor-pos (q/cursor-position db)]
                           (set-cursor-position! node cursor-pos))

                         ;; Update mock-text for boundary detection
                         (update-mock-text! (.-textContent node)))}]
```

**Key Insight:** Cursor position is stored in DB (`:cursor-position`), but **applying** it requires DOM manipulation in lifecycle hook.

### Pattern: Atoms for Component-Local State

Use atoms **sparingly** for state that:
1. Doesn't affect other components
2. Shouldn't be in history (undo/redo)
3. Is truly UI-only

```clojure
;; components/block.cljs
(defn Block [props]
  (let [;; Prevent blur event firing after Escape key
        exiting-edit? (atom false)

        ;; Track if we're in initial render
        initializing? (atom true)]
    [:div.block
     ;; Use atoms to coordinate event handlers
     {:on {:keydown (fn [e]
                      (when (= (.-key e) "Escape")
                        (reset! exiting-edit? true)))
           :blur (fn [e]
                   (when-not @exiting-edit?
                     (on-intent {:type :exit-edit})))}}]))
```

**Rule:** If state could affect another block or needs undo, put it in DB via intent. Otherwise, atom is OK.

### Pattern: Mock-text Technique for Cursor Detection

Replicant's `:replicant/on-render` enables Logseq's mock-text technique:

```clojure
;; Hidden div that mirrors contenteditable text
[:div#mock-text {:style {:position "absolute"
                         :visibility "hidden"
                         :white-space "pre-wrap"}}]

;; On every input, update mock-text
(defn update-mock-text! [text]
  (when-let [mock-elem (js/document.getElementById "mock-text")]
    (set! (.-innerHTML mock-elem) "")
    (doseq [[idx c] (map-indexed vector (seq text))]
      (let [span (.createElement js/document "span")]
        (.setAttribute span "id" (str "mock-text_" idx))
        (set! (.-textContent span) (str c))
        (.appendChild mock-elem span)))))

;; Detect cursor row position
(defn detect-cursor-row-position [elem]
  (when-let [cursor-rect (get-caret-rect elem)]
    (let [tops (get-mock-text-tops)
          cursor-top (.-top cursor-rect)]
      {:first-row? (= (first tops) cursor-top)
       :last-row? (= (last tops) cursor-top)})))
```

**Why:** Contenteditable doesn't expose "what row am I on?" API. Mock-text gives us character positions via `getBoundingClientRect()`.

### What NOT to Do with Replicant

❌ **Don't use `:ref`** - Use lifecycle hooks instead
❌ **Don't store app state in atoms** - Use DB + intents
❌ **Don't call DOM APIs outside lifecycle hooks** - You'll lose in diffing
❌ **Don't mutate DOM directly** - Let Replicant handle it

✅ **Do use lifecycle hooks** for DOM-only operations
✅ **Do dispatch intents** for all state changes
✅ **Do keep components pure** - same props = same render
✅ **Do use atoms** for truly local, ephemeral UI state

---

## Testing Replicant Components

### REPL Component Testing

```clojure
;; dev/repl/init.cljc
(repl/test-component! 'components.block/Block
  {:db (sample-db)
   :block-id "a"
   :depth 0
   :on-intent (fn [intent]
                (prn "Intent dispatched:" intent))})
```

**What you can verify:**
- Component renders without errors
- Correct DOM structure
- Intents dispatched on interactions
- Props correctly passed down

**What you can't verify:**
- Actual cursor position (requires real browser)
- getBoundingClientRect values (requires layout engine)
- Keyboard events (can mock, but real test is better)

### E2E Testing with Chrome DevTools MCP

```clojure
;; test/e2e/navigation_test.cljs
(deftest cursor-position-after-navigation
  ;; Use chrome-devtools MCP
  (mcp__chrome-devtools__click {:uid "block-a"})
  (mcp__chrome-devtools__press_key {:key "ArrowDown"})

  ;; Verify cursor position
  (let [pos (mcp__chrome-devtools__evaluate_script
              {:function "() => window.getSelection().anchorOffset"})]
    (is (= 6 pos))))
```

**See:** `dev/specs/TESTING_STRATEGY_NAVIGATION.md` for full testing approach.

---

## Reference

- Docs: https://replicant.fun/event-handlers/
- Example: `src/lab/app.cljs:49` (interpolate-actions)
- Source: `~/Projects/best/replicant/src/replicant/core.cljc` (lifecycle implementation)
- Our Usage: `src/components/block.cljs` (real-world patterns)
