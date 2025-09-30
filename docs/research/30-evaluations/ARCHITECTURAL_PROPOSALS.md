# Architectural Proposal Workflow

Automated workflow for gathering architectural improvement proposals from multiple LLMs and ranking them.

## Overview

This workflow:
1. Generates **15 architectural proposals** (5 questions × 3 providers: Gemini, Codex, Grok)
2. Uses the **project overview** as context (not source code - less implementation bias)
3. Generates **2 rankings** from Codex and Grok evaluating all proposals
4. Saves everything organized by timestamp

## Why This Approach?

**Context without Implementation Bias**
- Uses high-level architectural overview instead of source code
- LLMs focus on concepts and patterns, not specific implementation details
- Less likely to suggest micro-optimizations vs architectural improvements

**Diverse Perspectives**
- 5 different architectural questions covering different system areas
- 3 different LLM providers with different "tastes"
- 15 unique proposals ensure broad exploration

**Quality Ranking**
- Codex (high reasoning) for detailed technical evaluation
- Grok for alternative perspective
- Rankings use project context to evaluate fitness

## Quick Start

```bash
# Run the full workflow (15 proposals + 2 rankings)
scripts/architectural-proposals

# Or specify output directory
scripts/architectural-proposals research/proposals/my-experiment

# Results saved to: research/proposals/YYYY-MM-DD-HH-MM/
```

## Architectural Questions

Five questions target different system aspects:

### 1. Core Kernel (`kernel`)
**Focus:** 3-operation kernel (create, place, update)
**Question:** How would you simplify or abstract the core operations?

### 2. Derived Indexes (`indexes`)
**Focus:** Computed views (parent-of, pre/post, siblings)
**Question:** How would you decouple or redesign the derived index architecture?

### 3. Transaction Pipeline (`pipeline`)
**Focus:** NORMALIZE → VALIDATE → APPLY → DERIVE
**Question:** How would you improve or rethink this pipeline architecture?

### 4. Extensibility (`extensibility`)
**Focus:** Intent compilation, plugin system
**Question:** How would you redesign the extensibility and composition model?

### 5. Developer Experience (`dx`)
**Focus:** REPL, tooling, debugging
**Question:** How would you improve developer experience and tooling?

## Ranking Criteria

Proposals are ranked on:

### Primary (Must Have)
1. **Simplicity** - Reduces conceptual complexity
2. **Readability** - Human and LLM can understand quickly
3. **Debuggability** - Observable, clear errors, REPL-friendly
4. **Expressiveness** - Enables powerful composition, low LoC

### Secondary (Nice to Have)
5. **Novel Concepts** - New ideas if they unlock better expressiveness
6. **DX** - Works well with REPL, good for AI assistants

### What to Ignore
- **Performance** - Unless fundamentally impossible to fix later
- **Feature Completeness** - Partial solutions OK if pattern is clear
- **Backwards Compatibility** - Greenfield evaluation

## Output Structure

```
research/proposals/YYYY-MM-DD-HH-MM/
├── proposals/
│   ├── gemini-kernel.md
│   ├── gemini-indexes.md
│   ├── gemini-pipeline.md
│   ├── gemini-extensibility.md
│   ├── gemini-dx.md
│   ├── codex-kernel.md
│   ├── codex-indexes.md
│   ├── codex-pipeline.md
│   ├── codex-extensibility.md
│   ├── codex-dx.md
│   ├── grok-kernel.md
│   ├── grok-indexes.md
│   ├── grok-pipeline.md
│   ├── grok-extensibility.md
│   └── grok-dx.md
└── rankings/
    ├── codex-ranking.md
    └── grok-ranking.md
```

## Workflow Details

### Phase 1: Proposal Generation (Parallel)

For each of 5 questions:
- **Gemini**: Fast, broad thinking
- **Codex**: Deep reasoning (`model_reasoning_effort=high`)
- **Grok**: Alternative perspective

Each provider receives:
```
<context>
PROJECT OVERVIEW: [full overview.md]
</context>

<question>
ARCHITECTURAL QUESTION: [specific question]

Provide:
1. Core idea
2. Key benefits (simplicity, readability, debuggability, expressiveness)
3. Implementation sketch
4. Tradeoffs and risks
5. DX improvements (REPL, debugging, testing)
</question>
```

### Phase 2: Ranking (Sequential)

Codex and Grok each receive:
- Full project overview
- All 15 proposals (Codex output cleaned of reasoning preamble)
- Ranking instructions with criteria

