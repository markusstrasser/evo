# Debiased LLM Evaluation System

Implementation of the evaluation system specified in `docs/research/50-specs/evaluator-oct1.md`.

## Overview

A research-backed system for fairly evaluating AI-generated architecture proposals using LLM judges, with algorithmic mitigation of known biases:

- **Position bias** - tendency to favor certain orderings
- **Verbosity bias** - tendency to prefer longer responses
- **Sycophancy** - tendency to agree without critical evaluation

## Architecture

### Core Components

**`src/eval/core.cljc`** - Pure evaluation pipeline:
- Proposal normalization (length truncation)
- Prompt generation with explicit criteria
- Position bias calibration
- Score aggregation (median, variance, confidence)
- Final ranking with tie-breaking

**`src/eval/llm.clj`** - LLM API integrations:
- Gemini, Codex, Grok provider support
- JSON response parsing (simple and nested formats)
- Shell-based CLI invocation (reuses project tools)

**`src/eval/async.clj`** - Parallel evaluation:
- core.async orchestration for concurrent rounds
- Reduces latency from 3x sequential to ~1x

**`dev/eval_repl.clj`** - REPL utilities:
- Mock data and evaluators for testing
- Quick smoke tests
- Real API test helpers

**`test/eval_core_test.clj`** - Comprehensive test suite:
- 15 tests, 58 assertions
- Unit tests for all pipeline stages
- Edge case coverage

**`scripts/test-evaluator.sh`** - End-to-end test script with real Gemini API

## Key Design Decisions

### Bias Mitigation Strategy

**1. Multiple Shuffled Rounds**
- Run evaluation 3+ times with randomized proposal order
- Averages out position effects across rounds
- Default: 3 rounds (diminishing returns beyond 5)

**2. Length Normalization**
- Truncate all proposals to 500 chars (configurable)
- Append `"... [+N chars]"` note for truncated text
- Prevents longer proposals from dominating

**3. Position Calibration**
- Apply empirically-derived weights to adjust scores by position
- Default weights: `[0.9 0.95 1.0 1.05 1.10]` for positions 1-5
- First position gets penalty, last gets boost

**4. Median Aggregation**
- Use median (not mean) across rounds for robustness to outliers
- Calculate confidence from score variance (low stddev = high confidence)

**5. Single Model, Multiple Passes**
- Avoids multi-agent sycophancy (models agreeing without critique)
- Each pass is independent and stateless
- No cascading self-refinement loops

### Why NOT Multi-Agent Debate

Research shows multi-agent debates often:
- Amplify biases through echo chambers
- Collapse to premature consensus (sycophancy)
- Perform worse than single-model with calibration

Our simpler approach: one strong judge, multiple independent passes, algorithmic debiasing.

## Usage

### Basic Evaluation

```clojure
(require '[eval.core :as eval])
(require '[eval.llm :as llm])

(def proposals
  {:design-a "Simple REST API with CRUD operations..."
   :design-b "Complex API with GraphQL, WebSocket..."
   :design-c "Moderate REST API with pagination..."})

;; Create evaluator function
(def evaluator (llm/make-evaluator :gemini))

;; Run evaluation (3 rounds by default)
(def result (eval/evaluate proposals evaluator))

;; Get ranking
(:ranking result)
;; => [:design-c :design-a :design-b]

;; Get detailed scores
(:details result)
;; => {:design-a {:median 7.5 :confidence :high :scores [7.2 7.5 7.8] ...}
;;     :design-b {:median 6.1 :confidence :high :scores [6.0 6.2 6.1] ...}
;;     :design-c {:median 8.2 :confidence :medium :scores [8.0 8.5 8.1] ...}}
```

### Configuration Options

```clojure
(eval/evaluate proposals
  {:rounds 5                     ; more rounds = more robust
   :max-length 1000              ; longer proposals allowed
   :position-weights [0.85 0.9 1.0 1.1 1.15]  ; custom calibration
   :criteria [{:name "Correctness" :weight 0.4}
              {:name "Simplicity" :weight 0.3}
              {:name "Maintainability" :weight 0.3}]}
  evaluator)
```

### Using Different LLM Providers

```clojure
;; Gemini (fast, high token limit)
(def gemini-eval (llm/make-evaluator :gemini
                   {:model "gemini-2.0-flash-exp"}))

;; Codex (reasoning-focused)
(def codex-eval (llm/make-evaluator :codex
                  {:model "gpt-5-codex"
                   :reasoning-effort "high"}))

;; Grok
(def grok-eval (llm/make-evaluator :grok
                 {:model "grok-4-latest"}))
```

### Parallel Evaluation (Faster)

