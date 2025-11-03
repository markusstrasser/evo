# Transclusion & References Status

Comparison of evo implementation vs Logseq functionality.

## ✅ Implemented (Working)

### Block References - `((block-id))`
**Status:** ✅ Fully working

- **Syntax:** `((block-id))`
- **Behavior:** Inline transclusion of referenced block's text content
- **Features:**
  - Cycle detection (prevents infinite recursion)
  - Missing block error handling
  - Renders inline within parent text

**Implementation:**
- Parser: `src/parser/block_refs.cljc`
- Component: `src/components/block_ref.cljs`
- Rendering: `components/block.cljs:179-193` (render-text-with-refs)

**Example:**
```
Block A: "This is the first block"
Block B: "Reference to ((block-a))"

Renders as: "Reference to This is the first block"
```

## ❌ Not Implemented

### Block Embeds - `{{embed ((block-id))}}`
**Status:** ❌ Not implemented

- **Logseq Syntax:** `{{embed ((block-id))}}` or `{{embed [[page-name]]}}`
- **Behavior in Logseq:** Full block tree render (includes children, properties, etc.)
- **Difference from `((block-id))`:**
  - Block ref: Shows only inline text
  - Block embed: Shows full block structure with children

**Note:** Logseq is deprecating `{{embed}}` in db-based graphs in favor of `/Node embed` command.

**Example (Logseq behavior):**
```
Block A (with children):
  - Parent text
    - Child 1
    - Child 2

Block B: "{{embed ((block-a))}}"

Renders full tree:
  - Parent text
    - Child 1
    - Child 2
```

### Page References - `[[page-name]]`
**Status:** ❌ Not implemented

- **Logseq Syntax:** `[[page-name]]`
- **Behavior in Logseq:**
  - Creates link to another page
  - Clicking navigates to that page
  - Creates bidirectional link (backlinks)
  - Auto-creates page if doesn't exist

**Current evo equivalent:**
- evo has typed refs system (`plugins/refs.cljc`) for programmatic links
- No markdown-style `[[page]]` syntax parsing yet
- No page/document concept (only blocks in a tree)

**Example (Logseq behavior):**
```
Page: "My Project"
  - Uses [[ClojureScript]] and [[React]]
  - See also [[Architecture Docs]]

Clicking [[ClojureScript]] navigates to ClojureScript page.
ClojureScript page shows backlink to "My Project".
```

## Architecture Notes

### Current evo Design
- **Single document tree:** All blocks are in one `:doc` tree
- **No pages:** Just nested blocks with IDs
- **Refs plugin:** Typed refs (`:link`, `:highlight`, `:citation`) in node properties
- **Block refs:** Inline transclusion via `((block-id))` parsing

### What's Needed for Full Logseq Parity

1. **Block Embeds:**
   - Parse `{{embed ((block-id))}}` syntax
   - Render full block tree recursively
   - Handle embed depth limits (prevent deeply nested embeds)

2. **Page References:**
   - Multi-document support (pages as separate trees)
   - Parse `[[page-name]]` syntax
   - Page navigation/routing
   - Backlinks system (already have partial support in refs plugin)
   - Page auto-creation

3. **Namespace Pages (Logseq feature):**
   - `[[namespace/page]]` hierarchical pages
   - Namespace browsing/navigation

## Demo Files

See the demo markdown files in this directory:
- `Projects.md` - Example project documentation
- `Tasks.md` - Example task list with block refs
- Both files use `((block-id))` syntax that currently works

To test the working features:
1. These are just markdown examples - not loaded in evo yet
2. The actual block ref syntax works in the evo browser UI
3. Try creating blocks with `((block-id))` references in the app

## References

- **Logseq source:** `~/Projects/best/logseq/src/main/frontend/components/block.cljs`
- **evo block refs:** `src/parser/block_refs.cljc`
- **evo refs plugin:** `src/plugins/refs.cljc`
- **Logseq common utils:** `~/Projects/best/logseq/src/main/logseq/common/util/block_ref.as` and `page_ref.as`
