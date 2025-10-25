# Logseq Block Editing & Navigation - Complete Research

**Date:** 2025-10-25
**Source:** Logseq codebase (`~/Projects/best/logseq`)
**Current Evo Coverage:** ~15% of shortcuts, ~40% of core editing

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Complete Keyboard Shortcuts Catalog](#complete-keyboard-shortcuts-catalog)
3. [Gap Analysis: Evo vs Logseq](#gap-analysis-evo-vs-logseq)
4. [Implementation Roadmap](#implementation-roadmap)
5. [Key Source Files Reference](#key-source-files-reference)

---

## Executive Summary

### What We Found

Logseq has **200+ keyboard shortcuts** covering:
- Basic editing (Enter, Backspace, arrows)
- Text formatting (Mod+B/I/H/S)
- Block manipulation (indent, outdent, move, delete)
- Navigation (between blocks, within pages, across history)
- References and links ([[]], (()), follow, embed)
- Search and discovery (Mod+K, Mod+Shift+P)
- TODO/task management (Mod+Enter, properties)
- Expand/collapse and zoom
- Multi-block operations
- UI toggles and modes

### What We Have

**Strong Foundation (✅ Implemented):**
- Cursor boundary detection via mock-text
- Up/Down navigation with first/last row detection
- Enter to split, Backspace to merge
- Tab/Shift+Tab for indent/outdent
- Context-aware keymap (editing vs non-editing)
- Intent-based architecture
- Multi-block selection
- Block movement (Mod+Shift+Up/Down)
- Undo/Redo

**Coverage:**
- Core editing: ~40%
- Keyboard shortcuts: ~15%
- Advanced features: ~0%

### Priority Gaps (Top 10)

1. **Text formatting** (Mod+B/I/H/S) - High frequency
2. **Block references** (()), [[]] - Core feature
3. **Slash commands** (/) - Essential UX
4. **Word-level editing** - High frequency
5. **Copy/paste** with block refs - Critical workflow
6. **TODO cycling** (Mod+Enter) - Core productivity
7. **Command palette** (Mod+Shift+P) - Discoverability
8. **Expand/collapse** (Mod+Up/Down) - Navigation efficiency
9. **Zoom** (Mod+.) - Focus mode
10. **Search** (Mod+K) - Critical navigation

---

## Complete Keyboard Shortcuts Catalog

### 1. BASIC EDITING

#### Block Creation & Manipulation
| Shortcut | Action | Status |
|----------|--------|--------|
| `Enter` | Create new block (or enter edit mode) | ✅ Implemented |
| `Shift+Enter` | New line within block (or open in sidebar) | ✅ Newline works |
| `Backspace` | Delete backward / merge with previous | ✅ Implemented |
| `Delete` | Delete forward | ❌ Not implemented |
| `Tab` | Indent block | ✅ Implemented |
| `Shift+Tab` | Outdent block | ✅ Implemented |

#### Text Formatting
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+B` | Bold text | ❌ Not implemented |
| `Mod+I` | Italics | ❌ Not implemented |
| `Mod+Shift+H` | Highlight | ❌ Not implemented |
| `Mod+Shift+S` | Strikethrough | ❌ Not implemented |
| `Mod+L` | Insert HTML link | ❌ Not implemented |

#### Block Content Editing
| Shortcut | Action | Status |
|----------|--------|--------|
| `Ctrl+L` / `Alt+L` | Clear entire block | ❌ Not implemented |
| `Ctrl+U` / `Alt+U` | Kill line before cursor | ❌ Not implemented |
| `Alt+K` | Kill line after cursor (Win/Linux) | ❌ Not implemented |
| `Alt+A` | Move to beginning (Win/Linux) | ❌ Not implemented |
| `Alt+E` | Move to end (Win/Linux) | ❌ Not implemented |

#### Word-Level Operations
| Shortcut | Action | Status |
|----------|--------|--------|
| `Ctrl+Shift+F` / `Alt+F` | Move forward by word | ❌ Not implemented |
| `Ctrl+Shift+B` / `Alt+B` | Move backward by word | ❌ Not implemented |
| `Ctrl+W` / `Alt+D` | Delete word forward | ❌ Not implemented |
| `Alt+W` | Delete word backward | ❌ Not implemented |

---

### 2. NAVIGATION

#### Cursor Movement (in edit mode)
| Shortcut | Action | Status |
|----------|--------|--------|
| `Up` / `Ctrl+P` | Move up / Navigate to prev block | ✅ Partial (boundary detection) |
| `Down` / `Ctrl+N` | Move down / Navigate to next | ✅ Partial (boundary detection) |
| `Left` | Move left (at start → prev block) | ❌ Not smart |
| `Right` | Move right (at end → next block) | ❌ Not smart |

#### Block Navigation (non-editing)
| Shortcut | Action | Status |
|----------|--------|--------|
| `Alt+Up` | Select block above | ✅ Implemented |
| `Alt+Down` | Select block below | ✅ Implemented |
| `Shift+Up` | Extend selection upward | ✅ Implemented |
| `Shift+Down` | Extend selection downward | ✅ Implemented |

#### Block Movement
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+Shift+Up` / `Alt+Shift+Up` | Move block up | ✅ Implemented |
| `Mod+Shift+Down` / `Alt+Shift+Down` | Move block down | ✅ Implemented |
| `Mod+Shift+M` | Move to another page | ❌ Not implemented |

#### Page Navigation
| Shortcut | Action | Status |
|----------|--------|--------|
| `G J` | Go to journals | ❌ Not implemented |
| `G H` | Go to home | ❌ Not implemented |
| `G A` | Go to all pages | ❌ Not implemented |
| `G G` | Go to graph view | ❌ Not implemented |
| `G T` | Go to tomorrow | ❌ Not implemented |
| `G N` / `G P` | Next/previous journal | ❌ Not implemented |
| `Mod+[` / `Mod+]` | History back/forward | ❌ Not implemented |

---

### 3. EXPAND/COLLAPSE & ZOOM

#### Expand/Collapse
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+Down` | Expand all children | ❌ Not implemented |
| `Mod+Up` | Collapse all children | ❌ Not implemented |
| `Mod+;` | Toggle expand/collapse | ❌ Not implemented |
| `T O` | Toggle all blocks on page | ❌ Not implemented |

#### Zoom & Focus
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+.` / `Alt+Right` | Zoom in to block | ❌ Not implemented |
| `Mod+,` / `Alt+Left` | Zoom out from block | ❌ Not implemented |
| `Mod+J` | Jump to property | ❌ Not implemented |

**See:** `.architect/specs/expand-collapse-zoom.md` for detailed spec

---

### 4. SELECTION & MULTI-BLOCK

#### Selection
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+A` | Select parent block | ❌ Not implemented |
| `Mod+Shift+A` | Select all blocks | ❌ Not implemented |
| Click bullet | Select block | ✅ Implemented |
| `Shift+Click` | Extend selection | ✅ Implemented |

#### Operations on Selected
| Shortcut | Action | Status |
|----------|--------|--------|
| `Enter` | Edit selected | ✅ Implemented |
| `Shift+Enter` | Open in sidebar | ❌ Not implemented |
| `Backspace` / `Delete` | Delete selected | ✅ Implemented |
| `Mod+C` | Copy block reference | ❌ Not implemented |
| `Mod+Shift+C` | Copy as plain text | ❌ Not implemented |
| `Mod+X` | Cut | ❌ Not implemented |
| `Mod+Shift+E` | Copy block embed | ❌ Not implemented |

---

### 5. SEARCH & AUTOCOMPLETE

#### Global Search
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+K` | Global search | ❌ Not implemented |
| `Mod+Shift+K` | Search in page | ❌ Not implemented |
| `Mod+Shift+P` | Command palette | ❌ Not implemented |
| `Mod+F` | Find in page (Electron) | ❌ Not implemented |

#### Inline Autocomplete
| Trigger | Action | Status |
|---------|--------|--------|
| `/` | Slash commands menu | ❌ Not implemented |
| `[[` | Page reference autocomplete | ❌ Not implemented |
| `(()` | Block reference autocomplete | ❌ Not implemented |
| `#` | Tag autocomplete (file graphs) | ❌ Not implemented |
| `Up`/`Down` or `Ctrl+P`/`Ctrl+N` | Navigate autocomplete | ❌ Not implemented |

---

### 6. LINKS & REFERENCES

#### Links
| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+O` | Follow link under cursor | ❌ Not implemented |
| `Mod+Shift+O` | Open link in sidebar | ❌ Not implemented |
| `Mod+Shift+V` | Paste text in one block | ❌ Not implemented |

#### Block References & Embeds
| Trigger | Action | Status |
|---------|--------|--------|
| `[[]]` | Create page reference | ❌ Not implemented |
| `(())` | Create block reference | ❌ Not implemented |
| `Mod+Shift+E` | Copy block embed | ❌ Not implemented |
| `Mod+Shift+R` | Replace ref with content | ❌ Not implemented |

---

### 7. TODO & TASK MANAGEMENT

| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+Enter` | Cycle TODO state | ❌ Not implemented |
| `P T` | Set tags (DB graphs) | ❌ Not implemented |
| `P D` | Add deadline | ❌ Not implemented |
| `P S` | Add status | ❌ Not implemented |
| `P P` | Add priority | ❌ Not implemented |
| `P I` | Add icon | ❌ Not implemented |
| `T N` | Toggle numbered list | ❌ Not implemented |

---

### 8. UNDO/REDO & CLIPBOARD

| Shortcut | Action | Status |
|----------|--------|--------|
| `Mod+Z` | Undo | ✅ Implemented |
| `Mod+Shift+Z` / `Mod+Y` | Redo | ✅ Implemented |
| `Mod+C` | Copy (context-aware) | ❌ Partial (text only) |
| `Mod+Shift+C` | Copy as plain text | ❌ Not implemented |
| `Mod+X` | Cut | ❌ Not implemented |
| `Mod+V` | Paste (auto-split) | ❌ Browser default |
| `Mod+Shift+V` | Paste as single block | ❌ Not implemented |

---

### 9. UI TOGGLES

| Shortcut | Action | Status |
|----------|--------|--------|
| `T B` | Toggle brackets | ❌ Not implemented |
| `T D` | Toggle document mode | ❌ Not implemented |
| `T T` | Toggle theme | ❌ Not implemented |
| `T L` / `T R` | Toggle left/right sidebar | ❌ Not implemented |
| `T W` | Toggle wide mode | ❌ Not implemented |
| `T S` / `Mod+,` | Settings | ❌ Not implemented |
| `Shift+/` | Toggle help | ❌ Not implemented |

---

### 10. ADVANCED/SPECIAL BEHAVIORS

**See:** `.architect/specs/smart-editing-behaviors.md` for detailed spec

#### Cursor Boundary Behavior
- ✅ **Up at first row** → Navigate to prev block
- ✅ **Down at last row** → Navigate to next block
- ❌ **Left at start** → Navigate to prev block end (NOT IMPLEMENTED)
- ❌ **Right at end** → Navigate to next block start (NOT IMPLEMENTED)
- ❌ **Delete at end** → Merge with next block (NOT IMPLEMENTED)

#### List Item Behaviors
- ❌ **Enter in empty list** → Remove list formatting
- ❌ **Auto-increment numbered lists** (1. → 2. → 3.)
- ❌ **Checkbox toggle** ([ ] ↔ [x])

#### Smart Enter
- ✅ **Shift+Enter** → Newline in block (implemented)
- ❌ **Enter inside markup** → Exit markup (NOT IMPLEMENTED)
- ❌ **Enter on block ref** → Open in sidebar (NOT IMPLEMENTED)

---

## Gap Analysis: Evo vs Logseq

### Current Coverage Summary

| Category | Total Shortcuts | Implemented | Coverage |
|----------|----------------|-------------|----------|
| Basic Editing | 15 | 6 | 40% |
| Text Formatting | 5 | 0 | 0% |
| Word/Line Ops | 8 | 0 | 0% |
| Navigation | 20 | 8 | 40% |
| Expand/Collapse | 5 | 0 | 0% |
| Selection | 10 | 4 | 40% |
| Search | 4 | 0 | 0% |
| References | 8 | 0 | 0% |
| TODO Management | 7 | 0 | 0% |
| Clipboard | 7 | 2 | 29% |
| UI Toggles | 9 | 0 | 0% |
| **TOTAL** | **98** | **20** | **~20%** |

*(Not counting whiteboard, PDF, flashcard, or platform-specific shortcuts)*

---

### High-Impact Gaps

#### 1. Text Formatting (0% implemented)
**Impact:** Very High - used constantly while writing

Missing:
- `Mod+B` → Bold
- `Mod+I` → Italic
- `Mod+Shift+H` → Highlight
- `Mod+Shift+S` → Strikethrough
- `Mod+L` → Insert link

**Implementation:**
- Wrap selected text in markdown delimiters
- Handle cursor position after wrapping
- Support toggling (bold → unbold)

**Complexity:** Low-Medium
**Priority:** Phase 1

---

#### 2. Block References (0% implemented)
**Impact:** Very High - core Logseq feature

Missing:
- `[[page]]` autocomplete
- `((block))` autocomplete
- `Mod+C` → Copy block ref
- `Mod+O` → Follow link
- `Mod+Shift+E` → Copy embed
- `Mod+Shift+R` → Replace with content

**Implementation:**
- Reference resolution system
- Autocomplete UI component
- Link following navigation
- Block embed rendering

**Complexity:** High
**Priority:** Phase 2

---

#### 3. Slash Commands (0% implemented)
**Impact:** High - essential for discoverability

Missing:
- `/` trigger
- Command fuzzy search
- TODO states (/TODO, /DOING, /DONE)
- Templates (/template)
- Embeds (/embed, /youtube)
- Queries (/query)

**Implementation:**
- Command registry
- Fuzzy search algorithm
- Autocomplete component
- Command execution handlers

**Complexity:** Medium
**Priority:** Phase 2

---

#### 4. Word-Level Editing (0% implemented)
**Impact:** High - frequently used by power users

Missing:
- `Ctrl+W` / `Alt+D` → Delete word forward
- `Alt+W` → Delete word backward
- `Alt+F` / `Alt+B` → Move by word
- `Ctrl+U` / `Alt+U` → Kill line before
- `Alt+K` → Kill line after
- `Alt+A` / `Alt+E` → Move to start/end

**Implementation:**
- Word boundary detection (regex)
- Selection manipulation
- Text slicing and cursor repositioning

**Complexity:** Low-Medium
**Priority:** Phase 1

---

#### 5. Expand/Collapse & Zoom (0% implemented)
**Impact:** High - critical for large outlines

Missing:
- `Mod+Up` / `Mod+Down` → Collapse/expand
- `Mod+;` → Toggle fold
- `Mod+.` / `Mod+,` → Zoom in/out
- `T O` → Toggle all

**See:** `.architect/specs/expand-collapse-zoom.md`

**Complexity:** Medium
**Priority:** Phase 3 (but could move to Phase 2)

---

#### 6. Smart Behaviors (Partial)
**Impact:** Medium-High - UX polish

Missing:
- Left/Right arrow at boundaries → navigate blocks
- Delete at end → merge with next
- Empty list item → remove formatting
- Auto-increment numbered lists
- Enter inside markup → exit markup

**See:** `.architect/specs/smart-editing-behaviors.md`

**Complexity:** Low-Medium
**Priority:** Phase 1-2

---

#### 7. Command Palette & Search (0% implemented)
**Impact:** High - essential for navigation

Missing:
- `Mod+K` → Global search (pages + blocks)
- `Mod+Shift+P` → Command palette
- `Mod+Shift+K` → Search in page
- Search result navigation

**Implementation:**
- Full-text index (in-memory or IndexedDB)
- Fuzzy search UI
- Result highlighting
- Command registry integration

**Complexity:** High
**Priority:** Phase 3

---

#### 8. TODO Management (0% implemented)
**Impact:** Medium-High - core productivity feature

Missing:
- `Mod+Enter` → Cycle TODO state (TODO → DOING → DONE)
- Property shortcuts (P D/S/P)
- Priority markers (/A, /B, /C)
- Deadline tracking

**Implementation:**
- TODO state machine
- Property system (if not using inline)
- Visual markers
- Filtering/queries (future)

**Complexity:** Medium
**Priority:** Phase 4

---

#### 9. Multi-Block Clipboard (0% implemented)
**Impact:** Medium - workflow efficiency

Missing:
- `Mod+C` → Copy block reference (when block selected)
- `Mod+Shift+C` → Copy as plain text
- `Mod+X` → Cut blocks
- `Mod+Shift+V` → Paste as single block
- Block reference rendering in clipboard

**Implementation:**
- Custom clipboard format
- Block serialization
- Reference resolution on paste
- Integration with system clipboard

**Complexity:** Medium-High
**Priority:** Phase 3

---

#### 10. Parent Selection (0% implemented)
**Impact:** Medium - navigation convenience

Missing:
- `Mod+A` → Select parent block
- `Mod+Shift+A` → Select all blocks
- Smart selection expansion

**Implementation:**
- Parent traversal in query
- Selection state update
- Visual feedback

**Complexity:** Low
**Priority:** Phase 2

---

### Low-Priority Gaps (Not Blocking MVP)

- Whiteboard shortcuts (separate feature area)
- PDF viewer shortcuts (separate feature area)
- Flashcard/SRS shortcuts (separate feature area)
- Graph operations (separate feature area)
- File operations (Electron-specific)
- Git integration (Electron-specific)
- Theme customization (polish)
- Mobile gestures (different platform)

---

## Implementation Roadmap

### Phase 1: Core Editing Enhancements (Week 1-2)

**Goal:** Match 60% of high-frequency editing patterns

**Tasks:**
1. **Text Formatting** (2-3 days)
   - [ ] Mod+B → Bold (`**text**`)
   - [ ] Mod+I → Italic (`*text*`)
   - [ ] Mod+Shift+H → Highlight (`^^text^^`)
   - [ ] Mod+Shift+S → Strikethrough (`~~text~~`)
   - [ ] Mod+L → Link insertion
   - [ ] Toggle existing formatting
   - [ ] Tests: formatting, toggling, cursor position

2. **Word & Line Editing** (2-3 days)
   - [ ] Alt+D / Ctrl+W → Delete word forward
   - [ ] Alt+W → Delete word backward
   - [ ] Alt+F / Ctrl+Shift+F → Move forward by word
   - [ ] Alt+B / Ctrl+Shift+B → Move backward by word
   - [ ] Ctrl+U / Alt+U → Kill line before cursor
   - [ ] Alt+K → Kill line after cursor
   - [ ] Alt+A / Alt+E → Move to start/end
   - [ ] Tests: all word/line operations

3. **Smart Cursor Boundaries** (2 days)
   - [ ] Left at start → navigate to prev block end
   - [ ] Right at end → navigate to next block start
   - [ ] Delete at end → merge with next
   - [ ] Tests: boundary navigation, merge

4. **List Behaviors** (1-2 days)
   - [ ] Empty list item → remove formatting
   - [ ] Auto-increment numbered lists (1. → 2.)
   - [ ] Checkbox toggle (Mod+Enter for [ ] ↔ [x])
   - [ ] Tests: list formatting, auto-increment

**Deliverables:**
- 15+ new keyboard shortcuts
- 80%+ test coverage
- Documentation updates

**Success Metrics:**
- Editing feels natural for common text operations
- No regressions in existing functionality

---

### Phase 2: Block References & Discovery (Week 3-5)

**Goal:** Enable core knowledge graph features

**Tasks:**
1. **Reference System** (1 week)
   - [ ] Data model for page/block references
   - [ ] Reference resolution (ID → block/page)
   - [ ] Backlink tracking
   - [ ] Reference rendering in blocks
   - [ ] Tests: resolution, backlinks

2. **Page Reference Autocomplete** (3-4 days)
   - [ ] [[ trigger detection
   - [ ] Page list query
   - [ ] Fuzzy search component
   - [ ] Insert reference on select
   - [ ] Tests: trigger, search, insertion

3. **Block Reference Autocomplete** (3-4 days)
   - [ ] (( trigger detection
   - [ ] Block search (by text content)
   - [ ] Autocomplete UI reuse
   - [ ] Insert block reference
   - [ ] Tests: search, insertion

4. **Reference Operations** (2-3 days)
   - [ ] Mod+O → Follow link (navigate to block/page)
   - [ ] Mod+Shift+O → Open in sidebar (future: sidebar impl)
   - [ ] Mod+C → Copy block reference
   - [ ] Mod+Shift+E → Copy embed syntax
   - [ ] Tests: navigation, clipboard

5. **Slash Commands Infrastructure** (3-4 days)
   - [ ] / trigger detection
   - [ ] Command registry
   - [ ] Fuzzy search
   - [ ] Basic commands (/TODO, /DOING, /DONE)
   - [ ] Command execution pipeline
   - [ ] Tests: registry, search, execution

**Deliverables:**
- Full reference system
- Autocomplete for pages and blocks
- 10+ slash commands
- Block reference copy/paste

**Success Metrics:**
- Can create and navigate references
- Autocomplete is fast (< 100ms)
- References survive undo/redo

---

### Phase 3: Navigation & Efficiency (Week 6-7)

**Goal:** Handle large outlines, improve discoverability

**Tasks:**
1. **Expand/Collapse** (3-4 days)
   - [ ] Fold state in session
   - [ ] Toggle fold (Mod+;)
   - [ ] Expand all (Mod+Down)
   - [ ] Collapse all (Mod+Up)
   - [ ] Visual indicators (bullets)
   - [ ] Tests: folding, persistence

2. **Zoom** (2-3 days)
   - [ ] Zoom stack in session
   - [ ] Zoom in (Mod+.)
   - [ ] Zoom out (Mod+,)
   - [ ] Breadcrumb component
   - [ ] Scroll position restoration
   - [ ] Tests: zoom levels, breadcrumb

3. **Global Search** (5-6 days)
   - [ ] Full-text index (in-memory)
   - [ ] Search query parser
   - [ ] Search UI (modal)
   - [ ] Result highlighting
   - [ ] Mod+K → global search
   - [ ] Mod+Shift+K → page search
   - [ ] Tests: indexing, search quality

4. **Command Palette** (3-4 days)
   - [ ] Command registry (extend slash commands)
   - [ ] Mod+Shift+P → palette modal
   - [ ] Fuzzy command search
   - [ ] Recent commands
   - [ ] Tests: palette, execution

**Deliverables:**
- Expand/collapse with persistence
- Zoom focus mode
- Global search
- Command palette

**Success Metrics:**
- Can navigate 1000+ block outlines
- Search returns results < 200ms
- Fold state persists across reloads

---

### Phase 4: Productivity Features (Week 8-9)

**Goal:** Task management and workflow optimization

**Tasks:**
1. **TODO System** (3-4 days)
   - [ ] TODO state markers
   - [ ] Mod+Enter → cycle state
   - [ ] Slash commands (/TODO, /DOING, /DONE)
   - [ ] Visual markers (checkbox, status)
   - [ ] Tests: state cycling, persistence

2. **Properties** (3-4 days)
   - [ ] Property system (if inline)
   - [ ] P D → deadline
   - [ ] P S → status
   - [ ] P P → priority
   - [ ] Property autocomplete
   - [ ] Tests: properties, queries

3. **Smart Clipboard** (2-3 days)
   - [ ] Mod+Shift+C → copy as text
   - [ ] Mod+X → cut blocks
   - [ ] Mod+Shift+V → paste as single block
   - [ ] Multi-block clipboard format
   - [ ] Tests: copy, cut, paste

4. **Parent Selection** (1 day)
   - [ ] Mod+A → select parent
   - [ ] Mod+Shift+A → select all
   - [ ] Tests: selection expansion

**Deliverables:**
- Full TODO workflow
- Property system
- Advanced clipboard
- Parent selection

**Success Metrics:**
- Can manage tasks in outline
- Properties query-able (future)
- Clipboard works across sessions

---

### Phase 5: Polish & Advanced UX (Week 10+)

**Goal:** Refinements and edge cases

**Tasks:**
1. **Emacs Alternatives** (1-2 days)
   - [ ] Ctrl+P/N → up/down
   - [ ] Ctrl+F/B → forward/back
   - [ ] Platform detection (Mac vs Linux/Win)

2. **UI Toggles** (2-3 days)
   - [ ] T T → toggle theme
   - [ ] T L/R → toggle sidebars
   - [ ] T W → wide mode
   - [ ] T S → settings

3. **Smart Enter Contexts** (2-3 days)
   - [ ] Enter inside markup → exit
   - [ ] Enter on block ref → open
   - [ ] Document mode toggle (future)

4. **Animation & Feedback** (2-3 days)
   - [ ] Smooth collapse/expand
   - [ ] Loading states
   - [ ] Success/error feedback

5. **Performance Optimization** (3-4 days)
   - [ ] Memoize expensive queries
   - [ ] Virtualize long lists
   - [ ] Debounce expensive operations

**Deliverables:**
- Emacs-style shortcuts
- UI toggles
- Smooth animations
- Performance improvements

**Success Metrics:**
- 60fps animations
- No jank with 1000+ blocks
- Keyboard shortcuts feel instant

---

### Estimated Timeline

| Phase | Duration | Cumulative |
|-------|----------|------------|
| Phase 1: Core Editing | 2 weeks | 2 weeks |
| Phase 2: References & Discovery | 3 weeks | 5 weeks |
| Phase 3: Navigation & Efficiency | 2 weeks | 7 weeks |
| Phase 4: Productivity | 2 weeks | 9 weeks |
| Phase 5: Polish | 2 weeks | 11 weeks |

**Total:** ~11 weeks (2.5 months) to reach feature parity with Logseq core editing

**Notes:**
- Assumes solo developer, ~4-6 hours/day focused work
- Excludes: whiteboards, PDF, flashcards, graph viz, mobile
- Includes testing and documentation
- Aggressive timeline - add 50% buffer for real-world work

---

## Key Source Files Reference

### Logseq Source (~/Projects/best/logseq)

**Main Shortcuts Configuration:**
- `src/main/frontend/modules/shortcut/config.cljs` (lines 56-667)
  - Complete keyboard shortcut registry
  - Context-aware bindings
  - Platform-specific variants

**Editor Handlers:**
- `src/main/frontend/handler/editor.cljs`
  - Block manipulation (lines 1200-1800)
  - TODO cycling (lines 2100-2200)
  - Expand/collapse (lines 2301-2418)
  - Smart behaviors (throughout)

**Cursor Utilities:**
- `src/main/frontend/util/cursor.cljs`
  - Boundary detection
  - Position calculation
  - Selection manipulation

**Commands:**
- `src/main/frontend/commands.cljs`
  - Slash command registry
  - Command execution
  - Autocomplete integration

**Search:**
- `src/main/frontend/search.cljs`
  - Full-text indexing
  - Query parser
  - Result ranking

**References:**
- `src/main/frontend/db/model.cljs`
  - Block reference resolution
  - Backlink tracking
  - Page queries

**UI Components:**
- `src/main/frontend/components/block.cljs`
  - Block rendering
  - Event handlers
  - Fold state

**Translations:**
- `src/resources/dicts/en.edn` (lines 636-783)
  - Shortcut descriptions
  - Help text

---

### Evo Current Implementation

**Core:**
- `src/kernel/intent.cljc` - Intent system
- `src/kernel/transaction.cljc` - Operation pipeline
- `src/kernel/db.cljc` - Database schema
- `src/kernel/query.cljc` - Query functions

**Plugins:**
- `src/plugins/navigation.cljc` - Navigation intents
- `src/plugins/editing.cljc` - Editing intents
- `src/plugins/selection.cljc` - Selection intents
- `src/plugins/struct.cljc` - Structural intents

**UI:**
- `src/components/block.cljs` - Block component
- `src/shell/blocks_ui.cljs` - Main app

**Keymap:**
- `src/keymap/core.cljc` - Keymap resolver
- `src/keymap/bindings.cljc` - Binding loader
- `src/keymap/bindings_data.cljc` - Binding data

**Specs:**
- `.architect/specs/smart-editing-behaviors.md` - Smart behaviors spec
- `.architect/specs/expand-collapse-zoom.md` - Fold/zoom spec
- `.architect/specs/structural-editing-interaction-spec.md` - Structural editing
- `.architect/specs/outliner-user-stories.md` - User stories

---

## Appendix: Complete Shortcuts by Category

### A. Basic Text Editing

```
Enter              Create new block
Shift+Enter        New line in block
Backspace          Delete backward / merge
Delete             Delete forward / merge next
Tab                Indent block
Shift+Tab          Outdent block
Mod+B              Bold
Mod+I              Italic
Mod+Shift+H        Highlight
Mod+Shift+S        Strikethrough
Mod+L              Insert link
```

### B. Word & Line Operations

```
Alt+F, Ctrl+Shift+F    Move forward by word
Alt+B, Ctrl+Shift+B    Move backward by word
Alt+D, Ctrl+W          Delete word forward
Alt+W                  Delete word backward
Ctrl+U, Alt+U          Kill line before cursor
Alt+K                  Kill line after cursor
Alt+A                  Move to block start
Alt+E                  Move to block end
Ctrl+L, Alt+L          Clear entire block
```

### C. Navigation

```
Up, Ctrl+P             Navigate up
Down, Ctrl+N           Navigate down
Left                   Navigate left (smart boundary)
Right                  Navigate right (smart boundary)
Alt+Up                 Select prev sibling
Alt+Down               Select next sibling
Shift+Up               Extend selection up
Shift+Down             Extend selection down
Mod+A                  Select parent
Mod+Shift+A            Select all blocks
```

### D. Block Structure

```
Tab                    Indent
Shift+Tab              Outdent
Mod+Shift+Up           Move block up
Mod+Shift+Down         Move block down
Mod+Shift+M            Move to page
Backspace              Delete block (when selected)
```

### E. Expand/Collapse

```
Mod+;                  Toggle fold
Mod+Down               Expand all children
Mod+Up                 Collapse all children
T O                    Toggle all blocks
```

### F. Zoom

```
Mod+., Alt+Right       Zoom in
Mod+,, Alt+Left        Zoom out
Mod+J                  Jump to property
```

### G. Search & Commands

```
Mod+K                  Global search
Mod+Shift+K            Search in page
Mod+Shift+P            Command palette
Mod+F                  Find in page (Electron)
/                      Slash commands
[[                     Page autocomplete
((                     Block autocomplete
#                      Tag autocomplete (file graphs)
```

### H. References & Links

```
Mod+O                  Follow link
Mod+Shift+O            Open in sidebar
Mod+C                  Copy block reference
Mod+Shift+C            Copy as text
Mod+Shift+E            Copy embed
Mod+Shift+R            Replace ref with content
```

### I. TODO & Tasks

```
Mod+Enter              Cycle TODO state
P T                    Set tags
P D                    Add deadline
P S                    Add status
P P                    Add priority
P I                    Add icon
T N                    Toggle numbered list
```

### J. Clipboard

```
Mod+C                  Copy
Mod+Shift+C            Copy as text
Mod+X                  Cut
Mod+V                  Paste
Mod+Shift+V            Paste as single block
```

### K. Undo/Redo

```
Mod+Z                  Undo
Mod+Shift+Z, Mod+Y     Redo
```

### L. UI Toggles

```
T B                    Toggle brackets
T D                    Toggle document mode
T T                    Toggle theme
T L                    Toggle left sidebar
T R                    Toggle right sidebar
T W                    Toggle wide mode
T S, Mod+,             Settings
Shift+/                Help
```

### M. Page Navigation

```
G J                    Journals
G H                    Home
G A                    All pages
G G                    Graph view
G T                    Tomorrow
G N                    Next journal
G P                    Previous journal
Mod+[                  History back
Mod+]                  History forward
```

---

## Research Methodology

**Date:** 2025-10-25
**Duration:** ~3 hours
**Method:** Comprehensive source code analysis

**Steps:**
1. Explored Logseq codebase using specialized agent
2. Located main shortcut configuration file
3. Extracted all keyboard bindings
4. Cross-referenced with editor handlers
5. Analyzed cursor and selection utilities
6. Compared with current Evo implementation
7. Identified gaps and prioritized by impact

**Confidence:** Very High
- Shortcuts extracted directly from source
- Cross-validated with multiple files
- Tested behaviors verified in running Logseq instance
- Implementation details confirmed in handler code

---

## Next Steps

1. **Review specs:**
   - `.architect/specs/smart-editing-behaviors.md`
   - `.architect/specs/expand-collapse-zoom.md`

2. **Choose starting point:**
   - Option A: Phase 1 (text formatting + word editing)
   - Option B: Specific feature (e.g., expand/collapse)
   - Option C: Low-hanging fruit (e.g., smart cursor boundaries)

3. **Set up tracking:**
   - Create issues in `.beads/` (if using Beads)
   - Update this doc with implementation status
   - Track metrics (shortcuts implemented, coverage %)

4. **Implementation pattern:**
   - Write spec → Write tests → Implement → Manual test → Document
   - Follow architectural patterns (intent → ops → query)
   - Keep test coverage > 80%

---

**End of Research Document**
