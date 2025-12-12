# Logseq UI Features

UI patterns and features specific to Logseq's desktop application. These extend beyond core structural editing and may or may not be implemented in Evo depending on product direction.

**Note**: For core editing behaviors (navigation, selection, editing, structure), see `STRUCTURAL_EDITING.md`.

---

## 1. Command Palettes

### 1.1 Slash Commands (`/`)

Typing `/` while editing opens inline command menu at caret position.

**Behavior**:
- Arrow keys navigate menu
- Enter executes highlighted command
- Escape closes without action
- Typing filters the list

**Command categories**:
- Block types: heading, quote, code block
- Embeds: block embed, page embed
- Queries: simple query, advanced query
- Media: image, video, audio links
- Templates: insert template
- Properties: add property

**Multi-step commands**: Some commands (link, image, Zotero) spawn secondary input forms.

### 1.2 Quick Switcher (Cmd+K)

Global overlay for page/block search.

**Behavior**:
- Escape current edit mode first
- Opens full-screen overlay
- Results stream as user types
- Arrow keys navigate, Enter opens
- Escape closes, restores previous focus

**Variants**:
- `Cmd+K`: Global search
- `Cmd+Shift+K`: Current page scope

### 1.3 Command Palette (Cmd+Shift+P)

All available commands/shortcuts in searchable overlay.

**Behavior**:
- Lists all registered shortcuts
- Sorted by recent usage (frequency ring buffer)
- Execute any command by name

---

## 2. Sidebar

### 2.1 Right Sidebar

Secondary pane for viewing blocks/pages without losing main context.

**Opening methods**:
- Shift+Enter on selected block(s)
- Shift+Click on block bullet
- Cmd+Shift+O on link

**Behavior**:
- Multiple items can stack in sidebar
- Each item is independently scrollable
- Close individual items or clear all

### 2.2 Shift+Enter (Selection Mode)

When block(s) are selected (not editing):
- Opens selected blocks in right sidebar
- Main outline keeps current focus
- Multiple blocks = multiple sidebar entries

---

## 3. Advanced Clipboard

### 3.1 Copy Variants

| Shortcut | Behavior |
|----------|----------|
| Cmd+C (selection) | Copy blocks with Logseq metadata (HTML + Markdown + custom MIME) |
| Cmd+C (editing, no range) | Copy current block as `((uuid))` reference |
| Cmd+C (editing, with range) | Browser native copy |
| Cmd+Shift+C | Copy as plain text (strips metadata) |
| Cmd+Shift+E | Copy as embed `{{embed ((uuid))}}` |

### 3.2 Paste Variants

| Shortcut | Behavior |
|----------|----------|
| Cmd+V | Smart paste: check custom MIME first, then HTML, then plain text |
| Cmd+Shift+V | Paste as plain text (no parsing) |

**Smart paste detection order**:
1. Custom MIME payload (if same graph)
2. Attachments (if files present)
3. Rich text (HTML parsing)
4. Macro URLs (video/Twitter wrapping)
5. Block refs (paste inside `(( ))`)
6. Plain text (multi-paragraph splitting)

### 3.3 Cut Behavior

Cut sets `:editor/block-op-type` to `:cut` so paste handlers preserve UUIDs.

### 3.4 Graph Guard

Clipboard from different graph refuses to import custom MIME payload.

---

## 4. Pointer Gestures

### 4.1 Block Bullet

| Gesture | Behavior |
|---------|----------|
| Click | Toggle fold (if has children) |
| Alt+Click | Toggle entire subtree fold |
| Shift+Click | Open block in sidebar |

### 4.2 Block Content

| Gesture | Behavior |
|---------|----------|
| Click | Enter edit mode |
| Shift+Click | Add to selection range |
| Cmd+Click | Toggle individual block in selection |
| Cmd+Shift+Click | Append range to existing selection |

### 4.3 Block References

| Gesture | Behavior |
|---------|----------|
| Hover | Show preview tooltip |
| Click | Navigate to referenced block |
| Cmd+Click | Open in sidebar |

