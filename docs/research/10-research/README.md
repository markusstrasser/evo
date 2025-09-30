# 10-research: Questions & Raw Outputs

Research runs with metadata, prompts, and raw LLM outputs.

**Structure per run:**
```
10-research/YYYY-MM-DD--{slug}/
├── metadata.json       # Run config (models, prompts, env)
├── question.md         # Research question
├── outputs/            # Raw LLM responses
│   ├── gemini-*.md
│   ├── codex-*.md
│   └── grok-*.md
└── SUMMARY.md          # Quick synthesis
```

**Inbox:** `_inbox.md` - New questions land here before running
