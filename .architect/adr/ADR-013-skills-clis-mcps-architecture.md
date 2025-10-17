# ADR 013: Agent Skills vs CLIs vs MCPs - Development Tooling Architecture

## Status
Accepted

## Context

We have development tooling spread across multiple abstraction layers:
- **MCPs**: Stateful servers (architect, tournament, shadow-cljs, exa)
- **CLIs**: LLM provider wrappers (gemini, codex, grok) + utilities (repomix)
- **Scripts**: Bash automation in `scripts/`
- **Documentation**: Complex workflows in `CLAUDE.md` repeatedly loaded into context

**New capability**: Claude Code Agent Skills - filesystem-based progressive-disclosure resources with:
- L1 (Metadata): ~100 tokens, always loaded for discovery
- L2 (Instructions): <5k tokens, loaded when triggered
- L3+ (Resources): Unlimited, loaded as needed
- Execution container with filesystem/bash but **no network access**

**Problem**: How should we refactor tooling to optimally leverage Skills while maintaining composability and avoiding over-engineering?

## Decision

Adopt a **three-tier architecture** with clear boundaries:

1. **MCPs** - Keep stateful, long-running services
2. **Skills** - Convert complex workflows to progressive-disclosure resources
3. **CLIs** - Retain for network operations and composability

Add **Toolbox Index** (`dev/tooling-index.edn`) for discovery and documentation sync.

## Rationale

### Why Three Tiers?

Each tier serves distinct purposes:

**MCPs: Stateful Orchestration**
- Multi-step workflows with persistent state (`.architect/`, sessions)
- Long-running services (REPL connections, watching)
- Third-party integrations requiring network

Keep: `architect-mcp`, `tournament-mcp`, `mcp-shadow-dual`, `exa`

**Skills: Workflow Packaging**
- Complex procedures that agents "re-learn" every session
- Domain knowledge + execution scripts
- Progressive disclosure reduces context bloat

Create: Research workflow, visual validation, REPL debugging, dev diagnostics

**CLIs: Composability & Network**
- Simple wrappers for network APIs
- Pipe/automation flexibility
- Session management (external state)

Keep: `gemini`, `codex`, `grok`, `repomix`

### Why Not Skills for Everything?

**Anti-patterns identified:**
1. **Network-dependent CLIs → Skills**: Would fail silently (no network in container)
2. **Simple scripts → Skills**: Over-engineering one-liners
3. **Stateful MCPs → Skills**: Lose persistence, orchestration capability

### Progressive Disclosure Value

Current approach: Load all of `CLAUDE.md` (~15k tokens) every session

With Skills:
- L1 always loaded: ~100 tokens × 5 skills = 500 tokens
- L2 loaded on trigger: 3-5k tokens only when needed
- L3 scripts: Zero tokens until executed

**Savings**: ~14.5k tokens per session, larger when workflows aren't needed

### Toolbox Index: Discovery + Sync

`dev/tooling-index.edn` provides:
- **Discovery**: "What tools are available?" → Query index
- **Triggers**: Map natural language → tool invocation
- **Documentation sync**: Detect drift between SKILL.md and CLAUDE.md
- **Migration tracking**: Status of Skills implementation

Example:
```clojure
{:research
 {:type :skill
  :path "skills/research/"
  :triggers ["research best-of repo" "query reference"]
  :docs-ref "CLAUDE.md#research-workflow"}}
```

## Implementation

### Skill Structure (Standard)

```
skills/<name>/
  SKILL.md          # L1 metadata + L2 instructions (single source of truth)
  run.sh            # L3 executable orchestration
  config.edn        # Skill configuration (models, thresholds, etc.)
  data/             # Bundled resources (repos.edn, templates, etc.)
  examples/         # Usage examples
  README.md         # Quick reference
```

### Toolbox Index Schema

```clojure
{:version "1.0.0"
 :mcps {...}       ; Stateful services
 :skills {...}     ; Progressive-disclosure workflows
 :clis {...}       ; Network CLIs and utilities
 :scripts {...}    ; Simple bash automation
 :config {...}     ; API keys, paths, etc.
 :migration {...}} ; Implementation status
```

### Migration Priority (80/20 Rule)

**Phase 1: High Value**
1. Research workflow - Most-used, most complex (DONE)
2. Visual validation - Complex Python + domain knowledge

**Phase 2: Quick Wins**
3. REPL debugging - Package existing docs as guidance
4. Dev diagnostics - Consolidate scattered utilities

**Phase 3: Maintenance**
5. Update toolbox index as tools added/changed
6. Sync SKILL.md ↔ CLAUDE.md

## Consequences

### Positive

1. **Reduced Repetitive Context**
   - Skills L1/L2 replaces full CLAUDE.md sections
   - ~14k token savings per session
   - Only load what's needed when triggered

2. **Improved Discoverability**
   - Toolbox index shows all available tools
   - Natural language triggers for tool invocation
   - New agents onboard quickly

3. **Modular, Reusable Workflows**
   - Skills are self-contained units
   - Versioned, tested, documented
   - Can be shared across projects

4. **Clear Boundaries**
   - MCPs: Stateful
   - Skills: Workflows
   - CLIs: Network/composability
   - No ambiguity about where tools belong

5. **Incremental Adoption**
   - No big-bang rewrite
   - Build Skills one at a time
   - Existing tools continue working

### Negative

