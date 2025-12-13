# Evo Implementation Gaps & Guardrails

What's NOT yet implemented, what we deliberately diverge from, and implementation rules to follow.

**Specs**: See `STRUCTURAL_EDITING.md` (core) and `LOGSEQ_UI_FEATURES.md` (Logseq-specific).

---

## Deliberate Divergences from Logseq

Features we intentionally don't implement. See `VISION.md` for philosophy.

### No Block References `(())`

**What Logseq has:**
- `((uuid))` inline block references
- `{{embed ((uuid))}}` block embeds
- `Cmd+C` on caret (no selection) copies `((uuid))`
- `Cmd+Shift+E` copies as embed
- `Cmd+Shift+R` replaces ref with content
- Alt+drag inserts reference instead of moving
- Paste `((uuid))` in `(())` context strips outer parens

**What Evo does:** None of the above. Block content is copied/moved, never referenced.

**Why:** Block refs create fragile knowledge graphs. Content should be explicit, not pointer-based. If you need the same content in two places, it should exist in two places (with potential sync tooling), not be a runtime dereference. This aligns with the "explicit over implicit" philosophy.

**Clipboard simplifications:**
- `Cmd+C` with no selection: no-op (nothing to copy)
- Alt+drag: disabled or same as regular drag
- No ref detection in paste handlers
- No graph-mismatch concerns for refs (only for block structure)

### Plugin System (vs Logseq's Hooks)

**What Logseq has:**
- Global atom + pub/sub + multimethods
- `state/install-plugin-hook`, `state/pub-event!`
- LSPluginCore JS sandbox for external plugins

**What Evo does:** Same pattern as core - pure functions.

```clojure
;; Plugin contract: [db intent] -> ops | nil
(defn handler [db intent]
  (when (= (:type intent) :my-thing)
    [{:op :update :id (:target intent) :props {:done true}}]))
```

**Registration:** Add namespace to `resources/plugins.edn`

**Why this is simpler:**
- Same signature as existing `src/plugins/*` handlers
- No special API to learn - it's just Clojure
- Pure functions: testable, inspectable, composable
- Advanced logic? Just write code. Constraint is interface, not internals.

---

## Implementation Guardrails

1. **Single dispatcher rule** - Editing-context keys (Enter, ArrowUp/Down, Shift+Arrow) are owned by `src/components/block.cljs`. They dispatch Nexus actions with DOM facts (cursor rows, selection). Never bind these in `keymap/bindings_data.cljc` under `:editing`.

2. **Nexus wiring** - DOM handlers route through `shell/nexus.cljs`. Components emit `[:editing/navigate-up payload]`. Gives deterministic instrumentation and one action per DOM event.

3. **Cursor guard** - Preserve `dataset.mounted` pattern so Replicant doesn't stomp browser selections. `on-mount` handles NEW elements, `on-render` only acts if `mounted` flag exists.

4. **Selection direction tracking** - `session/selection` stores `:direction`, `:anchor`, `:focus`. Shift+Arrow extends/contracts incrementally.

5. **Visibility filter** - Navigation/selection must respect zoom root and skip folded descendants. Use `visible-blocks-in-dom-order {:root ... :skip-folded? true}`.

---

## Gaps (Not Yet Implemented)

### Core Editing

| Gap | Logseq | Evo | Priority |
|-----|--------|-----|----------|
| **Doc-mode** | Enter/Shift+Enter swap when enabled | ✅ Implemented | - |
| **Empty block auto-outdent** | Enter on empty child at end of parent outdents | ✅ Fixed | - |
| **Shift+Click visibility** | Only selects visible blocks | ✅ Fixed (uses visible-range) | - |
| **Indent expands collapsed** | Indent into collapsed sibling expands it | ✅ Fixed | - |
| **Non-consecutive rejection** | Indent/outdent on non-adjacent selection rejected | ✅ Fixed | - |
| **Empty list top-level fallback** | Enter on `- ` at top level adds newline | ✅ Fixed (normal split) | - |

### UI Features

| Gap | Logseq | Evo | Priority |
|-----|--------|-----|----------|
| **Slash commands** | `/` opens inline palette | Not implemented | LOW |
| **Quick switcher** | Cmd+K overlay search | Not implemented | LOW |
| **Shift+Enter sidebar** | Opens selected in sidebar | Not implemented | LOW |
| **Block-ref navigation** | Enter on `((uuid))` opens sidebar | Stub only | LOW |
| **Page-ref navigation** | Enter on `[[page]]` navigates | Stub only | LOW |
| **Cmd+Click toggle** | Toggle block in selection | Not implemented | LOW |
| **Drag & drop** | Mouse block reordering | Not implemented | LOW |

---

## Priority Order

**Low (power features):**
1. Block-ref/Page-ref navigation on Enter
2. Slash commands
3. Quick switcher

---

## Logseq Code References

Key Enter behavior in Logseq (`handler/editor.cljs`):
- Lines 2490-2554: `keydown-new-block` - context detection
- Lines 2547-2551: Empty block auto-outdent condition
- Lines 2383-2386: `keydown-new-line` - Shift+Enter
