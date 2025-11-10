# Editing & Navigation Feature Specs

**Last Updated:** 2025-11-09

This directory contains specifications for implementing Logseq-compatible editing and navigation features in Evo.

---

## 📂 File Structure

### **Active Specs** (Not Yet Implemented)

These are the NEW features to implement:

1. **SHIFT_ARROW_TEXT_SELECTION_SPEC.md** ⚡ HIGHEST PRIORITY
   - **What:** Shift+↑/↓ does text selection when NOT at row boundaries
   - **Why Critical:** Most noticeable UX gap vs Logseq
   - **Effort:** 15 minutes (row detection already exists!)
   - **Status:** ❌ Not implemented

2. **AUTOCOMPLETE_SPEC.md** ⚡ CRITICAL
   - **What:** Page `[[`, block `((`, and slash `/` autocomplete
   - **Why Critical:** Core Logseq feature, very visible
   - **Effort:** 3-4 days
   - **Status:** ❌ Not implemented
   - **Includes:**
     - Page reference search with fuzzy matching
     - Block full-text search
     - Slash command menu (TODO, bold, query, etc.)

3. **WORD_NAVIGATION_AND_KILL_COMMANDS_SPEC.md**
   - **What:** Emacs-style word navigation and kill commands
   - **Why Important:** Power users expect these
   - **Effort:** 1-2 days
   - **Status:** ❌ Not implemented
   - **Includes:**
     - Word navigation: `Alt+F/B`, `Ctrl+Shift+F/B`
     - Kill commands: `Ctrl+U`, `Alt+K`, `Ctrl+W`, `Alt+W`

4. **REMAINING_EDITING_GAPS.md**
   - **What:** Additional features after the above are done
   - **Why Important:** Nice-to-haves for 100% parity
   - **Effort:** 2-3 days total
   - **Status:** ❌ Not implemented
   - **Includes:**
     - Highlight/strikethrough formatting
     - Follow link under cursor (`Cmd+O`)
     - Select parent/all (`Cmd+A`, `Cmd+Shift+A`)
     - Copy as plain text (`Cmd+Shift+C`)
     - Format selection as link (`Cmd+L`)
     - Toggle numbered list (`t n`)

### **Testing Strategy**

5. **TESTING_STRATEGY_NAVIGATION.md**
   - General testing approach for navigation features
   - 95/5 split: Unit tests vs Browser E2E
   - Manual testing checklists

### **Archive** (Already Implemented)

6. **archive/TEXT_EDITING_BEHAVIORS_SPEC.md** ✅ DONE
   - Context detection (markup, refs, code blocks, lists)
   - Paired character handling (auto-close, delete pairs)
   - Context-aware Enter
   - Delete key merge operations
   - Arrow boundary navigation
   - Multi-byte character support
   - **Status:** ✅ Already implemented in Evo
   - **Evidence:** `src/plugins/context.cljc`, `src/plugins/smart_editing.cljc`

7. **archive/TEXT_EDITING_TESTING_STRATEGY.md** ✅ DONE
   - Original testing strategy document
   - Most tests already written and passing

---

## 🎯 Implementation Priority Order

### Phase 1: Quick Wins (1 day)
**Goal:** Fix the most noticeable UX gap and add easy shortcuts

- [ ] **SHIFT_ARROW_TEXT_SELECTION** (15 min)
  - Fix `Shift+↑/↓` to do text selection when not at boundaries
  - See: `SHIFT_ARROW_TEXT_SELECTION_SPEC.md`

- [ ] **Highlight & Strikethrough** (5 min)
  - Add `Cmd+Shift+H` and `Cmd+Shift+S` bindings
  - Intent already exists, just add to `keymap/bindings_data.cljc`
  - See: `REMAINING_EDITING_GAPS.md` section 2

- [ ] **Ctrl+P/N Navigation** (2 min)
  - Add Emacs-style arrow aliases
  - See: `REMAINING_EDITING_GAPS.md` section 7

**Why:** These are trivial to implement and have high user impact.

---

### Phase 2: Autocomplete (3-4 days)
**Goal:** Implement the three core autocomplete systems

- [ ] **Page Reference Autocomplete `[[`**
  - Trigger detection
  - Fuzzy search across pages
  - Popup UI with navigation
  - See: `AUTOCOMPLETE_SPEC.md` section 1

- [ ] **Block Reference Autocomplete `((`**
  - Trigger detection
  - Full-text block search
  - Preview UI
  - See: `AUTOCOMPLETE_SPEC.md` section 2

- [ ] **Slash Commands `/`**
  - Trigger detection (start of line or after space)
  - Command registry (TODO, DOING, bold, italic, query, embed, etc.)
  - Command menu UI with icons
  - See: `AUTOCOMPLETE_SPEC.md` section 3

**Why:** This is the most visible missing feature. Users immediately notice no `[[` or `/` autocomplete.

---

### Phase 3: Word Navigation & Kill Commands (1-2 days)
**Goal:** Add Emacs-style text editing shortcuts

- [ ] **Word Boundary Detection**
  - Create `src/utils/text.cljc`
  - `find-next-word-boundary`, `find-prev-word-boundary`
  - See: `WORD_NAVIGATION_AND_KILL_COMMANDS_SPEC.md` Step 1

