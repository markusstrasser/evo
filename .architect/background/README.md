# Research Spine

Numbered stages mirror the research workflow:

```
00-context/      → Canonical overview + context
10-research/     → Questions + raw LLM outputs  
20-proposals/    → Generated architectural proposals
30-evaluations/  → Rankings + decisions
40-specs-adr/    → Promoted specs + ADRs
```

## Workflow

1. **Intake**: New questions → `10-research/_inbox.md`
2. **Run**: Execute research → `10-research/YYYY-MM-DD--{slug}/`
3. **Propose**: Generate proposals → `20-proposals/YYYY-MM-DD--{slug}/`
4. **Evaluate**: Rank & decide → `30-evaluations/YYYY-MM-DD--{slug}/`
5. **Seal**: Promote decision → `40-specs-adr/ADR-NNN-{title}.md`

## Migration

Old locations have tombstone READMEs pointing here:
- `docs/adr/` → `40-specs-adr/`
- `research/results/` → `10-research/`
- `research/proposals/` → `20-proposals/` + `30-evaluations/`
