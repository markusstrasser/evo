# FR Coverage Matrix

**Generated:** 2026-04-22T19:19:10.002181

## Summary

- **Total FRs:** 50
- **Complete:** 44 (88%)
- **Implemented (not verified):** 0
- **Verified (not implemented):** 6
- **Missing:** 0

## Legend

**Status:**
- 🟢 Complete: Has both intent and test coverage
- 🟡 Implemented-Untested: Has intent but no test
- 🟠 Verified-Unimplemented: Has test but no intent (rare)
- 🔴 Missing: No intent or test coverage

**Priority:**
- 🔥 Critical
- ⬆️ High
- ➡️ Medium
- ⬇️ Low

## Coverage Matrix

| FR ID | Priority | Impl | Test | Status | Description |
|-------|----------|------|------|--------|-------------|
| derive-indexes | 🔥 critical | ❌ | ✅ | 🟠 verified-unimplemented | All operations trigger automatic index derivation; never mutate derived state directly |
| undo-restores-all | 🔥 critical | ❌ | ✅ | 🟠 verified-unimplemented | Undo/redo restores block content + caret position + selection state |
| backspace-merge | 🔥 critical | ✅ | ✅ | 🟢 complete | Backspace at start merges into previous sibling, re-parents children, preserves caret |
| smart-split | 🔥 critical | ✅ | ✅ | 🟢 complete | Enter performs context-aware split; empty list items unformat + create peer in single step |
| vertical-cursor-memory | 🔥 critical | ✅ | ✅ | 🟢 complete | Vertical navigation preserves horizontal grapheme column across blocks |
| view-arrows | 🔥 critical | ✅ | ✅ | 🟢 complete | ArrowUp/Down in view mode moves focus through visible siblings, skipping folded blocks |
| edit-view-exclusive | 🔥 critical | ✅ | ✅ | 🟢 complete | Edit mode and selection state never coexist; transitions clear opposite state |
| extend-boundary | 🔥 critical | ✅ | ✅ | 🟢 complete | Shift+Arrow extends selection incrementally with direction tracking |
| indent-outdent | 🔥 critical | ✅ | ✅ | 🟢 complete | Tab/Shift+Tab moves blocks under previous sibling / after parent; guards zoom roots |
| visible-outline | ⬆️ high | ❌ | ✅ | 🟠 verified-unimplemented | Navigation stays within visible outline (respects page, zoom, folding) |
| idle-guard | ⬆️ high | ❌ | ✅ | 🟠 verified-unimplemented | Idle state requires explicit action to enter edit/selection; prevents accidental state changes |
| selection-clears-edit | ⬆️ high | ❌ | ✅ | 🟠 verified-unimplemented | Entering selection mode clears editing state |
| copy-block | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+C copies selected blocks with metadata |
| paste-multiline | ⬆️ high | ✅ | ✅ | 🟢 complete | Pasting multi-line text creates multiple blocks |
| arrow-nav-mode | ⬆️ high | ✅ | ✅ | 🟢 complete | Arrow keys move cursor within block in Edit Mode |
| delete-forward | ⬆️ high | ✅ | ✅ | 🟢 complete | Delete key removes character at cursor or merges with next block at end |
| newline-no-split | ⬆️ high | ✅ | ✅ | 🟢 complete | Shift+Enter inserts newline without splitting block |
| shift-arrow-text-select | ⬆️ high | ✅ | ✅ | 🟢 complete | Shift+Arrow extends text selection within block (seeds multi-block selection) |
| horizontal-boundary | ⬆️ high | ✅ | ✅ | 🟢 complete | ArrowLeft at column 0 / ArrowRight at end jumps to adjacent block |
| idle-first-last | ⬆️ high | ✅ | ✅ | 🟢 complete | ArrowDown/Up from idle state selects first/last visible block |
| follow-link | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+Click or Cmd+O on page reference navigates to that page |
| switch-page | ⬆️ high | ✅ | ✅ | 🟢 complete | Navigate to different page (clears zoom, preserves page state) |
| cmd-a-cycle | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+A cycles: select all text → select block → select parent → select all visible |
| list-unformat | ⬆️ high | ✅ | ✅ | 🟢 complete | Enter on empty list item removes marker and creates peer at parent level |
| type-to-edit | ⬆️ high | ✅ | ✅ | 🟢 complete | Typing from idle/selection enters edit mode on first/selected block |
| climb-descend | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+Shift+Up/Down at boundaries climbs out to parent level / descends into next sibling |
| create-sibling | ⬆️ high | ✅ | ✅ | 🟢 complete | Create new block as sibling of current/selected |
| delete-block | ⬆️ high | ✅ | ✅ | 🟢 complete | Delete selected blocks (children move to trash with parent) |
| quick-switcher | ⬆️ high | ✅ | ✅ | 🟢 complete | Cmd+K opens global search overlay |
| slash-palette | ⬆️ high | ✅ | ✅ | 🟢 complete | Slash (/) opens inline command menu |
| slash-select | ⬆️ high | ✅ | ✅ | 🟢 complete | Enter inserts the selected slash command |
| zoom-guards | ➡️ medium | ❌ | ✅ | 🟠 verified-unimplemented | Structural operations blocked by zoom boundary (can't indent/outdent zoom root) |
| block-reference | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+Shift+C copies block reference ((id)) |
| kill-operations | ➡️ medium | ✅ | ✅ | 🟢 complete | Ctrl+K/U kill to end/beginning of line |
| paired-char-insertion | ➡️ medium | ✅ | ✅ | 🟢 complete | Auto-insert closing bracket/quote and place cursor inside |
| word-navigation | ➡️ medium | ✅ | ✅ | 🟢 complete | Option+Arrow moves cursor by word boundaries |
| toggle-block | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+Up/Down toggles fold state of current block |
| bold-italic | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+B/I wraps selection in **bold** or *italic* markers |
| alt-click-fold | ➡️ medium | ✅ | ✅ | 🟢 complete | Alt+Click toggles fold state of subtree |
| shift-click-range | ➡️ medium | ✅ | ✅ | 🟢 complete | Shift+Click extends selection range from current to clicked block |
| checkbox-toggle | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+Enter toggles TODO/DOING/DONE markers |
| slash-close | ➡️ medium | ✅ | ✅ | 🟢 complete | Esc closes slash palette without side effects |
| slash-filter | ➡️ medium | ✅ | ✅ | 🟢 complete | Slash palette search filters commands incrementally |
| slash-navigate | ➡️ medium | ✅ | ✅ | 🟢 complete | Arrow keys move slash selection with wraparound |
| focus-subtree | ➡️ medium | ✅ | ✅ | 🟢 complete | Cmd+. zooms into block as temporary root |
| restore-scope | ➡️ medium | ✅ | ✅ | 🟢 complete | Escape from zoom restores normal scope |
| expand-collapse-all | ⬇️ low | ✅ | ✅ | 🟢 complete | Cmd+Shift+Up/Down expands/collapses all blocks in scope |
| highlight-strikethrough | ⬇️ low | ✅ | ✅ | 🟢 complete | Cmd+H/Shift+H wraps selection in ^^highlight^^ or ~~strikethrough~~ |
| grapheme-cursor-memory | ⬇️ low | ✅ | ✅ | 🟢 complete | Cursor column memory uses grapheme clusters (not UTF-16 code units) for proper emoji/CJK handling |
| auto-increment | ⬇️ low | ✅ | ✅ | 🟢 complete | Enter on numbered list auto-increments next item |