### 4.4 Links

| Gesture | Behavior |
|---------|----------|
| Click | Follow link (page/external) |
| Cmd+Click | Open in sidebar |

---

## 5. Drag & Drop

### 5.1 Block Drag

Drag handle on blocks enables mouse reordering.

| Modifier | Behavior |
|----------|----------|
| None | Move block(s) to drop position |
| Alt | Insert block reference instead of moving |

### 5.2 Drop Zones

- Top/bottom of block: insert before/after
- Indented zone: make child of target
- Climb zones: trigger climb semantics

### 5.3 Format Guard

Dropping between pages with different formats (Markdown vs Org) shows warning and aborts.

### 5.4 Image/Asset Drop & Paste

**Logseq behavior** (file-based graphs):
- **Drop on block (not editing)**: Creates **separate blocks** per image via `api-insert-new-block!`
- **Paste/drop while editing**: Tries inline insertion but has a `FIXME: only the first asset is handled` bug
- **Sequential processing**: Uses `recur` pattern to process images one at a time
- **Deduplication**: Checksum-based via `get-file-checksum`; warns if asset exists
- **Max file size**: 100 MB (`exceed-limit-size?` in `assets.cljs`)
- **Markdown format**: `![name](../assets/filename.png)`

**Logseq behavior** (DB-based graphs):
- **Always separate blocks**: Each image becomes its own block with rich metadata
- **Asset properties**: `:logseq.property.asset/type`, `:logseq.property.asset/size`, `:logseq.property.asset/checksum`
- **Parallel save**: Uses `p/all` to save all assets concurrently
- **Batch insert**: `insert-blocks!` creates all asset blocks atomically

**Key difference: Logseq does NOT support multiple images inline in one block.**

**Evo divergence**:
- **Inline multiple images**: Evo inserts multiple images **in the same block** as space-separated markdown: `![img1](path1) ![img2](path2)`
- **Concurrent upload**: Evo uploads all images in parallel via Promise.all
- **Simpler model**: No separate asset blocks, just inline markdown
- No deduplication (yet)
- No file size limit (yet)

**Source**:
- `frontend/handler/editor.cljs:1582-1620` (`db-based-save-assets!` - DB graphs)
- `frontend/handler/file_based/editor.cljs:341-371` (`file-upload-assets!` - file graphs)
- `frontend/components/block.cljs:3449-3476` (drop handler for non-editing)
- `frontend/handler/assets.cljs:22-25` (size limit check)

---

## 6. Move Dialog (Cmd+Shift+M)

Opens picker to move selected blocks under arbitrary target block/page.

**Behavior**:
- Search for target page/block
- Preview target location
- Confirm moves blocks to new location

---

## 7. Link Helpers

| Shortcut | Behavior |
|----------|----------|
| Cmd+L | Insert/wrap link at cursor |
| Cmd+O | Follow link under cursor |
| Cmd+Shift+O | Open link in sidebar |
| Cmd+Shift+R | Replace block ref with resolved content |

---

## 7.5 Page References & Backlinks

### Page References (`[[Page]]`)

Typing `[[` triggers page autocomplete. Selecting or completing inserts a page reference link.

**Behavior**:
- `[[` opens autocomplete popup at cursor
- Arrow keys navigate suggestions
- Enter/Tab selects highlighted page
- Typing filters by fuzzy match
- "Create new page" option appears when no exact match
- Escape dismisses without action

**Page ref rendering**:
- Displayed as orange clickable links (Logseq style: `#d9730d`)
- Click navigates to the referenced page
- If page doesn't exist, creates it on navigation

**Source**: `src/main/frontend/components/editor.cljs`, `handler/page.cljs`

### Linked References (Backlinks)

Every page shows "Linked References" - blocks from other pages that reference it via `[[Page]]`.

**Query behavior**:
- Scans all blocks for `[[Current Page]]` pattern
- Case-insensitive matching
- Excludes self-references (blocks on the current page)
- Groups results by source page

