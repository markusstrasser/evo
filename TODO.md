# Architecture Improvements for Tree Editor

## Overview
Improve robustness, debuggability, simplicity, and inspectability of the ClojureScript/Replicant tree editor.

## 🛡️ Robustness

### Schema Validation & Error Handling
- [ ] Add Malli schemas for db structure validation
- [ ] Implement operation guards with try/catch
- [ ] Add error boundaries for UI operations
- [ ] Validate db state after every operation

### Undo/Redo System (Operation-Based)
- [ ] Create `:tx-log` in db to store operation history
- [ ] Every Replicant event pipes to `{:op :type :args {...}}` transaction
- [ ] Implement undo/redo by replaying operations from initial state
- [ ] Add transaction middleware to log all operations

```clojure
;; Example transaction structure
{:op :create-child-block :node-id "parent-123" :timestamp 1234567890}
{:op :select-node :node-id "child-456"}
{:op :patch-node :node-id "child-456" :updates {:props {:text "New Text"}}}
```

## 🔍 Debuggability

### Enhanced Logging System
- [ ] Add configurable log levels (:debug, :info, :warn, :error)
- [ ] Implement log history with timestamps
- [ ] Add operation timing and performance metrics
- [ ] Create dev mode with enhanced debugging

### State Diffing & Change Tracking
- [ ] Implement state diffing between operations
- [ ] Add change tracking for nodes, selections, and view state
- [ ] Log meaningful state transitions
- [ ] Add breakpoints for specific operations

### Dev Mode Enhancements
- [ ] Add dev middleware for dispatch system
- [ ] Implement hot-reload friendly debugging
- [ ] Add assertion checks in dev mode
- [ ] Create debug helpers for common operations

## 🎯 Simplicity

### Separate Concerns with Protocols
- [ ] Create `TreeOperations` protocol (insert, delete, move)
- [ ] Create `ViewOperations` protocol (select, collapse, expand)
- [ ] Create `QueryOperations` protocol (find-parent, get-children, get-depth)
- [ ] Implement protocols on db record

### Pure Functions with Context
- [ ] Remove global `current-node-id` dependency
- [ ] Make all operations take explicit parameters
- [ ] Add operation builders for common patterns
- [ ] Reduce implicit state dependencies

### Reduce Global State
- [ ] Consider component-local state where appropriate
- [ ] Add state slicing for better performance
- [ ] Implement selective re-rendering
- [ ] Add state normalization patterns

## 👁️ Inspectability

### Dev Tools Panel
- [ ] Create collapsible dev tools UI panel
- [ ] Show current selection, node count, operation history
- [ ] Add undo/redo buttons in dev panel
- [ ] Display performance metrics

### State Visualization
- [ ] Implement tree structure visualizer
- [ ] Add node highlighting for selected/collapsed states
- [ ] Show operation flow and data dependencies
- [ ] Create interactive state explorer

### Performance Monitoring
- [ ] Add render time tracking
- [ ] Monitor operation execution times
- [ ] Track memory usage patterns
- [ ] Add performance warnings for slow operations

### Action Replay & Time Travel
- [ ] Implement operation replay from tx-log
- [ ] Add time travel debugging
- [ ] Create operation bookmarks
- [ ] Add step-through debugging for operations

## Implementation Priority

### High Priority (Immediate)
- [ ] Schema validation for db structure
- [ ] Operation-based undo/redo with tx-log
- [ ] Enhanced error handling and logging
- [ ] Dev tools panel with basic inspection

### Medium Priority
- [ ] Protocol-based separation of concerns
- [ ] State diffing and change tracking
- [ ] Performance monitoring
- [ ] Tree visualization

### Low Priority
- [ ] Advanced time travel debugging
- [ ] Performance optimization
- [ ] Advanced dev tools features

## Implementation Notes

### Transaction System
Every user interaction should generate a transaction:
```clojure
;; Event -> Transaction
{:click "node-123"} -> {:op :select-node :node-id "node-123"}

;; Transaction gets added to tx-log
(swap! db update :tx-log conj transaction)

;; Undo/redo replays from initial state
(defn replay-operations [initial-db operations]
  (reduce apply-operation initial-db operations))
```

### Dev Tools Integration
```clojure
;; Add to main render conditionally
(defn render [db]
  [:div {:class [:app]}
   (when @dev-mode? [dev-panel db])
   [tree-editor db]])
```

### Testing Considerations
- [ ] Add tests for transaction system
- [ ] Test undo/redo with complex operation sequences
- [ ] Add performance regression tests
- [ ] Test dev tools don't affect production builds