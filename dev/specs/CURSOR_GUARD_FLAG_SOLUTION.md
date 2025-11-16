# Cursor Guard Flag Solution - Gemini 2.5 Pro Proposal

**Date:** 2025-11-16
**Source:** Architect skill with full src/ context + Slate implementation analysis
**Run ID:** 577f3aae-2e35-4f28-810c-919407c2aa9f

## Background

After researching Slate.js implementation and consulting multiple LLMs with full context, this is the recommended solution for fixing cursor scrambling during typing.

## The Core Problem

Cursor position is being **SET in state during normal typing** when it should only be set for:
- Navigation (arrow keys)
- Undo/redo
- Programmatic operations

The bug is NOT in cursor restoration - it's in preventing cursor-pos from being set incorrectly during typing.

## Slate's Pattern (Industry Standard)

Slate uses a `state.isUpdatingSelection` flag to distinguish:
- **Programmatic updates**: Set flag → update DOM → setTimeout to clear flag
- **User typing**: Check flag first - if set, ignore selectionchange event

## Gemini's Proposal for Replicant

### 1. Core Approach

Introduce a simple boolean flag, `:cursor-locked?`, into the editor component's state atom. Native DOM event listeners (`beforeinput`, etc.) will set this flag to `true` to signal that the browser is in control, and the render/patch function will check this flag, skipping any programmatic cursor updates while it is set. The flag is cleared asynchronously with `setTimeout` immediately after being set, ensuring the lock is temporary and confined to the current event-loop tick.

### 2. Key Components and Their Responsibilities

**Component State Atom:**
- **Responsibility:** The single source of truth for a `contenteditable` component instance.
- **Implementation:** A standard ClojureScript `atom` holding a map. It will contain the editor's content, selection state, and our new `:cursor-locked?` flag.

**DOM Event Listeners:**
- **Responsibility:** To detect user interactions that modify the DOM and immediately lock the cursor state. They do one thing: set `:cursor-locked?` to `true` and schedule it to be unlocked.
- **Implementation:** Attach listeners for events like `beforeinput`, `compositionstart`, and `mousedown` to the `contenteditable` element. The handler function will look like this:

```clojure
(fn [event]
  (swap! state-atom assoc :cursor-locked? true)
  (js/setTimeout #(swap! state-atom assoc :cursor-locked? false) 0))
```

**Note:** Placing the unlock logic right next to the lock logic makes the temporary nature of the lock explicit and reduces the chance of a lock getting "stuck."

**Render/Patch Function:**
- **Responsibility:** To synchronize the DOM with the component's state atom. It is now also responsible for *honoring* the cursor lock.
- **Implementation:** Within the function that applies state changes to the DOM, wrap the cursor-setting logic in a conditional check.

```clojure
(defn apply-state-to-dom [element state]
  ;; ... logic to update element.innerHTML or child nodes ...

  ;; The guard check
  (when-not (:cursor-locked? state)
    (set-dom-selection (:selection state)))

  ;; ... other logic ...
  )
```

This function reads the state (including the flag) but does not modify it, keeping its primary responsibility clean.

### 3. Data Structures and Storage

The only data structure change is adding a single key to the component's state map. This approach is minimal and easy to inspect.

**Storage:** The component's `state-atom`.
**Structure:** A simple ClojureScript map.

**Example State Map:**

```clojure
{
  :content "<p>Hello world!</p>"
  :selection {:anchor {:path [0 0], :offset 5}
              :focus  {:path [0 0], :offset 5}}

  ;; The new guard flag
  :cursor-locked? false ;; or true
}
```

This is highly debuggable. At any point in the REPL, you can `(pprint @my-editor-state-atom)` to see the exact state, including whether the cursor is locked. You can also manually `swap!` the atom to test locking/unlocking behavior directly.

### 4. Events to Lock

Critical events that should set `:cursor-locked? true`:
- `beforeinput` - Catches typing BEFORE it happens (most important!)
- `compositionstart` - IME/mobile keyboards with predictive text
- `mousedown` - User clicking to select text

Do NOT lock on every event (e.g., `keyup`, `mousemove`) - this creates flickering.

