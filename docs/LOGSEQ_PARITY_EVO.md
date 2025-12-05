# Evo Implementation Gaps & Guardrails

What's NOT yet implemented and implementation rules to follow.

**Specs**: See `STRUCTURAL_EDITING.md` (core) and `LOGSEQ_UI_FEATURES.md` (Logseq-specific).

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
| **Empty block auto-outdent** | Enter on empty child at end of parent outdents | Stays in place | MEDIUM |
| **Shift+Click visibility** | Only selects visible blocks | Includes folded | MEDIUM |
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

**Medium (core UX edge cases):**
1. Empty block auto-outdent at end of parent
2. Visibility-aware Shift+Click (skip folded)

**Low (power features):**
3. Block-ref/Page-ref navigation on Enter
4. Slash commands
5. Quick switcher

---

## Logseq Code References

Key Enter behavior in Logseq (`handler/editor.cljs`):
- Lines 2490-2554: `keydown-new-block` - context detection
- Lines 2547-2551: Empty block auto-outdent condition
- Lines 2383-2386: `keydown-new-line` - Shift+Enter
