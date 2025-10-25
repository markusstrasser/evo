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

## Reference

- Docs: https://replicant.fun/event-handlers/
- Example: `src/lab/app.cljs:49` (interpolate-actions)
- Source: `~/Projects/best/replicant/src/replicant/core.cljc` (lifecycle implementation)
