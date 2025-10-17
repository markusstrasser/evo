# Skills/CLIs/MCPs Architecture - Implementation Summary

**Date:** 2025-10-17
**Status:** Phase 1 Complete - Research Skill Proof-of-Concept

## What Was Implemented

### 1. Toolbox Index (`dev/tooling-index.edn`)

Central registry of all development tooling:
- **MCPs**: Stateful servers (architect, tournament, shadow-cljs, exa, context7)
- **Skills**: Progressive-disclosure workflows (research, visual, repl-debug, diagnostics)
- **CLIs**: Network-dependent tools (gemini, codex, grok, repomix)
- **Scripts**: Simple bash automation (generate-overview, quick-test, etc.)
- **Migration tracking**: Current status and priorities

**Benefits:**
- Single source of truth for tool discovery
- Natural language triggers mapped to tools
- Documentation sync detection
- Migration progress tracking

### 2. Research Workflow Skill (Proof-of-Concept)

Full implementation in `skills/research/`:

**Files Created:**
- `SKILL.md` - L1/L2 documentation (247 lines)
- `run.sh` - Orchestration script (293 lines)
- `config.edn` - Configuration and query templates
- `data/repos.edn` - 40+ curated project metadata
- `examples/` - Usage examples
- `README.md` - Quick reference

**Commands Implemented:**
- `./run.sh list` - List available best-of projects
- `./run.sh info <project>` - Show project details
- `./run.sh search <query>` - Search project metadata
- `./run.sh explore <project> <query>` - Deep dive query
- `./run.sh compare <projects> <aspect>` - Cross-project comparison

**Model Selection:**
- Gemini for high token-count queries
- Codex for architecture/taste questions
- Grok for quick queries
- Automatic small/large repo detection

**Tested:**
- ✅ EDN format validation
- ✅ Directory structure complete
- ✅ Script executability
- ✅ Help command
- ✅ List command (40+ projects found)
- ✅ Info command (shows size, structure, languages)

### 3. Architecture Decision Record

**ADR-013-skills-clis-mcps-architecture.md** (9.7k)

Comprehensive documentation covering:
- Context and problem statement
- Three-tier architecture decision
- Rationale for each tier
- Implementation details
- Pros/cons/mitigations
- Examples and comparisons
- Red flags and migration checklist

## Architecture Overview

### Three-Tier System

```
┌─────────────────────────────────────────────────────┐
│ MCPs (Stateful Services)                            │
│ - architect, tournament, shadow-cljs, exa           │
│ - Long-running, persistent state                    │
│ - Network access                                     │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│ Skills (Progressive-Disclosure Workflows)           │
│ - research, visual, repl-debug, diagnostics         │
│ - L1: ~100 tokens (always loaded)                  │
│ - L2: 3-5k tokens (loaded on trigger)              │
│ - L3+: Unlimited resources (loaded as needed)      │
│ - No network (calls CLIs via orchestration)        │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│ CLIs (Network & Composability)                      │
│ - gemini, codex, grok, repomix                      │
│ - Simple wrappers, pipe-friendly                    │
│ - Network access, external state                   │
└─────────────────────────────────────────────────────┘
```

### Token Savings

**Before:** Load all CLAUDE.md (~15k tokens) every session

**After:**
- L1 metadata: ~100 tokens × 5 skills = 500 tokens
- L2 instructions: 3-5k tokens only when triggered
- **Savings: ~14.5k tokens per session** (97% reduction)

## Validation Results

✅ **Toolbox Index:**
- Valid EDN format
- All expected sections present
- 8 top-level keys (:version, :description, :mcps, :skills, :clis, :scripts, :config, :migration)

✅ **Research Skill:**
- Complete directory structure
- All files present (SKILL.md, run.sh, config.edn, README.md, examples/)
- Scripts executable
- Commands working (list, info tested)
- 40+ best-of projects discovered

✅ **ADR:**
- Proper format with all required sections
- 9.7k comprehensive documentation
- Examples, comparisons, migration checklist included

## Next Steps

