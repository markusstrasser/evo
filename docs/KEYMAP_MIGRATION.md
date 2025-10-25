# Keymap Architecture Migration Guide

## Status: ✅ Infrastructure Complete, UI Integration Pending

The central keymap resolver is implemented and ready. This guide documents the migration from the old if/cond ladder to the new declarative system.

## Completed

1. **`keymap/core.cljc`** - Central resolver with:
   - `!keymap-registry` atom (single source of truth)
   - `register!` for plugin-based binding registration
   - `resolve-intent-type` for event → intent resolution
   - Context-aware (editing vs non-editing)
   - Hot-reloadable

2. **`keymap/bindings.cljc`** - All application bindings registered:
   - Navigation (ArrowUp/Down)
   - Selection (Shift+Arrow)
   - Structural edits (Tab, Shift+Tab, Backspace)
   - Move operations (Cmd/Alt+Shift+Arrow)
   - Edit mode overrides

## Next Step: UI Layer Integration

### Current Code (src/app/blocks_ui.cljs)

```clojure
(defn handle-global-keydown [e]
  (let [key (.-key e)
        mod? (or (.-metaKey e) (.-ctrlKey e))
        shift? (.-shiftKey e)
        ...]
    (cond
      (and (= key "Enter") (not shift?) ...) (do ...)
      (and (= key "ArrowDown") ...) (do ...)
      ;; 20+ more branches...
      )))
```

### Migrated Code

```clojure
(require '[keymap.core :as keymap]
         '[keymap.bindings]) ; Loads bindings on require

(defn handle-global-keydown [e]
  (let [event (keymap/parse-dom-event e)
        db @!db
        intent-type (keymap/resolve-intent-type event db)]
    (when intent-type
      (case intent-type
        ;; Special handling for intents that need additional context
        :create-new-block-after-focus
        (let [focus-id (sel/get-focus db)
              parent (get-in db [:derived :parent-of focus-id])
              new-id (str "block-" (random-uuid))]
          (.preventDefault e)
          (handle-intent {:type :create-and-place
                          :id new-id
                          :parent parent
                          :after focus-id})
          (js/setTimeout #(handle-intent {:type :enter-edit :block-id new-id}) 0))

        ;; Default: direct intent dispatch
        (do (.preventDefault e)
            (handle-intent {:type intent-type}))))))
```

### Migration Steps

1. **Add requires** to `blocks_ui.cljs`:
   ```clojure
   [keymap.core :as keymap]
   [keymap.bindings] ; Side-effect: registers all bindings
   ```

2. **Replace `handle-global-keydown`** with resolver-based version (see above)

3. **Remove old if/cond ladder** (lines ~90-200 in blocks_ui.cljs)

4. **Test hot reload**: Change a binding in `keymap/bindings.cljc`, save, verify new binding works

5. **Add custom bindings** (optional):
   ```clojure
   (keymap/register! :non-editing
     [[{:key "j"} :select-next-sibling]
      [{:key "k"} :select-prev-sibling]])
   ```

## Benefits

✅ **Zero Divergence**: Single table, impossible for UI and registry to drift
✅ **Hot Reload**: Change bindings without restarting browser
✅ **Debuggable**: `@keymap/!keymap-registry` shows all bindings
✅ **Extensible**: Plugins can register bindings independently
✅ **Testable**: Mock keymap registry for unit tests
✅ **Maintainable**: 15 lines replaces 200-line if/cond ladder

## Testing

Integration tests already written (test/integration/keybinding_test.cljc) verify intent semantics. After migration, add resolver tests:

```clojure
(deftest test-resolver
  (keymap/reset-all!)
  (keymap/register! :non-editing
    [[{:key "j"} :select-next-sibling]])

  (let [event {:key "j" :mod false :shift false :alt false}
        db (make-db-non-editing)]
    (is (= :select-next-sibling (keymap/resolve-intent-type event db)))))
```

## Rollback Plan

If issues arise, old `handle-global-keydown` is preserved in git. Revert commit to restore.

## Questions?

See `src/keymap/core.cljc` for implementation details.
