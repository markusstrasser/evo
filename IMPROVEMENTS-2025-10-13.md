# Project Improvements - October 13, 2025

## Summary

Completed 5 cleanup and organization improvements:

1. ✅ Removed 69MB venv from git, added to .gitignore
2. ✅ Consolidated API wrapper scripts (archived duplicates)
3. ✅ Improved visual validation documentation
4. ✅ Reorganized research directories
5. ✅ Clarified dev fixture separation

## 1. Python Venv Cleanup

**Problem**: `mcp/eval/.venv/` (69MB) would have been committed to git

**Solution**:
- Added `.venv/`, `venv/`, `__pycache__/`, `*.pyc` to `.gitignore`
- Created `mcp/requirements.txt` for Python dependencies
- Updated `mcp/README.md` with setup instructions

**Result**: Cleaner repo, faster clones, no venv conflicts

---

## 2. API Wrapper Consolidation

**Problem**: Duplicate API wrappers:
- `scripts/gemini-api` (unused bash wrapper)
- `scripts/openai` (unused bash wrapper)
- Global tools: `gemini`, `codex` (from npm/bun) - actually used
- Local: `scripts/grok` (used by both Python and Clojure)

**Solution**:
- Archived unused bash scripts: `.archived-gemini-api`, `.archived-openai`
- Created `scripts/API_WRAPPERS.md` documenting the active wrappers
- Clarified usage patterns (global vs local tools)

**Result**: Less duplication, clear documentation

---

## 3. Visual Validation Scripts

**Problem**: 7 validation scripts (1857 LOC) seemed complex

**Investigation**: Found they're actually well-organized:
- **Orchestrators** (Babashka): High-level workflows
- **Bash wrappers**: Handle venv activation
- **Python scripts**: Actual validation logic

**Solution**:
- Created `scripts/VISUAL-VALIDATION-QUICKREF.md` documenting architecture
- No code changes needed - structure is intentional and clean

**Result**: Better documentation, preserved 3-layer design

---

## 4. Research Directory Organization

**Problem**: Scattered research with unclear naming:
```
research/
├── mcp-tooling-proposals/
├── papers-mcp-tooling/
└── results/
```

**Solution**: Reorganized into clearer categories:
```
research/
├── proposals/       # LLM-generated architectural proposals
├── papers/          # Paper analysis and meta-processes
├── experiments/     # Experiment results and refactoring attempts
└── README.md       # Directory guide
```

**Result**: Easier to find past research, clearer purpose

---

## 5. Dev Fixtures Clarification

**Problem**: Two fixture files seemed redundant:
- `dev/fixtures.cljc` (4.7k)
- `dev/anki_fixtures.cljc` (4.4k)

**Investigation**: They serve different purposes:
- `fixtures.cljc` - Generic kernel testing (nodes, trees, db shapes)
- `anki_fixtures.cljc` - Anki-specific scenarios (cards, reviews, undo/redo)

**Solution**:
- Added clear docstrings explaining scope and usage
- Added cross-references between the two files
- No consolidation needed - separation is intentional

**Result**: Clear separation of concerns documented

---

## Files Created/Modified

### Created
- `mcp/requirements.txt` - Python dependencies
- `scripts/API_WRAPPERS.md` - API wrapper documentation
- `scripts/VISUAL-VALIDATION-QUICKREF.md` - Visual validation quick reference
- `research/README.md` - Research directory guide
- `IMPROVEMENTS-2025-10-13.md` - This file

### Modified
- `.gitignore` - Added Python venv patterns
- `mcp/README.md` - Added Python server setup
- `dev/fixtures.cljc` - Enhanced docstring with scope/usage
- `dev/anki_fixtures.cljc` - Enhanced docstring with scope/usage

### Moved
- `scripts/gemini-api` → `scripts/.archived-gemini-api`
- `scripts/openai` → `scripts/.archived-openai`
- `research/mcp-tooling-proposals/*` → `research/proposals/`
- `research/papers-mcp-tooling/*` → `research/papers/`
- `research/results/*` → `research/experiments/`

---

## Impact

- **Repo size**: Prevented 69MB venv from being committed
- **Clarity**: Better documentation for API wrappers, visual validation, research, fixtures
- **Organization**: Clearer research directory structure
- **Maintainability**: Easier to find and understand project components

---

## Next Steps

These were all quick wins (1-2 hours total). For deeper improvements, consider:
- Implement validation logic in `mcp/review/refine()` (currently placeholder)
- Add MCP integration for automated screenshots
- Create archive policy for old experiments (see `research/README.md`)
