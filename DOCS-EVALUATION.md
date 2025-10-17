# Root Documentation Evaluation (2025-10-17)

## Context

Post-skills-migration cleanup: evaluating all root markdown files for necessity, duplication, and clarity.

## Evaluation Criteria

- **KEEP**: Essential, no duplication, clear purpose
- **DELETE**: Stale, duplicate, unnecessary
- **RENAME/REFACTOR**: Good content, wrong location or naming
- **UPDATE**: Keep but needs revision

---

## Files Evaluated

### CLAUDE.md ✅ KEEP
**Status**: Primary agent instructions file
**Content**: Skills overview, LLM CLIs, MCPs, dev tooling, quick reference
**Recent updates**:
- Added Skills Index table (inline, no external registry)
- Updated research workflow for llmx
- Added migration notes (2025-10-17)
**Recommendation**: Keep - this is the authoritative agent context file

---

### AGENTS.md ✅ KEEP (Symlink)
**Status**: Symlink to CLAUDE.md
**Purpose**: Alias for discoverability
**Note**: CLAUDE.md footer says "Note: AGENTS.md is a symlink to CLAUDE.md - edit CLAUDE.md only"
**Recommendation**: Keep - intentional alias, low cost

---

### README.md ⚠️ RENAME/REFACTOR
**Status**: Contains project vision/philosophy, NOT typical project README
**Content**:
- MOTTO: "Build → Learn → Extract → Generalize"
- "UI as IR" concept (MLIR metaphor)
- Meta-system vision
- Loose architectural ideas
**Issue**: File named README.md suggests project documentation, but contains personal notes/vision
**Recommendation**:
- **Option A**: Rename to `VISION.md` or `PHILOSOPHY.md`
- **Option B**: Create proper README.md with project info, rename this to `NOTES.md`
- **Option C**: Move to `docs/vision.md` or `docs/notes/`

---

### STYLE.md ✅ KEEP
**Status**: Clojure refactoring patterns with real examples
**Content**:
- 23 patterns (flattening, composition, etc.)
- Before/after code examples
- Living document for code review
**Value**: High - referenced during refactoring, concrete examples
**Recommendation**: Keep - this is valuable living documentation

---

### TODO-FOR-HUMAN.md ❌ DELETE
**Status**: 4 unchecked items, no context/dates/priority
**Content**:
- Vague items about ADR/RFC/DSL
- "Consider MCP for ..."
- No dates, no context, no priority
**Issue**: Unclear what's actionable vs. shower thoughts
**Recommendation**: DELETE - if these were important, they'd be in actual TODO system or issues
**Alternative**: If user wants to keep, expand with context/dates/priority

---

### SKILLS-MIGRATION-SUMMARY.md ✅ KEEP (Historical Record)
**Status**: Migration documentation (2025-10-17)
**Content**:
- Skills migration details (285 lines removed, 5 files deleted)
- Before/after architecture
- Testing notes
**Value**: Historical record of major refactoring
**Recommendation**: Keep - useful for understanding migration decisions
**Note**: Could move to `docs/migration/` or `docs/decisions/` if we want to keep root clean

---

## Summary

### Counts
- **Total files**: 6 (excluding symlink)
- **Keep as-is**: 3 (CLAUDE.md, STYLE.md, SKILLS-MIGRATION-SUMMARY.md)
- **Keep (symlink)**: 1 (AGENTS.md)
- **Rename/Refactor**: 1 (README.md)
- **Delete**: 1 (TODO-FOR-HUMAN.md)

### Recommendations

**Immediate Actions**:
1. **DELETE** `TODO-FOR-HUMAN.md` - no clear value
2. **RENAME** `README.md` → `VISION.md` or `NOTES.md` (or move to docs/)
3. **CREATE** proper `README.md` with:
   - Project name and description
   - Quick start (dev server, REPL)
   - Project structure overview
   - Links to CLAUDE.md for agent instructions
   - Links to key documentation

**Optional Actions**:
- Move `SKILLS-MIGRATION-SUMMARY.md` to `docs/decisions/` or `docs/migration/`
- Consider if root should only have: README.md, CLAUDE.md, STYLE.md (all others in docs/)

---

## No Duplication Found

**Key finding**: After deleting `dev/tooling-index.edn`, there are no duplicate registries or redundant metadata files.

- Skills info: Now in SKILL.md YAML frontmatter + CLAUDE.md Skills Index table
- Configuration: Now in SKILL.md markdown tables (no config.edn)
- Agent context: Centralized in CLAUDE.md

✅ Single source of truth achieved

---

## Files That Don't Exist (Previously Mentioned)

- `DEV_TOOLING_GUIDE.md` - Does not exist at root
- `PROJECT_NAVIGATION_GUIDE.md` - Does not exist at root
- `IMPLEMENTATION-SUMMARY.md` - Does not exist at root

These may have been deleted in previous cleanup or never existed at root level.
