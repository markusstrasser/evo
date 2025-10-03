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

## Reference

- Docs: https://replicant.fun/event-handlers/
- Example: `src/lab/app.cljs:49` (interpolate-actions)
