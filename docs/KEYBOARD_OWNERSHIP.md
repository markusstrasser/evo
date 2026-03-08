# Keyboard Ownership

This file is the canonical ownership matrix for keyboard behavior.

The rule is simple:

- `shell.global-keyboard` owns app-global shortcuts, non-editing selection
  policy, and validated editor-scoped shortcuts that must resolve above the
  block-local keydown flow.
- `components.block` owns block-local contenteditable behavior, row/boundary
  checks, IME safety, and browser-sensitive event ordering.
- Browser default behavior owns plain text editing that does not require Evo
  policy.
- `shell.executor` is the canonical runtime for all dispatched intent maps.

## Matrix

| Key family | Owner | Reason |
| --- | --- | --- |
| `Cmd/Ctrl+Z`, `Cmd/Ctrl+Shift+Z`, `Cmd/Ctrl+Y` | `shell.global-keyboard` | App-level history, not contenteditable behavior |
| `Cmd/Ctrl+B`, `Cmd/Ctrl+P`, `Cmd/Ctrl+K`, navigation history | `shell.global-keyboard` | Global chrome and editor-scoped shortcuts resolved at the shell edge |
| Non-editing `ArrowUp/ArrowDown`, `Shift+ArrowUp/ArrowDown`, `Tab`, `Shift+Tab` | `shell.global-keyboard` | Selection and structure policy when no block is actively editing |
| Editing `Tab`, `Shift+Tab`, `Cmd/Ctrl+B`, `Cmd/Ctrl+O`, select-all cycle | `shell.global-keyboard` | Editor-scoped shortcuts that still route through shell-level key resolution |
| `Cmd/Ctrl+V` while focused-but-not-editing | `shell.global-keyboard` | Clipboard bootstrap into intent flow |
| `Cmd/Ctrl+A` cycle in edit mode | `shell.global-keyboard` | Cross-cutting selection-mode transition |
| Editing `ArrowUp/ArrowDown` at row boundaries | `components.block` | Requires live row/boundary DOM reads and journals DOM fallback |
| Editing `Shift+ArrowUp/ArrowDown` | `components.block` | Must collapse DOM selection before block-selection transition |
| Editing `ArrowLeft/ArrowRight` at text boundaries | `components.block` | Contenteditable cursor-edge behavior |
| `Enter`, `Shift+Enter`, `Escape`, `Backspace`, `Delete`, paste/input/blur | `components.block` | Browser-sensitive sequencing and pending-buffer handling |
| Plain cursor movement and character insertion inside a block | Browser default | No Evo policy required unless a boundary/context rule triggers |

## Change Rule

Before adding a keyboard behavior, decide which of these questions is true:

1. Is it part of block-local editing flow and does it need live cursor rows,
   selection collapse, IME guards, or browser-specific ordering?
   Put it in `components.block`.
2. Is it an app-global shortcut, or an editor-scoped shortcut that must only
   run when the current DOM target is the active block editor?
   Put it in `shell.global-keyboard`.
3. Is it ordinary text editing with no Evo-specific policy?
   Let the browser own it.

Only extract pure helper functions after ownership is already clear.