**Display**:
- Appears below page outline
- Shows count badge with total backlinks
- Blocks grouped under source page headers
- Source page header is clickable (navigates to that page)
- `[[Page]]` refs within backlink text are clickable

**Filtering** (Logseq advanced):
- Include/exclude filters stored as page properties
- Shift-click toggles between include/exclude modes
- Filter state persists in DB

**Source**: `src/main/frontend/components/reference.cljs`, `deps/db/src/logseq/db/common/reference.cljs`

### Block References (`((block-id))`)

Reference a specific block by its UUID.

**Behavior**:
- `((` triggers block search autocomplete
- Shows block content preview
- Click on rendered ref navigates to source block
- Cmd+Shift+R replaces ref with actual content

---

## 8. Block Properties

### 8.1 Property Trigger (`:`)

Typing `:` at start of block opens property autocomplete.

### 8.2 Property Editing

Properties appear as key-value pairs. Values can be:
- Plain text
- Page references
- Dates
- Numbers

---

## 9. Templates

### 9.1 Insert Template

Via slash command `/template` or property `:template`.

### 9.2 Template Variables

Templates can include dynamic placeholders:
- `<%today%>`: Current date
- `<%time%>`: Current time
- `<%current page%>`: Page name

---

## 10. PDF Integration

### 10.1 Highlights

PDF annotations create blocks with highlight references.

### 10.2 Copy from PDF

Cmd+C in PDF viewer copies highlight text, not block ref.

### 10.3 Navigation

Clicking PDF highlight block jumps to highlight in PDF panel.

---

## 11. Whiteboard Integration

### 11.1 Portal Context

Blocks in whiteboards maintain portal context for references.

### 11.2 Whiteboard Paste

Clipboard with `<whiteboard-tldr>` metadata extracts shapes instead of text.

---

## Implementation Status (Evo)

| Feature | Status | Priority |
|---------|--------|----------|
| **Core Editing** | | |
| Drag & drop | ✅ Implemented | - |
| Multi-block copy w/ hierarchy | ✅ Implemented | - |
| Multi-block cut | ✅ Implemented | - |
| Backspace merge-previous | ✅ Implemented | - |
| Block refs `((uuid))` rendering | Not planned | - |
| Block embeds `{{embed}}` | Not planned | - |
| Smart paste (URL detection) | Not implemented | Medium |
| **Commands & Search** | | |
| Slash commands | Not implemented | Could add |
| Quick switcher | Not implemented | Could add |
| Command palette | Not implemented | Low |
| **Sidebar & Panels** | | |
| Right sidebar | Basic structure | Could extend |
| Shift+Enter → sidebar | Not implemented | Easy add |
| **Pointer Gestures** | | |
| Cmd+Click toggle | Not implemented | Easy add |
| Move dialog | Not implemented | Medium effort |
| **Data Model** | | |
| Block properties | Not implemented | Future |
| Templates | Not implemented | Future |
| PDF integration | Not implemented | N/A |
| Whiteboard | Not implemented | N/A |

---

## Implementation Notes

### Smart Paste Detection

Enhance `:paste-text` to detect content types:
1. URL patterns → wrap in appropriate macros
   - YouTube/Vimeo → `{{video URL}}`
   - Twitter → `{{twitter URL}}`
2. Markdown structure → preserve formatting
3. Code blocks → wrap in triple backticks

**Location**: Extend `plugins/clipboard.cljc` paste handler

---

## Notes

These features are Logseq-specific UI patterns. When implementing:

1. **Slash commands**: Need autocomplete component, command registry, multi-step form support
2. **Quick switcher**: Need overlay component, streaming search, keyboard navigation
3. **Sidebar**: Need panel management, multiple items, independent scroll
4. **Drag & drop**: Need drag handles, drop indicators, modifier detection
5. **Properties**: Need schema system, autocomplete, validation

Core structural editing (STRUCTURAL_EDITING.md) should be solid before tackling these.
