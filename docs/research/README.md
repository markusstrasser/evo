# Research Spine

Lean five-stage research workflow mirroring solo investigation cycles.

## Structure

```
docs/research/
├── 00-context/          # Canonical overviews, external insights, state of world
├── 10-research/         # Questions, CLI refs, timestamped runs with outputs
├── 20-proposals/        # Generated architectural proposal bundles
├── 30-evaluations/      # Rankings, re-rankings, RFC drafts, decisions
├── 40-adr/              # Promoted Architecture Decision Records
└── 40-specs/            # Promoted specifications and design docs
```

## Workflow

1. **Intake**: New questions → `10-research/_inbox.md`
2. **Research Run**: Create `10-research/YYYY-MM-DD--{topic}-{model}/`
   - Contains: `metadata.json`, `question.md`, `outputs/`, `SUMMARY.md`
3. **Proposals**: Generated bundles → `20-proposals/YYYY-MM-DD--{topic}/`
4. **Evaluation**: Rankings, decisions → `30-evaluations/YYYY-MM-DD--{topic}/`
   - Track status in `status.md`: `researching | proposing | evaluating | sealed`
5. **Finalize**: Accepted outcomes → `40-adr/` or `40-specs/`

## Naming Convention

**Research slugs**: `YYYY-MM-DD--{topic}-{model-name}`

Examples:
- `2025-09-30--best-repos-battle-test-gemini`
- `2025-09-30--derived-index-refresh-codex`
- `2025-09-30--architectural-proposals-run1`

## Migration Notes

Migrated from:
- `docs/adr/` → `40-adr/` (with ADR- prefix)
- `docs/specs/` → `40-specs/`
- `docs/reasoning/` → `00-context/`
- `research/results/` → `10-research/`
- `research/proposals/` → `20-proposals/` + `30-evaluations/`
