# V3 Evaluator

Tournament-based ranking system using Bradley-Terry model with bias correction and quality gates.

## Design Philosophy

**Problem**: Fixed-baseline comparisons are fragile; hardcoded bias corrections are brittle; pretty leaderboards hide unstable results.

**Solution**: Swiss-Lite tournament → BT ranking → learned debiasing → readiness checks. Refuse to publish when quality thresholds fail.

**Key Insight**: Pairwise can be distracted by surface cues; hybrid protocol (pairwise for coarse order, pointwise for ties) wins.

## Pipeline Flow

```
Items → Tournament (Swiss-Lite) → Pairwise Duels → Edges
                                         ↓
                                  Vignettes (bias detection)
                                         ↓
                                  Debias Correction
                                         ↓
                                  Bradley-Terry Fit
                                         ↓
                    ┌────────────────────┴────────────────────┐
                    ↓                                         ↓
            Stability Checks                          Pointwise Refine
         (τ, R², Drop-K, Mallows)                    (for overlapping CIs)
                    ↓                                         ↓
                    └─────────────→ Ranking ←─────────────────┘
                                         ↓
                                  Readiness Gate
                                  (OK/UNSTABLE/INVALID)
```

## Files

### Core Pipeline
- **`core_v3.cljc`** - Orchestration with readiness checks. Runs tournament rounds until τ-stable, applies debiasing, computes quality metrics, gates on thresholds.
- **`judges_api.clj`** - API-only LLM judges (GPT-5, Gemini, Claude, Kimi, Grok). Reproducible HTTP calls, no CLI fragility. Includes mock for testing.

### Tournament & Comparison
- **`tournament.cljc`** - Swiss-Lite scheduler. Seeds to min-degree, then pairs within BT-score brackets (Swiss) or strongest-vs-weakest (SWIM). Concentrates comparisons where uncertainty is high.
- **`pairwise.cljc`** - Duel execution. Calls judge with left/right items, returns edge with verdict + criteria + metadata (provider, flags, timestamp).

### Prompts & Validation
- **`prompts_v3.cljc`** - Strict JSON schemas for pairwise/pointwise. Validates output structure, auto-retries on invalid JSON. Supports vignette flags (NEW/OLD tags).

### Statistical Models
- **`bt.cljc`** - Bradley-Terry MLE via MM algorithm. Computes θ (scores), SE, CI. Includes Kendall-τ for stability, bootstrap splits, Drop-K brittleness index.
- **`mallows.cljc`** - Dispersion via Mallows model. Bootstrap resampling → estimate β (inverse dispersion) → consensus strength metric.

### Bias & Robustness
- **`debias.cljc`** - Online bias detection. Injects vignettes (5-10%), estimates position/recency/provenance biases from win rates, applies correction on logit scale before BT.
- **`attacks.cljc`** - Adversarial perturbations (NEW/OLD tags, EXPERT labels, verbosity padding). Computes ASR (attack success rate) to prove robustness.

### Quality Assurance
- **`schema_audit.cljc`** - R² adherence check. Regresses verdict on criteria scores. Low R² → judge is incoherent → flag INVALID.
- **`objective.clj`** - Spectral/OWASP feature extraction from OpenAPI specs. Grounds rankings with machine-verifiable signals (missing auth, BOLA risk, etc.).

### Output
- **`report.cljc`** - Formatted reports. Prints ranking with CIs, quality metrics (τ, R², Drop-K, dispersion, ASR), bias estimates, status (OK/UNSTABLE/INVALID).

## Usage

### REPL
```clojure
(require '[dev.eval.core-v3 :as v3])

(def items [{:id :a :text "Design A"}
            {:id :b :text "Design B"}
            {:id :c :text "Design C"}])

(def result (v3/evaluate! items {:providers [:gpt5-codex]}))

(require '[dev.eval.report :as rpt])
(rpt/print! result)
```

### Babashka
```bash
bb run-tournament     # Evaluate items.edn
bb run-report         # Print report from run.edn
bb run-attacks        # Test robustness
```

## Quality Gates

Results marked **UNSTABLE** if:
- Kendall-τ (split-half) < 0.9
- Mallows β (dispersion) < 1.0  
- Drop-K brittleness > 30%

Results marked **INVALID** if:
- Schema R² (adherence) < threshold (default 0.5)

**Gate enforced**: No pretty leaderboards from noise.

## Spec Reference

Full design rationale: `docs/research/50-specs/evaluator-oct2.md`