- [ ] **Word Navigation Intents**
  - `:move-cursor-forward-word`
  - `:move-cursor-backward-word`
  - Bindings: `Alt+F/B`, `Ctrl+Shift+F/B` (Mac)
  - See: `WORD_NAVIGATION_AND_KILL_COMMANDS_SPEC.md` Step 2

- [ ] **Kill Command Intents**
  - `:clear-block-content`, `:kill-to-beginning`, `:kill-to-end`
  - `:kill-word-forward`, `:kill-word-backward`
  - All copy to clipboard
  - See: `WORD_NAVIGATION_AND_KILL_COMMANDS_SPEC.md` Step 3

**Why:** Power users (especially Emacs users) will miss these immediately.

---

### Phase 4: Additional Features (2-3 days)
**Goal:** Fill remaining gaps for 100% parity

- [ ] **Follow Link Under Cursor** (`Cmd+O`)
  - Detect page/block ref at cursor
  - Navigate to page or scroll to block
  - See: `REMAINING_EDITING_GAPS.md` section 1

- [ ] **Select Parent** (`Cmd+A` while editing)
  - Exit edit mode, select parent block
  - See: `REMAINING_EDITING_GAPS.md` section 3

- [ ] **Select All Blocks** (`Cmd+Shift+A`)
  - Select all blocks in current view
  - See: `REMAINING_EDITING_GAPS.md` section 3

- [ ] **Copy as Plain Text** (`Cmd+Shift+C`)
  - Strip markdown formatting before copying
  - See: `REMAINING_EDITING_GAPS.md` section 4

- [ ] **Paste as Single Block** (`Cmd+Shift+V`)
  - Don't split on newlines
  - See: `REMAINING_EDITING_GAPS.md` section 4

- [ ] **Toggle Numbered List** (`t n`)
  - Toggle `1. ` prefix on/off
  - See: `REMAINING_EDITING_GAPS.md` section 8

**Optional (Lower Priority):**
- [ ] Format selection as link (`Cmd+L`) - requires prompt UI
- [ ] Replace block ref with content (`Cmd+Shift+R`)
- [ ] Copy block as embed (`Cmd+Shift+E`)

**Why:** These complete the feature set for 100% parity.

---

## 🧪 Testing Approach

Each spec includes:

### Unit Tests (95% of testing)
- Pure function tests
- Intent → ops verification
- Fast, deterministic
- Run on every commit: `bb test`

### Browser E2E Tests (5% of testing)
- Playwright tests for critical paths
- Cross-browser compatibility
- Run before release: `npx playwright test`

### Manual Testing
- 5-minute smoke tests
- Checklists in each spec
- Run after implementation: `bb dev`

---

## 📊 Current Status

| Feature | Status | Priority | Effort |
|---------|--------|----------|--------|
| Context detection | ✅ Done | - | - |
| Paired characters | ✅ Done | - | - |
| Smart Enter | ✅ Done | - | - |
| Arrow navigation | ✅ Done | - | - |
| **Shift+Arrow text selection** | ❌ Todo | ⚡ Critical | 15 min |
| **Highlight/Strikethrough** | ❌ Todo | ⚡ Quick win | 5 min |
| **Page autocomplete [[** | ❌ Todo | ⚡ Critical | 1-2 days |
| **Block autocomplete ((** | ❌ Todo | ⚡ Critical | 1 day |
| **Slash commands /** | ❌ Todo | ⚡ Critical | 1-2 days |
| **Word navigation** | ❌ Todo | High | 1 day |
| **Kill commands** | ❌ Todo | High | 1 day |
| **Follow link** | ❌ Todo | Medium | 1 day |
| **Select parent/all** | ❌ Todo | Medium | 0.5 day |
| **Copy/paste variants** | ❌ Todo | Low | 0.5 day |

**Estimated Total Effort:** 8-10 days of focused work

**Feature Parity After Completion:** 99% (only sidebar and advanced block ref ops missing)

---

## 🚀 Getting Started

### To Implement a Feature:

1. **Read the spec** - Understand expected behavior
2. **Check files to modify** - Each spec lists exact files
3. **Write unit tests first** - Follow test examples in spec
4. **Implement the feature** - Use code templates from spec
5. **Run tests** - `bb test && npx playwright test`
6. **Manual verification** - Use checklist in spec

### To Debug:

All specs include debug tips. In browser:

```javascript
// Check state
DEBUG.summary()
DEBUG.selection()
DEBUG.editing()

// Test intents
DEBUG.dispatch({type: 'your-intent', ...})

// Verify integrity
DEBUG.checkIntegrity()
```

---

## 📝 Notes

- **Architecture:** All specs follow Evo's intent-based architecture
- **No Breaking Changes:** All additions, no modifications to existing code
- **Backward Compatible:** Existing shortcuts and behaviors unchanged
- **Testing First:** Every spec includes tests before implementation

---

## 🔗 References

**Logseq Source Analysis:**
- `src/main/frontend/handler/editor.cljs` - Main editing logic
- `src/main/frontend/modules/shortcut/config.cljs` - All shortcuts
- `src/main/frontend/util/cursor.cljs` - Cursor utilities
- `src/main/frontend/commands.cljs` - Slash commands

**Evo Implementation:**
- `src/plugins/` - All feature plugins
- `src/keymap/bindings_data.cljc` - Keyboard shortcuts
- `src/components/block.cljs` - Block editing component
