# Researcher Subagent Integration

## Overview

Integrated a **researcher subagent** that conducts deep, multi-source research and produces structured reports via the review-flow MCP.

## Architecture

```
User: "Research X"
  ↓
Main Agent (Claude Code): Invokes researcher subagent via Task tool
  ↓
Researcher Subagent (separate context):
  - Calls start_research() MCP tool → gets research_id
  - Queries multiple sources:
    * Context7 (library docs)
    * Exa (code examples)
    * ~/Projects/best/* (reference repos)
    * WebSearch (current practices)
  - Calls update_research_progress() → logs progress
  - Synthesizes findings
  - Calls save_research_report() → persists to MCP
  ↓
Main Agent: "Research complete, saved at research://reports/{id}"
  ↓
Main Agent can query research://reports to see results
```

**Key insight**: The MCP is just a **storage backend**. The researcher subagent does the actual work and USES the MCP to persist results.

## Components

### 1. MCP Server Extensions (`mcp/review/server.py`)

**Role**: Storage backend ONLY. The MCP doesn't do research - it just persists results.

**Storage Tools** (3 new):
```python
start_research(topic, focus_areas) 
→ Creates research_id, initializes directory structure
→ Like creating a new document

update_research_progress(research_id, progress_note)
→ Logs progress event to ledger
→ Like appending to a log file

save_research_report(research_id, report_content, findings_summary)
→ Persists report, updates metadata
→ Like saving a completed document
```

**Access Resources** (2 new):
```python
research://reports/{research_id}
→ Read specific report + metadata
→ Like querying a document by ID

research://reports
→ List all research reports (sorted by date)
→ Like listing all documents
```

**File Structure**:
```
research/reports/{research_id}/
├── metadata.json  # Status, topic, focus areas, timestamps
└── report.md      # Synthesized research report (markdown)
```

**Ledger Events**:
- `research_started` - When storage initialized
- `research_progress` - When progress logged
- `research_completed` - When report saved

**Important**: The MCP is a **storage API**, not a research engine. The researcher subagent does the actual work.

### 2. Subagent Configuration (`.claude/agents/researcher.md`)

**Purpose**: Deep research with synthesis across multiple sources

**Tool Access**:
- ✅ Read, Grep, Glob (local repos)
- ✅ Context7 MCP (library docs)
- ✅ Exa MCP (code examples)
- ✅ WebSearch, WebFetch (current practices)
- ✅ Review-MCP tools (start/update/save research)
- ❌ No Edit/Write (except via save_research_report)