```clojure
(require '[eval.async :as eval-async])

;; Runs all rounds concurrently
(def result (eval-async/evaluate-parallel proposals evaluator))
;; Takes ~1x API latency instead of 3x
```

### REPL Development

```clojure
(require '[eval-repl :as er])

;; Quick test with mock evaluator
(er/quick-eval)

;; Test individual components
(er/test-normalization)
(er/test-position-bias)
(er/test-parallel)

;; Run all local tests (no API calls)
(er/run-all-tests)

;; Test with real API
(er/test-gemini)
```

## Testing

### Unit Tests

```bash
clj -M:test --skip-meta :integration
# 109 tests, 391 assertions, 0 failures
```

### End-to-End Test (Real API)

```bash
scripts/test-evaluator.sh
# Requires GEMINI_API_KEY in .env
```

### REPL Testing

```clojure
(require '[eval-repl :as er])
(er/run-all-tests)  ; All local tests
```

## Results Interpretation

### Ranking
Ordered list of proposal IDs from best to worst by median score.

### Details Map
For each proposal:
- **`:median`** - Median score across rounds (primary ranking metric)
- **`:mean`** - Average score across rounds
- **`:stddev`** - Standard deviation (consistency measure)
- **`:confidence`** - `:high` / `:medium` / `:low` based on stddev
- **`:scores`** - Raw scores from each round

### Confidence Levels
- **High** (stddev < 0.5) - Very consistent across rounds, reliable ranking
- **Medium** (0.5 ≤ stddev < 1.0) - Some variance, generally reliable
- **Low** (stddev ≥ 1.0) - High variance, consider more rounds or tied

## Evaluation Criteria

Default criteria for API design proposals:

1. **Functional Correctness** (30%) - Satisfies requirements, correct behavior
2. **Complexity/Simplicity** (20%) - As simple as possible, low coupling
3. **Maintainability** (20%) - Clear, documented, extensible
4. **Performance & Scalability** (20%) - Efficient, scales well
5. **Consistency/Best-Practices** (10%) - Follows standards, conventions

Customizable via `:criteria` config.

## Implementation Notes

### Why Shell-Based CLI Calls?

- Reuses project's battle-tested CLI tools (`gemini`, `codex`, `scripts/grok`)
- Avoids HTTP client/auth boilerplate
- Leverages existing .env API key management
- Simpler than direct HTTP APIs

### Response Format Handling

The parser supports both:
- **Simple**: `{"1": 8, "2": 7, "3": 9}`
- **Detailed**: `{"1": {"Correctness": 8, "Simplicity": 7}, "2": {...}}`

For detailed format, automatically averages all criterion scores.

### Position Calibration Weights

Default weights `[0.9 0.95 1.0 1.05 1.10]` based on CalibraEval research:
- LLMs tend to over-score first position → 0.9 penalty
- LLMs tend to under-score last position → 1.10 boost
- Middle positions neutral

Tune empirically for your specific model/domain.

## Performance

### Latency
- **Sequential**: ~3x single API call (~6-9 seconds for Gemini)
- **Parallel**: ~1x single API call (~2-3 seconds for Gemini)

### Token Usage
With 3 proposals × 500 chars each + prompt overhead:
- ~2000-3000 tokens per round
- 3 rounds = ~6000-9000 tokens total
- Cost: pennies per evaluation with Gemini

## Research References

Spec document cites 15+ papers on LLM judge biases:
- Position bias: 60-70% preference for certain positions
- Verbosity bias: >90% preference for longer text
- Sycophancy: multi-agent agreement reduces accuracy
- Calibration: 20-30% fairness improvement from position weights

See `docs/research/50-specs/evaluator-oct1.md` for full bibliography.

## Future Enhancements

Potential improvements (not currently implemented):

1. **Condorcet tie-breaking** - Pairwise preference matrix for complex ties
2. **Early stopping** - Abort rounds if clear winner emerges
3. **Multi-model ensemble** - Mix Gemini/Codex/Grok judges (if justified by gains)
4. **Fine-tuned evaluator** - Train on past architecture decisions
5. **Criterion-specific scores** - Request breakdown per criterion (more tokens)

Keep it simple - current implementation achieves 80/20 goal.

## Files

```
src/eval/
  core.cljc      - Main evaluation pipeline (pure functions)
  llm.clj        - LLM API integrations (Gemini, Codex, Grok)
  async.clj      - Parallel evaluation (core.async)

dev/
  eval_repl.clj  - REPL utilities and testing helpers

test/
  eval_core_test.clj - Comprehensive unit tests

scripts/
  test-evaluator.sh  - End-to-end test with real API

docs/
  research/50-specs/evaluator-oct1.md - Full design spec
  EVALUATOR.md                         - This file
```

## License

Same as project license.
