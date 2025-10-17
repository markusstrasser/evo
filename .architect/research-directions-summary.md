# Fruitful LLM/GenAI Research Directions (AI Research & Verification Perspective)

**Date:** 2025-10-17
**Context:** Based on architect skill work and recent research findings

---

## TL;DR

**Top 3 immediate research questions with high ROI:**

1. **Confidence Calibration** - Can we predict when LLM judges will be wrong? (Infrastructure exists, 1-2 days)
2. **Provider Specialization** - Do different system prompts maximize diversity from fixed models? (Design complete, 2-3 weeks)
3. **Optimal Tournament Structures** - Is round-robin overkill for N=3-5 proposals? (Infrastructure exists, 3-4 days)

---

## 1. Confidence Calibration in LLM Judges 🔥 **IMMEDIATE PRIORITY**

### The Problem
- LLM judges have < 0.7 accuracy vs humans
- We have NO way to know when they're wrong
- Current confidence scores don't predict correctness

### Research Question
Can we develop metrics that predict when tournament rankings will diverge from human preferences?

### Why This Matters
- Auto-approve high-confidence decisions → save human time
- Flag low-confidence decisions → avoid bad choices
- Understand **when** LLMs are reliable vs unreliable

### How to Execute (1-2 days)
1. Run architect skill on 20 test cases
2. Collect human rankings independently
3. Measure correlations:
   - Judge agreement entropy → human agreement
   - Ranking stability across runs → correctness
   - Proposal similarity → judge confusion

### Success Metric
Confidence score correlates ≥ 0.75 with correctness

---

## 2. Provider Specialization for Maximum Diversity ⭐ **HIGH VALUE**

### The Problem
- We use 3 best-in-class models (Gemini 2.5 Pro, GPT-5 Codex, Grok-4)
- Currently they receive **identical prompts** → limited diversity
- Missing opportunity to explore different solution spaces

### Research Question
What system prompt specializations maximize proposal diversity AND quality?

### Proposed Specializations
**Variant A - Architectural Styles:**
- Gemini → Reactive/Event-Driven (event sourcing, streams, immutability)
- Codex → Functional/Type-Driven (types, pure functions, composition)
- Grok → Pragmatic/Debuggable (simple, observable, maintainable)

**Why These:** Fundamentally different paradigms naturally explore different solutions

### Metrics
**Diversity:**
- Embedding distance (OpenAI embeddings)
- Vocabulary overlap (TF-IDF Jaccard distance)
- Pattern coverage (event sourcing vs monads vs service layers)

**Quality:**
- Tournament ranking (existing infrastructure)
- Human preference correlation
- Implementability checklist

### How to Execute (2-3 weeks)
- **Week 1:** Implement specialized prompts, build diversity analysis tooling
- **Week 2:** Run 20 test cases × 4 configs (baseline + 3 variants) = 80 runs
- **Week 3:** Analysis and report

### Success Metric
50%+ diversity improvement over baseline, quality ≥ baseline

**See:** `.architect/experiments/provider-specialization-experiment.md` for full design

---

## 3. Optimal Tournament Structures for Small N 📊 **QUICK WIN**

### The Problem
- Research focuses on large tournaments (100+ items)
- Our use case: rank 3-5 proposals
- Round-robin costs O(N²), Swiss-system costs O(log N)

### Research Question
What's the optimal tournament structure for N=3-5?

### Approaches to Test
1. **Round-robin** (current) - All pairs, most expensive
2. **Swiss-system** - log₂(N) rounds, cheaper
3. **Baseline-fixed** - Compare each to reference, cheapest
4. **Sequential elimination** - Winner-take-all bracket

### Metrics
- Correlation with human rankings
- Cost (number of LLM API calls)
- Stability (variance across runs)

### How to Execute (3-4 days)
1. Implement 4 tournament structures
2. Run on 20 test cases
3. Compare quality vs cost trade-offs

### Success Metric
Find structure that matches round-robin quality at 50% cost

---

## 4. Real-Time Bias Detection 🚨

### The Problem
- Research identifies 11 bias types (verbosity, authority, etc.)
- Detection is post-hoc analysis
- No real-time intervention

### Research Question
Can we detect bias patterns during evaluation and auto-correct?

### Approach
- Log proposal metadata (length, citations, sentiment, terminology)
- Calculate correlations in real-time
- Flag suspicious patterns (e.g., longest always wins)
- Test mitigations:
  - Re-run with bias-aware prompt
  - Add de-biasing judge
  - Flag for human review

### Success Metric
Catch 70%+ of systematic bias cases before they impact decisions

---

## 5. Context Length vs Quality Trade-offs 📏

### The Problem
- Full proposals can be 2000+ tokens
- Tournament judges see full text
- Is this necessary?

### Research Question
How much truncation/summarization hurts ranking quality?

### Test Variants
- Full proposals (baseline)
- First 50% only
- Last 50% only
- AI-generated summaries (500 tokens)
- Section-based summaries (Approach + Trade-offs)

### Success Metric
If summaries maintain 90%+ ranking quality → 80% cost reduction

---

## 6. Verification Automation 🤖

### The Problem
- No automated checks for architectural consistency
- LLMs hallucinate (missing components, impossible claims)
- Human review is bottleneck

### Research Question
Can we build verification rules that catch hallucinations?

### Checks to Develop
- Component consistency (all references defined)
- Data flow completeness (inputs/outputs specified)
- Technology conflicts (incompatible versions)
- Scale claims verification (back-of-envelope math)
- Security gaps (missing auth, encryption)