**Workflow**:
1. Initialize: `start_research(topic, focus_areas)`
2. For each focus area:
   - Query Context7 for library docs
   - Search Exa for real-world code
   - Read ~/Projects/best/* reference repos
   - WebSearch for current best practices (2024-2025)
   - Emit progress: `update_research_progress()`
3. Synthesize findings (not just aggregate!)
4. Write structured report
5. Save: `save_research_report()`

**Output Format**:
```markdown
# Research Report: {Topic}

## Executive Summary
[Key findings + recommendation]

## Focus Area 1
### Library Documentation
### Real-World Usage
### Reference Implementation
### Current Best Practices (2024-2025)
### Tradeoffs
### Recommendation

## Cross-Cutting Insights
## Actionable Recommendations
## References
```

**Quality Standards**:
- Synthesis, not aggregation
- Cite all sources (Context7, Exa, file paths, URLs)
- Actionable project-specific guidance
- Prioritize recent sources (2024-2025)

## Usage

### Explicit Invocation
```
User: "Use the researcher subagent to investigate how re-frame handles derived subscriptions"

Researcher:
1. Calls start_research()
2. Queries Context7 for re-frame docs
3. Searches Exa for examples
4. Reads ~/Projects/best/re-frame/src/re_frame/subs.cljc
5. WebSearches "re-frame subscriptions 2024"
6. Emits progress updates
7. Synthesizes findings
8. Calls save_research_report()

Result: research/reports/{id}/report.md
```

### Query Results
```
Main agent: "Query research://reports to see completed research"
→ Lists all reports with summaries

Main agent: "Read research://reports/{id}"
→ Gets full report + metadata
```

## Benefits

### 1. Smarter Than Scripts
**Scripts** (dumb):
- Dump raw LLM output
- No cross-referencing
- No synthesis

**Researcher** (smart):
- Synthesizes across 4+ sources
- Cross-references findings
- Identifies consensus vs controversies
- Provides project-specific recommendations

### 2. Multi-Source Integration
- Context7: Up-to-date library docs
- Exa: Real-world code patterns
- ~/Projects/best/*: Proven reference implementations
- WebSearch: Current best practices (2024-2025)

### 3. Observable Progress
```
📊 Research progress: Library docs - Found 3 subscription patterns
📊 Research progress: Code examples - 12 real-world usages analyzed
📊 Research progress: Reference repos - re-frame uses signal graph
✅ Research complete: research/reports/{id}/report.md
```

### 4. Reusable Reports
- Stored in `research/reports/`
- Discoverable via `research://reports` resource
- Can reference in future work
- Team can share findings

## Comparison: Scripts vs Researcher Subagent

| Aspect | Scripts | Researcher Subagent |
|--------|---------|---------------------|
| **Output** | Raw LLM responses | Synthesized reports |
| **Sources** | 1-2 (limited by script) | 4+ (Context7, Exa, repos, web) |
| **Analysis** | None (just dump) | Cross-reference + tradeoffs |
| **Progress** | Silent black box | Real-time updates |
| **Storage** | Ad-hoc files | Structured (research://reports) |
| **Reusability** | Hard to find | Discoverable via MCP |
| **Context** | Generic | Project-specific guidance |

## Integration with Review-Flow

The researcher complements the review-flow workflow:

**Review-Flow**:
```
Idea → Proposals → Ranking → Refinement → Decision → Implementation
```

**Researcher**:
```
Question → Multi-source research → Synthesized report → Reference in proposals
```

**Example**:
1. User: "Research event sourcing patterns"
2. Researcher: Creates comprehensive report from Context7, Exa, best-of repos
3. User: "Now generate proposals for adding event sourcing to our app"
4. Review-flow: Uses research report as context for proposals

## Files Modified/Created

**MCP Server**:
- `mcp/review/server.py` (+193 lines)
  - 3 new tools: start_research, update_research_progress, save_research_report
  - 2 new resources: research://reports/{id}, research://reports
  - Ledger events for research tracking

**Subagent Config**:
- `.claude/agents/researcher.md` (new, ~300 lines)
  - Complete workflow
  - Tool restrictions
  - Quality standards
  - Example invocations

**Documentation**:
- `research/review-runs/RESEARCHER-AGENT.md` (this file)
- `research/review-runs/SUBAGENT-DESIGN.md` (earlier exploration)

## Next Steps

### Test the Researcher

Pick a simple research question to validate:
```
User: "Use the researcher subagent to investigate how datascript handles indexes"

Expected:
1. start_research() creates research_id
2. Progress updates appear
3. Report saved to research/reports/{id}/report.md
4. Can query via research://reports
```

### Iterate Based on Learnings

After first few uses:
- Adjust tool access if needed
- Refine report template
- Add domain-specific sections
- Update quality standards

### Consider Additional Subagents

Based on the pattern:
- **proposal-generator**: Generate proposals from research
- **spec-refiner**: Validate specs before implementation
- But start simple - researcher alone may be sufficient

## Design Rationale

### Why MCP-Integrated (Not Just Files)?

**Option 1: Write files directly**
- Pro: Simple
- Con: No discoverability, no events, no state tracking

**Option 2: MCP-integrated (chosen)**
- Pro: Discoverable via resources, progress events, structured state
- Con: Slightly more complex

**Decision**: MCP integration enables:
- Main agent can query completed research
- Progress updates during long research
- Structured metadata (topic, focus areas, status)
- Ledger provenance (who researched what when)

### Why Subagent (Not Main Agent)?

**Option 1: Main agent does research**
- Pro: No delegation overhead
- Con: Context bloat, harder to constrain tools, loses synthesis focus

**Option 2: Researcher subagent (chosen)**
- Pro: Fresh context, tool restrictions, specialized prompt, reusable
- Con: Requires explicit invocation

**Decision**: Subagent provides:
- Context management (no token bloat in main session)
- Tool restrictions (can't accidentally edit source during research)
- Specialized instructions (synthesis, citations, quality standards)
- Reusability (works across all projects)

## FAQ

**Q: Why not just use scripts like before?**
A: Scripts dump raw LLM output without synthesis. Researcher cross-references multiple sources, identifies tradeoffs, and provides project-specific guidance.

**Q: When should I use researcher vs just asking main agent?**
A: Use researcher when you need:
- Multi-source investigation (Context7 + Exa + repos + web)
- Synthesis across sources (not just aggregation)
- Structured report for future reference
- Deep dive (not quick answer)

**Q: Can researcher modify code?**
A: No - tool access restricted to Read/Grep/Glob. Can only write via save_research_report(). This prevents accidental edits during research phase.

**Q: How do I see research progress?**
A: Researcher emits ctx.info() progress updates via update_research_progress(). Main agent sees these in real-time.

**Q: Where are reports stored?**
A: `research/reports/{research_id}/` with metadata.json + report.md. Discoverable via `research://reports` resource.

**Q: Does this replace the review-flow MCP?**
A: No - complements it. Researcher does investigation, review-flow does proposal generation + ranking + decision. They work together.