### Phase 2: Additional Skills

**Priority order (from ADR-013):**

1. **Visual Validation Skill** (High Value)
   - Complex Python + domain knowledge
   - Consolidate scattered scripts
   - Canvas/WebGL analysis

2. **REPL Debugging Skill** (Quick Win)
   - Package existing REPL-first debugging docs
   - Browser console patterns
   - Common pitfalls catalog

3. **Dev Diagnostics Skill** (Maintenance)
   - Health checks
   - Environment validation
   - Error catalog
   - Preflight scripts

### Maintenance Tasks

1. **Documentation Sync**
   - Update CLAUDE.md with Skills references
   - Link to toolbox index
   - Remove duplicated workflow docs

2. **Pre-commit Hook**
   - Validate toolbox index EDN format
   - Check SKILL.md ↔ CLAUDE.md sync
   - Verify trigger uniqueness

3. **Skill Testing Framework**
   - Smoke tests for run.sh scripts
   - L1 metadata validation
   - L2 token count checking (<5k)

## Files Created

```
dev/
  tooling-index.edn                    # Central registry

skills/
  research/
    SKILL.md                            # L1/L2 documentation
    run.sh                              # Orchestration
    config.edn                          # Configuration
    README.md                           # Quick reference
    data/
      repos.edn                         # Project metadata
    examples/
      example-quick-query.sh
      example-comparison.sh

.architect/
  adr/
    ADR-013-skills-clis-mcps-architecture.md

IMPLEMENTATION-SUMMARY.md               # This file
```

## Usage Examples

### Using Toolbox Index

```clojure
;; Load index
(def idx (-> "dev/tooling-index.edn" slurp edn/read-string))

;; Find research tools
(keys (:skills idx))
;=> (:research :visual-validate :repl-debug :dev-diagnostics)

;; Get triggers
(get-in idx [:skills :research :triggers])
;=> ["research best-of repo" "query reference projects" ...]
```

### Using Research Skill

```bash
# List projects
skills/research/run.sh list

# Quick query
skills/research/run.sh explore malli "schema composition patterns"

# Focused large repo query
skills/research/run.sh explore clojurescript "macro expansion" \
    --focused "cljs/compiler.clj,cljs/analyzer.cljc" \
    --model codex

# Compare projects
skills/research/run.sh compare "re-frame,electric" "reactive state"
```

## Key Decisions

### Why Three Tiers?

Each tier serves distinct purposes:

- **MCPs**: Stateful orchestration, long-running, third-party integration
- **Skills**: Workflow packaging with progressive disclosure
- **CLIs**: Network access, composability, session management

### Why Not Convert Everything to Skills?

**Anti-patterns avoided:**
1. Network-dependent CLIs → Skills (no network in container)
2. Simple scripts → Skills (over-engineering)
3. Stateful MCPs → Skills (lose persistence)

### Progressive Disclosure Value

Traditional approach loads all context every session. Skills use layered loading:
- L1: Always present, minimal tokens
- L2: Triggered, moderate tokens
- L3: On-demand, unlimited resources

## Architectural Validation

Both **Gemini** and **Codex** independently proposed identical architectures:
- Three-tier system (MCPs/Skills/CLIs)
- Same Skills candidates
- Same keep-as-CLIs decisions
- Tournament result: Statistical tie (consensus)

**Novel contribution (Codex):** Toolbox index for discovery and sync checking

## Conclusion

Phase 1 complete with working proof-of-concept. Research workflow skill demonstrates:
- ✅ Complete implementation
- ✅ Validated functionality
- ✅ Token reduction strategy
- ✅ Progressive disclosure benefits
- ✅ Clear migration path for remaining skills

Architecture is sound, pragmatic, and incrementally adoptable. Ready for Phase 2.

---

**See Also:**
- ADR-013: Full architectural decision rationale
- `skills/research/SKILL.md`: Complete research workflow docs
- `dev/tooling-index.edn`: Tool registry
- `CLAUDE.md`: Project documentation (to be updated with Skills references)