### 5. Timing Mechanism

Use `setTimeout(..., 0)` to clear the flag on the next event loop tick:

```clojure
(fn [event]
  (swap! state-atom assoc :cursor-locked? true)
  (js/setTimeout #(swap! state-atom assoc :cursor-locked? false) 0))
```

This ensures:
1. Lock is set synchronously during the event
2. Current event handler completes
3. Flag is cleared before next event can fire

### 6. Integration with Replicant

In `src/components/block.cljs`, modify the `:replicant/on-render` hook:

**Before:**
```clojure
:replicant/on-render (fn [{:replicant/keys [node life-cycle remember memory]}]
  (when-not (= life-cycle :replicant.life-cycle/unmount)
    (let [cursor-pos (q/cursor-position db)]
      ;; ...
      (if cursor-pos
        ;; ... cursor positioning code ...
```

**After:**
```clojure
:replicant/on-render (fn [{:replicant/keys [node life-cycle remember memory]}]
  (when-not (= life-cycle :replicant.life-cycle/unmount)
    (let [cursor-pos (q/cursor-position db)
          cursor-locked? (aget node "__cursorLocked")]  ;; Read flag from DOM
      ;; ...
      (if (and cursor-pos (not cursor-locked?))  ;; THE GUARD CHECK
        ;; ... cursor positioning code ...
```

### 7. Pros and Cons

**Pros:**
- **Extreme Simplicity**: The logic is a single boolean flag and an `if` statement
- **Excellent Debuggability**: The lock state is not hidden - it's explicit data observable at all times
- **REPL-Friendly**: You can manually toggle the flag from REPL to test scenarios
- **Explicit Control**: The flow is clear - an event sets a flag, the renderer reads the flag
- **Low Performance Overhead**: A map lookup and boolean check is computationally insignificant

**Cons:**
- **Slightly Imperative**: Relies on mutating an atom in response to a DOM event (side effect)
- **Relies on setTimeout Timing**: The `setTimeout(..., 0)` pattern introduces asynchronicity
- **Potential for Stuck Locks**: If setTimeout fails to run, cursor could get permanently locked (mitigated by co-locating lock/unlock)

### 8. Red Flags to Watch For During Implementation

- **A "Stuck" Lock State**: If editor becomes unresponsive to programmatic cursor changes, check the flag. Ensure every event listener that sets the flag also schedules its removal.
- **Ignoring Composition Events**: Forgetting `compositionstart`/`compositionend` will break CJK/mobile keyboard users.
- **Overly Broad Event Listening**: Don't attach to every event - stick to events that directly precede DOM mutation.
- **Race Conditions with Framework Logic**: Ensure state update logic doesn't interfere with the lock.

## Implementation Checklist

- [ ] Add event listeners for `beforeinput`, `compositionstart`, `mousedown`
- [ ] Implement flag set + setTimeout clear pattern in each listener
- [ ] Add guard check `(when-not cursor-locked? ...)` in `:replicant/on-render`
- [ ] Test typing "Hello World" - should NOT scramble
- [ ] Test arrow navigation - should still restore cursor correctly
- [ ] Test undo/redo - should still restore cursor correctly
- [ ] Test mobile/IME keyboards
- [ ] Verify flag resets properly (check in REPL)

## Alternative: Using Replicant Memory

Instead of DOM properties (`aget/aset`), could use Replicant's `memory`:

```clojure
:replicant/on-render (fn [{:replicant/keys [node life-cycle remember memory]}]
  (let [cursor-locked? (get memory :cursor-locked?)]
    (when-not cursor-locked?
      ;; ... cursor positioning ...
```

**Trade-off:** Memory is component-scoped and survives re-renders, which might be cleaner than DOM properties.

## References

- Slate.js implementation: `useIsomorphicLayoutEffect` + `state.isUpdatingSelection` flag
- Architect run: `.architect/review-runs/577f3aae-2e35-4f28-810c-919407c2aa9f/`
- Failed attempt: `docs/CURSOR_FIX_ATTEMPTS.md`
- Full context: 81k tokens from `src/` + Slate analysis
