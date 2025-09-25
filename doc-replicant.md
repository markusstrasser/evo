# Replicant Library Documentation Summary

## Core Concepts

### Data-Driven Event Handling
Replicant uses a dispatch system where events are represented as data vectors instead of functions. Register a global dispatch function that receives event metadata and handler data:

```clojure
(r/set-dispatch!
  (fn [event-data handler-data]
    ;; event-data: {:replicant/trigger :replicant.trigger/dom-event
    ;;              :replicant/dom-event <DOMEvent>
    ;;              :replicant/node <DOMNode>}
    ;; handler-data: your custom data like [:action/type arg1 arg2]
    (handle-actions handler-data)))
```

### Hiccup Format
Similar to Reagent but with Replicant-specific features:
- `:replicant/key` - optimizes DOM updates by identifying reusable elements
- `:on` - event handlers as data (not functions)
- `:class` - can be collections like `[:class1 :class2]` instead of `"class1 class2"`

### Aliases
Custom hiccup tags that expand to other hiccup. Pure functions that take attributes and children:

```clojure
(defalias button [attrs children]
  [:button.btn attrs children])

;; Usage
[::button {:class :primary} "Click me"]
```

Benefits:
- Late binding (expanded only when needed)
- Top-down expansion (vs bottom-up function calls)
- Better dead code elimination
- Raises abstraction level for testing

### Reactive Rendering
Uses ClojureScript atoms with watchers for reactive updates:

```clojure
(defonce store (atom {}))
(add-watch store ::render
  (fn [_ _ _ new-state]
    (r/render el (render-fn new-state))))
```

### Keys for Optimization
`:replicant/key` helps reuse DOM nodes instead of destroying/recreating:

```clojure
[:ul
 (for [item items]
   [:li {:replicant/key (:id item)} (:name item)])]
```

### State Management Pattern
Typically uses atoms with action dispatching systems like Nexus:

```clojure
;; Actions are pure functions returning effect descriptions
(nxr/register-action! :counter/inc
  (fn [state path]
    [[:store/assoc-in path (inc (get-in state path))]]))

;; Effects perform side effects
(nxr/register-effect! :store/assoc-in
  (fn [_ store path value]
    (swap! store assoc-in path value)))
```

## Key Differences from React/Reagent

- **No component local state** - everything flows through global state
- **Data-driven** - event handlers are data, not functions
- **Pure functions** - aliases are pure, no lifecycle methods
- **Top-down rendering** - single render function for entire app
- **Late-bound aliases** - expanded only when attributes/children change

## Common Patterns

### Event Handler Data
```clojure
;; Instead of functions:
{:on {:click (fn [e] (do-something))}}

;; Use data:
{:on {:click [:action/do-something arg1 arg2]}}
```

### Conditional Classes
```clojure
;; Instead of string concatenation:
{:class (str "base" (when active " active"))}

;; Use collections:
{:class (cond-> [:base] active (conj :active))}
```

### Routing Integration
Use aliases for declarative routing:

```clojure
(defalias link [attrs children]
  [:a (assoc attrs :href (location->url (:routes attrs) (:location attrs)))
   children])

;; Usage
[::link {:location {:page :home}} "Home"]
```

## Debugging Tips

- Check console for `:class` warnings about string vs collection usage
- Use browser devtools to inspect DOM structure
- Log dispatch calls to verify event handling
- Ensure `:replicant/key` is used appropriately for lists
- Verify alias functions are registered before use

## Integration with Current Codebase

The current implementation uses some Replicant patterns but could be improved:

- Event handlers are data-driven ✓
- Reactive rendering with atoms ✓
- Missing: proper class collections, keys for optimization, aliases for components
- Issues: manual render forcing, watch setup problems, event bubbling conflicts