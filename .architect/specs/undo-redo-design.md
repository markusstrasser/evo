# Undo/Redo Design

## Overview

Implement undo/redo functionality using event sourcing with tombstones. Events are never deleted - only marked as active or undone.

## Architecture

### Event Status
Every event can have a status:
- `:active` (default) - Event is applied to state
- `:undone` - Event is ignored during state reduction

### Tombstone Events
New event types:
- `:event/undo` - Marks a target event as undone
- `:event/redo` - Marks an undone event as active again

### Data Structure
```clojure
{:event/type :undo
 :event/timestamp #inst "2025-..."
 :event/data {:target-event-id <uuid-of-event-to-undo>}}

{:event/type :redo
 :event/timestamp #inst "2025-..."
 :event/data {:target-event-id <uuid-of-event-to-redo>}}
```

### Event ID
Each event needs a unique ID:
```clojure
{:event/id #uuid "..."
 :event/type :card-created
 :event/timestamp #inst "..."
 :event/data {...}}
```

## State Management

### Current State Shape
```clojure
{:cards {hash card-with-meta}
 :meta {hash card-meta}}
```

### Extended State Shape
```clojure
{:cards {hash card-with-meta}
 :meta {hash card-meta}
 :event-status {event-id :active|:undone}
 :undo-stack [event-id ...]  ; Events that can be undone
 :redo-stack [event-id ...]} ; Events that can be redone
```

## Implementation

### 1. Add Event IDs
Modify `new-event` to include UUID:
```clojure
(defn new-event [event-type data]
  {:event/id (random-uuid)
   :event/type event-type
   :event/timestamp (date-from-ms (now-ms))
   :event/data data})
```

### 2. Reduce Events with Status
```clojure
(defn reduce-events [events]
  (let [event-status (build-event-status-map events)
        active-events (filter #(= :active (event-status (:event/id %))) events)]
    (reduce apply-event initial-state active-events)))

(defn build-event-status-map [events]
  (reduce
    (fn [status-map event]
      (case (:event/type event)
        :undo (assoc status-map
                (get-in event [:event/data :target-event-id])
                :undone)
        :redo (assoc status-map
                (get-in event [:event/data :target-event-id])
                :active)
        status-map))
    {}
    events))
```

### 3. Undo/Redo Events
```clojure
(defn undo-event [target-event-id]
  (new-event :undo {:target-event-id target-event-id}))

(defn redo-event [target-event-id]
  (new-event :redo {:target-event-id target-event-id}))
```

### 4. UI Actions
Add undo/redo buttons:
```clojure
::undo
(when-let [last-undoable (last (:undo-stack state))]
  (let [event (undo-event last-undoable)]
    (fs/append-to-log dir-handle [event])
    (reload-state!)))

::redo
(when-let [last-redoable (last (:redo-stack state))]
  (let [event (redo-event last-redoable)]
    (fs/append-to-log dir-handle [event])
    (reload-state!)))
```

## Constraints

1. **Immutability**: Events are append-only, never modified or deleted
2. **Idempotency**: Undoing the same event twice has no effect
3. **Linear history**: No branching - redo stack is cleared on new action
4. **Scope**: Only user actions can be undone (card creation, reviews)

## Edge Cases

1. **Undo card creation**: Card disappears from state
2. **Undo review**: Card's scheduling reverts to previous state
3. **Redo after new action**: Redo stack is cleared
4. **Multiple undos**: Each undo goes back one step
5. **Image occlusion**: Undoing parent card creation removes all child cards

## Future Enhancements

1. **Undo groups**: Batch related events (e.g., all child cards from image occlusion)
2. **Selective undo**: Undo specific events, not just last one
3. **Undo history UI**: Show list of undoable actions with descriptions