### Success Metric
Auto-reject 80%+ of obviously broken proposals with < 5% false positives

---

## 7. Prompt vs Model Variance 🎯

### The Problem
- We tune prompts extensively
- Is model choice more important?

### Research Question
What contributes more to proposal quality?

### Experiment
Generate proposals with:
- 3 models × 1 prompt
- 1 model × 3 prompts
- 3 models × 3 prompts (full factorial)

**ANOVA analysis:** Variance explained by model vs prompt vs interaction

### Success Metric
Focus optimization effort where it has most impact

---

## 8. Human-in-Loop Timing Optimization ⏱️

### Research Question
When should humans review?

### Test Strategies
- Never (fully automated)
- Final decision only (current)
- After proposals (before tournament)
- After tournament (before ADR)
- At checkpoints (mid-generation)

### Success Metric
Find timing that maximizes quality/time ratio

---

## 9. Tournament Reproducibility 🔄

### The Problem
- Same proposals + same judges → different results
- Why?

### Research Question
What causes instability?

### Measure
- Run same tournament 10× with different seeds
- Track rank variance per proposal
- Correlate with:
  - Proposal similarity (close races?)
  - Judge model (which consistent?)
  - Time of day (API variance?)
  - Temperature settings

### Success Metric
Quantify when rankings are trustworthy vs noisy

---

## 10. Cross-Architecture Pattern Transfer 🔀

### Research Question
Can insights from one domain transfer to another?

### Approach
- Build dataset of decisions across domains
- Test transfer learning:
  - Microservices → data pipelines
  - Event sourcing → state management
  - API design → inter-service communication

### Success Metric
Domain transfer saves 30%+ human review time

---

## Prioritization Matrix

| Research Direction | Time to Execute | Potential Impact | Infrastructure Ready? | Priority |
|-------------------|-----------------|------------------|----------------------|----------|
| 1. Confidence Calibration | 1-2 days | High | ✅ Yes | **P0** |
| 2. Provider Specialization | 2-3 weeks | Very High | 🟡 Partial | **P0** |
| 3. Optimal Tournaments | 3-4 days | Medium | ✅ Yes | **P1** |
| 4. Real-Time Bias | 1 week | Medium | 🟡 Partial | **P1** |
| 5. Context Length | 2-3 days | Medium | ✅ Yes | **P2** |
| 6. Verification | 2-3 weeks | High | ❌ No | **P2** |
| 7. Prompt vs Model | 1 week | Medium | 🟡 Partial | **P2** |
| 8. Human Timing | 2 weeks | High | 🟡 Partial | **P3** |
| 9. Reproducibility | 1 week | Low | ✅ Yes | **P3** |
| 10. Pattern Transfer | 4+ weeks | Very High | ❌ No | **P3** |

---

## Recommended Roadmap

### Phase 1: Quick Wins (Week 1)
**Goal:** Validate research infrastructure and get early results

- [ ] Run #1 (Confidence Calibration) - 1-2 days
  - 20 test cases, human rankings, correlation analysis
  - **Deliverable:** Confidence score formula that predicts correctness

- [ ] Run #3 (Optimal Tournaments) - 3-4 days
  - Implement 4 tournament structures, test on 20 cases
  - **Deliverable:** Cost vs quality trade-off analysis

**Output:** 2 research reports, validate measurement infrastructure

---

### Phase 2: High-Value Experiment (Weeks 2-4)
**Goal:** Execute provider specialization experiment

- [ ] Week 2: Implementation
  - Build specialized prompts (Variant A)
  - Create diversity analysis tooling
  - Test on 3 sample cases

- [ ] Week 3: Data Collection
  - Run 20 test cases × 4 configs = 80 runs
  - Collect diversity + quality metrics
  - Human rankings for all cases

- [ ] Week 4: Analysis
  - Generate diversity/quality reports
  - Statistical tests (t-tests, ANOVA)
  - Write research paper

**Output:** Research paper on provider specialization, production-ready specialized prompts

---

### Phase 3: Polish & Scale (Weeks 5-8)
**Goal:** Add remaining quick-win features

- [ ] Real-time bias detection (#4)
- [ ] Context length optimization (#5)
- [ ] Prompt vs model analysis (#7)

**Output:** Production-grade architect skill with research-backed optimizations

---

## Expected Publications

### Paper 1: "Diversity-Quality Trade-offs in Multi-LLM Architectural Decision Systems"
**Contribution:** Show that specialized prompts increase diversity without sacrificing quality

**Venue:** ICSE 2026 (Software Engineering), NeurIPS 2025 LLM Workshop

---

### Paper 2: "Confidence Calibration for LLM-as-a-Judge Systems"
**Contribution:** Develop metrics that predict when LLM rankings diverge from human preferences

**Venue:** EMNLP 2025 (NLP), ICLR 2026 (ML)

---

### Paper 3: "Verification Automation for AI-Generated Architecture Proposals"
**Contribution:** Automated checks that catch AI hallucinations in architectural designs

**Venue:** ASE 2026 (Automated Software Engineering), ESEC/FSE 2026

---

## Next Actions

**Today:**
1. Review this summary
2. Decide: Which research direction to start with?
3. If #2 (Provider Specialization): Review full experiment design at `.architect/experiments/provider-specialization-experiment.md`

**Tomorrow:**
- Start implementation of chosen research direction
- Set up experiment infrastructure
- Run pilot on 3-5 test cases

**Week 1 Goal:**
- Complete one research direction end-to-end
- Validate measurement infrastructure
- Decide: continue with full study or pivot