Output format:
```markdown
# Rankings

## Tier S (9-10): Exceptional
[Proposal details with score, reasoning, tradeoffs]

## Tier A (7-8): Strong
[...]

## Summary
Top 3 Recommendations
Common Themes
Red Flags
```

## Example Usage

### Generate Proposals
```bash
# Full workflow
scripts/architectural-proposals

# Watch progress
scripts/architectural-proposals 2>&1 | tee proposal-run.log
```

### Review Results
```bash
# Latest run
LATEST=$(ls -td research/proposals/* | head -1)

# Read rankings
bat $LATEST/rankings/codex-ranking.md
bat $LATEST/rankings/grok-ranking.md

# Compare rankings
diff $LATEST/rankings/codex-ranking.md $LATEST/rankings/grok-ranking.md

# Read specific proposals
bat $LATEST/proposals/codex-kernel.md
bat $LATEST/proposals/gemini-dx.md

# Read all proposals from one provider
bat $LATEST/proposals/codex-*.md
```

### Extract Insights
```bash
# Find top-rated proposals across both rankings
grep -h "Score: [89]" $LATEST/rankings/*.md

# Extract all "Core Insight" sections
grep -A 1 "Core Insight:" $LATEST/rankings/*.md

# Find common themes
grep -A 5 "Common Themes:" $LATEST/rankings/*.md
```

## Customization

### Add New Questions

Edit `research/architectural-questions.edn`:

```clojure
{:questions
 [
  ;; ... existing questions ...

  {:id :new-area
   :focus "New Focus Area"
   :prompt "Your detailed question here..."}
 ]}
```

Then update `scripts/architectural-proposals` to include the new question.

### Change Providers

Edit `scripts/architectural-proposals`:

```bash
# Use different providers
providers=("gemini" "codex" "claude")  # if you add Claude CLI

# Use different models
codex -m gpt-4-turbo ...  # instead of gpt-5-codex
```

### Adjust Ranking Prompt

Edit `research/proposal-ranker-prompt.md` to change:
- Ranking criteria
- Output format
- Evaluation methodology

## Tips

### For Best Results

1. **Keep overview current** - Regenerate `docs/overview.md` before running
2. **Run during off-peak** - 15 parallel API calls can take 2-5 minutes
3. **Check API keys** - Set `XAI_API_KEY` for Grok
4. **Read both rankings** - Codex and Grok often highlight different aspects

### Common Issues

**Empty Proposals**
- Check API key env vars
- Verify CLI tools are installed and working
- Look for errors in proposal files

**Codex Output Too Large**
- Script automatically skips first 1430 lines (reasoning)
- If still too large, increase offset in ranking collection

**Duplicate Rankings**
- Each ranker sees identical input, so very similar rankings are expected
- Differences highlight which aspects each model prioritizes

## Integration with REPL

```clojure
;; dev/repl.clj

(defn review-proposals
  "Open proposals in browser or viewer"
  [timestamp]
  (let [dir (str "research/proposals/" timestamp)]
    (shell/sh "open" (str dir "/rankings/codex-ranking.md"))
    (shell/sh "open" (str dir "/rankings/grok-ranking.md"))))

(defn latest-proposals
  "Get path to latest proposal run"
  []
  (->> (file-seq (io/file "research/proposals"))
       (filter #(.isDirectory %))
       (map #(.getName %))
       (filter #(re-matches #"\d{4}-\d{2}-\d{2}-\d{2}-\d{2}" %))
       sort
       last))
```

## See Also

- `research/CLI_REFERENCE.md` - LLM CLI tool reference
- `research/architectural-questions.edn` - Question definitions
- `research/proposal-ranker-prompt.md` - Ranking instructions
- `scripts/grok` - Grok API wrapper
- `.agentlog/session-*.md` - Past session learnings

## Design Philosophy

This workflow embodies the project's values:

- **Data-Driven**: Proposals are data, rankings are derived views
- **Composable**: Questions, providers, and rankers are pluggable
- **Observable**: All intermediate artifacts saved for inspection
- **Debuggable**: Clear structure, readable output, easy to trace
- **Expressive**: Single command generates comprehensive architectural review

The workflow itself is a tree:
```
Root: Question Set
├─ Question 1 → [Gemini, Codex, Grok] → Proposals
├─ Question 2 → [Gemini, Codex, Grok] → Proposals
├─ Question 3 → [Gemini, Codex, Grok] → Proposals
├─ Question 4 → [Gemini, Codex, Grok] → Proposals
└─ Question 5 → [Gemini, Codex, Grok] → Proposals
                                       ↓
                      All Proposals → [Codex, Grok] → Rankings
```

Canonical state: Questions + Overview
Derived views: Proposals, Rankings