1. **Initial Conversion Overhead**
   - Extract workflows from CLAUDE.md
   - Write SKILL.md, orchestration scripts
   - Create examples and tests

2. **Coordination Cost**
   - Must sync SKILL.md ↔ CLAUDE.md ↔ toolbox index
   - Risk of documentation drift
   - Requires discipline to maintain

3. **Debugging Complexity**
   - Skills orchestrate multiple CLIs via shell
   - Shell scripting can be brittle
   - Need robust error handling

4. **Granularity Decisions**
   - Where to draw Skill boundaries?
   - Avoid over-engineering simple tasks
   - Avoid overly coarse mega-Skills

### Mitigations

1. **Toolbox index validation script**
   - Check all paths exist
   - Verify triggers unique
   - Detect SKILL.md ↔ CLAUDE.md drift

2. **Skill testing framework**
   - Smoke tests for `run.sh` scripts
   - Validate L1 metadata format
   - Check L2 token count <5k

3. **Documentation standards**
   - SKILL.md is source of truth
   - CLAUDE.md references Skills via links
   - Update both or fail pre-commit

4. **Simple things stay simple**
   - Don't convert one-line scripts
   - Keep CLIs as CLIs
   - Skills only for complex workflows

## Examples

### Before: Repetitive Context

Every session loads:
```markdown
## Research Workflow

**Path**: `~/Projects/best/{projectname}`

**1. Explore structure first (for large repos):**
[400 lines of detailed instructions...]
```

**Cost**: 3-5k tokens every session, whether used or not

### After: Progressive Disclosure

Session start loads L1 only:
```markdown
**research**: Query best-of repos for patterns
```

**Cost**: ~20 tokens

When triggered, load L2:
```markdown
# Research Workflow
[Detailed 3k token instructions]
```

**Cost**: 3k tokens only when used

### Skill Invocation

Agent sees trigger:
```
User: "How do ClojureScript projects handle reactive state?"
Agent: *sees "reactive state" → matches skill trigger "research"*
Agent: *loads SKILL.md L2*
Agent: *executes skills/research/run.sh compare "re-frame,electric" "reactive state"*
```

### Toolbox Query

```clojure
;; What research tools do I have?
(->> (read-edn "dev/tooling-index.edn")
     :skills
     (filter #(str/includes? (str %) "research"))
     keys)
;=> (:research)

;; What are the triggers?
(-> (read-edn "dev/tooling-index.edn")
    :skills
    :research
    :triggers)
;=> ["research best-of repo" "query reference projects" "explore codebase patterns"]
```

## Comparison: Skills vs CLIs vs MCPs

| Aspect | Skills | CLIs | MCPs |
|--------|--------|------|------|
| **State** | Stateless | Stateless | Stateful |
| **Network** | No | Yes | Yes |
| **Context** | Progressive (L1/L2/L3) | Not managed | Not managed |
| **Complexity** | Multi-step workflows | Simple wrappers | Orchestration |
| **Discovery** | Via toolbox index | PATH/scripts/ | .mcp.json |
| **Composability** | Via run.sh | Pipes/automation | Tool invocation |
| **Best for** | Workflow packaging | Network APIs | Long-running services |

## Red Flags to Watch For

1. **Over-engineering Simple Tasks**
   - Don't create Skills for one-line scripts
   - Keep `quick-test.sh` as a script, not a Skill

2. **Network-Dependent Skills**
   - Skills can't call external APIs directly
   - Must call CLIs via `run.sh` orchestration
   - Test network failures

3. **Documentation Drift**
   - SKILL.md ↔ CLAUDE.md must stay in sync
   - Pre-commit hook to detect divergence
   - Toolbox index validation

4. **Stateful Logic in Skills**
   - Skills are stateless by design
   - Don't duplicate MCP orchestration
   - Use MCPs for coordination

5. **Giant L2 Documents**
   - Keep SKILL.md instructions <5k tokens
   - Break into sub-skills if too large
   - Progressive disclosure only works if L2 is concise

6. **CRITICAL: Wrong Models for Skills**
   - NEVER use gpt-4o, gpt-4-turbo, or gemini-flash for Skills work
   - ALWAYS use gpt-5-codex (with high reasoning) and gemini-2.5-pro
   - Skills are for important work (architecture, research, debugging)
   - Token savings from progressive disclosure >> model cost difference
   - Cheap models defeat the purpose of high-quality skill workflows

## Migration Checklist

For each workflow → Skill conversion:

- [ ] Extract from CLAUDE.md
- [ ] Create `skills/<name>/` directory
- [ ] Write SKILL.md (L1 + L2)
- [ ] Create `run.sh` orchestration
- [ ] Add `config.edn` if needed
- [ ] Bundle data resources
- [ ] Write examples
- [ ] Write README
- [ ] Update toolbox index
- [ ] Update CLAUDE.md with reference link
- [ ] Test smoke tests
- [ ] Validate L2 token count <5k

## References

- Skills implementation: `skills/research/`
- Toolbox index: `dev/tooling-index.edn`
- Project docs: `CLAUDE.md`
- Agent Skills spec: `docs/agent-skills-claude.md`
- Architectural proposals: Gemini + Codex consensus (2025-10-17)

## See Also

- ADR 000: Refs as Derived (plugin pattern)
- ADR 011: Refs as Policy (typed relationships)
- Research on progressive disclosure benefits
- Claude Code documentation on Skills
