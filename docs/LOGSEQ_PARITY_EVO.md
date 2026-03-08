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

**Registration:** Require the namespace in `shell/editor.cljs`

**Why this is simpler:**
- Same signature as existing `src/plugins/*` handlers
- No special API to learn - it's just Clojure
- Pure functions: testable, inspectable, composable
- Advanced logic? Just write code. Constraint is interface, not internals.

---

## Implementation Guardrails

1. **Single dispatcher rule** - Editing-context keys (Enter, ArrowUp/Down, Shift+Arrow) are owned by `src/components/block.cljs`. They dispatch intent maps with DOM facts (cursor rows, selection). Never bind these in `keymap/bindings_data.cljc` under `:editing`.

2. **Runtime wiring** - Global non-editing keys route through `shell.global-keyboard`. Block-local editing keys stay in `src/components/block.cljs`. `shell.executor` is the canonical runtime for both paths.

3. **Cursor guard** - Preserve `dataset.mounted` pattern so Replicant doesn't stomp browser selections. `on-mount` handles NEW elements, `on-render` only acts if `mounted` flag exists.

4. **Selection direction tracking** - `session/selection` stores `:direction`, `:anchor`, `:focus`. Shift+Arrow extends/contracts incrementally.

5. **Visibility filter** - Navigation/selection must respect zoom root and skip folded descendants. Use `q/visible-blocks` (requires session for fold/zoom/page state).

---

## Cursor & Focus Behavior Parity (Verified 2025-12-13)

Comprehensive comparison of cursor and focus edge cases between Evo and Logseq.

### ✅ Verified Aligned Behaviors

| Behavior | Both Implementations |
|----------|---------------------|
| Left arrow at pos 0 | Navigate to previous block at END |
| Right arrow at end | Navigate to next block at START |
| Backspace at pos 0 (content) | Merge with previous sibling/parent, cursor at join |
| Backspace at pos 0 (empty) | Delete block, navigate to prev |
| Delete at end | Merge with next block |
| Enter at pos 0 (content after) | Empty block ABOVE, stay on current |
| Enter at end | New empty block below |
| Enter in middle | Split, cursor to new block at 0 |
| Up/Down with selection | Collapse to start (up) / end (down) |
| Column memory | Grapheme-aware, clamps to line length |
| Doc-level backspace | No-op (protected - can't merge into `:doc`) |

### Implementation Differences (Same UX)

| Aspect | Evo | Logseq |
|--------|-----|--------|
| **First/last row detection** | Range API `getBoundingClientRect()` + threshold | Mock-text DOM elements with `offsetTop` |
| **Column restoration** | Grapheme count → character offset | `closer` algorithm finds pixel-nearest character |
| **Blur suppression** | Double-rAF flag (~32ms) | Single-rAF deferred focus (~16ms) |

### Known Edge Cases

1. **Variable line heights**: Evo's threshold-based row detection (`< 1.5 * lineHeight`) may behave slightly differently with mixed heading/text content.

2. **Proportional fonts**: Logseq's `closer` algorithm finds the visually closest character by pixel position. Evo uses grapheme count which is equivalent for monospace but may differ slightly with proportional fonts.

**Test coverage**: E2E tests in `e2e/cursor-*.spec.js` verify boundary behaviors.

### Logseq Source References

| Behavior | Logseq File | Key Functions |
|----------|-------------|---------------|
| Column memory | `util.cljc`, `util/cursor.cljs` | `get-line-pos`, `closer`, `mock-char-pos` |
| Cross-boundary nav | `handler/editor.cljs:2643` | `move-cross-boundary-up-down` |
| Row detection | `util/cursor.cljs` | `textarea-cursor-first-row?`, `textarea-cursor-last-row?` |
| Enter routing | `handler/editor.cljs:504` | `insert-new-block!`, `insert-new-block-before-block-aux!` |
| Backspace at 0 | `handler/editor.cljs:2865` | `delete-block-when-zero-pos!`, `delete-block-inner!` |
| Merge logic | `handler/editor.cljs:776` | `move-to-prev-block` |

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
