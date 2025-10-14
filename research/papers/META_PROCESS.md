# Meta-Process: Idea → Implementation → Validation

## Full Lifecycle with Self-Verification

```
┌─────────────────────────────────────────────────────────────────────┐
│                      IDEA GENERATION                                │
│  Input: Problem/frustration/observation                            │
│  Output: Rough description                                          │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   PROPOSAL GENERATION                               │
│  Tools: propose(description)                                        │
│  • Query 3 AIs (gemini, codex, grok)                               │
│  • Generate diverse approaches                                      │
│  • Store: research/runs/{run_id}/proposals/                        │
│  Output: [proposal-1, proposal-2, proposal-3]                      │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │ FAST FILTER   │ ← Human: Read proposals, gut check
         │ (1-2 min)     │   • Obviously broken?
         └───────┬───────┘   • Missing the point?
                 │           • Too complex?
                 │
                 ▼ (Keep 1-3 proposals)
┌─────────────────────────────────────────────────────────────────────┐
│              DETAILED EVALUATION (Tournament)                       │
│  Tools: compare_multiple(proposals, eval_prompt)                   │
│  • Run Swiss-Lite tournament                                        │
│  • Multiple judges (gpt5, gemini, grok)                            │
│  • Quality gates: R², Kendall-τ, brittleness                      │
│  Output: Ranked proposals with confidence intervals                │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │ SANITY CHECK  │ ← Validation Gates
         │ (automated)   │   • Status: VALID or INVALID?
         └───────┬───────┘   • If INVALID: Review metrics
                 │           • R² < 0.7 → judges incoherent
                 │           • τ < 0.8 → unstable rankings
                 ▼
         [VALID] ✓
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    SPEC REFINEMENT                                  │
│  Tools: refine(proposal_id, feedback)                              │
│  Loop (max 5 rounds):                                               │
│    1. Generate spec (types, tests, examples)                       │
│    2. Validate (run tests, check types, REPL smoke test)          │
│    3. If passed → DONE                                              │
│    4. Else → incorporate feedback, round++                         │
│  Output: {spec_id, status: "ready"|"needs_review"}                │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │ PREFLIGHT     │ ← Before Implementation
         │ (manual)      │   • Does spec make sense?
         └───────┬───────┘   • Can I REPL-test it?
                 │           • Any obvious gaps?
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     IMPLEMENTATION                                  │
│  Human implements (or agent with supervision)                      │
│  • Write code                                                       │
│  • Run tests                                                        │
│  • REPL verify                                                      │
│  • Commit with spec reference                                      │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    DECISION RECORD (ADR)                            │
│  Tools: decide(spec_id, decision, commit_sha)                      │
│  Records:                                                           │
│  • Which proposal → which spec → which commit                      │
│  • Why this approach (tradeoffs accepted)                          │
│  • What alternatives were rejected (and why)                       │
│  • How to verify it works                                          │
│  Output: {adr_id, adr_uri}                                         │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   USAGE VERIFICATION                                │
│  (1 week later, 1 month later)                                     │
│  Questions:                                                         │
│  • Did implementation actually solve the problem?                  │
│  • How many times used vs expected?                                │
│  • What friction did I hit?                                        │
│  • Would I do it differently now?                                  │
│  Output: Retrospective notes in ADR                                │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 ▼
         ┌───────────────┐
         │ PROCESS AUDIT │ ← Self-Verification Loop
         │ (quarterly)   │
         └───────┬───────┘
                 │
                 ▼
    Was the PROCESS itself good?
```

---

## Self-Verification: How Do We Know the Process Works?

### Level 1: Per-Run Quality Gates (Automatic)

**During Tournament Evaluation**:
```python
def validate_tournament_quality(result):
    gates = {
        "schema_r2": result["schema-r2"] > 0.7,      # Judges coherent?
        "kendall_tau": result["tau-split"] > 0.8,    # Stable rankings?
        "brittleness": result["brittleness"]["k"] < 2, # Robust?
        "dispersion": result["dispersion"]["beta"] < 7.0 # Agreement?
    }

    if not all(gates.values()):
        return {"status": "INVALID", "failed_gates": [k for k, v in gates.items() if not v]}

    return {"status": "VALID"}
```

**If INVALID**: Don't trust the ranking. Either:
- Eval prompt was ambiguous → refine criteria
- Proposals too similar → need more diversity
- Judges disagree fundamentally → manual review needed

### Level 2: Spec Validation (Automatic)

**During Refinement**:
```python
def validate_spec(spec):
    checks = [
        has_types(spec),           # Types defined?
        has_tests(spec),           # Tests present?
        has_examples(spec),        # Usage examples?
        passes_tests(spec),        # Tests pass?
        repl_loads(spec),          # Can load in REPL?
    ]

    return {
        "passed": all(checks),
        "failures": [name for name, passed in zip(CHECK_NAMES, checks) if not passed]
    }
```

**If fails after 5 rounds**: Flag for manual review (don't block, but warn)

### Level 3: Implementation Verification (Manual + Assisted)

**After Implementation**:
```bash
# Smoke test script
./scripts/verify-implementation $SPEC_ID

# Checks:
# - Code compiles ✓
# - Tests pass ✓
# - Example from spec works ✓
# - REPL demo works ✓
```

### Level 4: Usage Tracking (Passive)

**Track Actual Usage**:
```jsonl
{"event": "tool_used", "tool": "propose", "run_id": "...", "timestamp": "..."}
{"event": "tool_used", "tool": "refine", "run_id": "...", "timestamp": "..."}
{"event": "implementation_used", "adr_id": "...", "context": "writing tests"}
```

**Analysis** (quarterly):
```python
# How many ideas → proposals → specs → ADRs?
# Where do most drop off?
# Which ADRs get used vs bit-rot?

def analyze_funnel():
    ideas = count_events(type="propose")
    refined = count_events(type="refine")
    decided = count_events(type="decide")

    print(f"Conversion: {ideas} → {refined} → {decided}")
    print(f"Proposal-to-spec: {refined/ideas:.1%}")
    print(f"Spec-to-ADR: {decided/refined:.1%}")
```

### Level 5: Retrospectives (Manual, Scheduled)

**Every Quarter**: Review all ADRs from last 3 months

```markdown
# Q4 2025 Process Retrospective

## Stats
- Ideas proposed: 12
- Specs created: 8  (67% conversion)
- ADRs recorded: 5  (62% completion)

## ADR Usage Review
- adr-001 (review tooling): ✓ Used weekly
- adr-002 (eval improvements): ✗ Never used (over-engineered)
- adr-003 (mcp server): ✓ Used daily
- adr-004 (research pipeline): ~ Used once (too complex)
- adr-005 (minimal-linear): [NEW - check in 1 month]

## Process Improvements
- Tournament eval works great (R² >0.8 every time)
- Refinement loops often hit max rounds → need better validators
- ADRs without "how to verify" section never get verified
- Quarterly reviews are too infrequent → try monthly

## Next Quarter
- [ ] Add validator for "does spec have verification plan?"
- [ ] Track time spent per stage (is refinement too slow?)
- [ ] Experiment: Skip tournament for obvious winners
```

---

## Process Health Metrics

### Green (Healthy Process)
```
✓ Tournament validity rate: >80%
✓ Spec convergence: <4 rounds average
✓ Implementation matches spec: >90%
✓ ADR usage rate: >50% still relevant after 3 months
✓ Retrospective completion: Every quarter
```

### Yellow (Needs Attention)
```
⚠ Tournament validity: 60-80%
⚠ Spec convergence: 4-5 rounds
⚠ Spec-implementation drift: noticeable gaps
⚠ ADR usage: 30-50% relevant
⚠ Retrospectives: Every 6 months
```

### Red (Process Broken)
```
✗ Tournament validity: <60%  → Eval criteria are bad
✗ Spec convergence: Max out 5 rounds  → Validators disagree
✗ Implementation ignores spec  → Spec was unrealistic
✗ ADR usage: <30% relevant  → Building wrong things
✗ Retrospectives: Never  → Flying blind
```

---

## Example: Full Run Through

### T=0: Idea
"MCP tooling for review process is too complex"

### T+10min: Proposals Generated
```bash
propose("simplify review process mcp tooling")
# → run-2025-10-13/proposals/{minimal,staged,event-sourced,agent-loop,micro-kernel}
```

### T+15min: Fast Filter (Human)
- Keep: minimal-linear, micro-kernel
- Discard: event-sourced (too complex), agent-loop (too many calls), staged (gates will frustrate)

### T+20min: Tournament
```bash
compare_multiple(
  items={minimal, micro-kernel},
  eval_prompt="...",
  judges=["gpt5-codex", "gemini25-pro"]
)

# Results:
# Status: VALID ✓
# Winner: minimal-linear (θ=2.1, CI=[1.8, 2.4])
# Runner-up: micro-kernel (θ=0.0, CI=[-0.3, 0.3])
```

### T+25min: Refine Winner
```bash
refine("minimal-linear", "")

# Round 1: Generate spec
# Round 2: Add missing tests
# Round 3: Add REPL examples
# Status: READY ✓
```

### T+30min: Preflight (Human)
- Read spec
- Copy code to REPL, smoke test
- Looks good ✓

### T+2hr: Implementation
- Write mcp/review/server.py
- Write tests
- REPL verify
- Commit: feat(mcp): add minimal-linear review server

### T+2hr 5min: ADR
```bash
decide(
  spec_id="minimal-linear-spec-001",
  decision="Implemented minimal-linear approach",
  commit="abc123"
)

# Creates: research/adrs/adr-006-review-tooling.md
```

### T+1 week: Usage Check
```python
# Used 3 times this week for:
# - Evaluating implementation designs ✓
# - Comparing refactor approaches ✓
# - Architectural proposals (existing bb script better)

# Conclusion: Useful but not replacing bb scripts
```

### T+1 month: Retrospective
```markdown
## ADR-006: Review Tooling (Minimal Linear)

**Status**: Active use

**Usage**: 12 times (3/week average)
- Implementation comparisons: 8 times ✓
- Quick design questions: 4 times ✓
- NOT replacing bb architectural-proposals (that's fine)

**Friction Points**:
- API keys sometimes not loaded → fixed with dotenv
- Results hard to read → added pretty-printing
- No way to save favorites → will add bookmarking

**Would I do it differently?**
- ✓ Glad we did minimal-linear (micro-kernel would add complexity)
- ✓ Tournament eval is great
- ✗ Should have tested with real eval CLI earlier
- ~ Refinement loop only used once (specs simple enough)

**Process observations**:
- Tournament took 5min (acceptable)
- Spec refinement unnecessary (could skip next time)
- Implementation was straightforward (spec helped)
- Biggest value: Tournament forced clarity on criteria
```

---

## Meta-Meta: How Do We Verify the Self-Verification?

**The Recursion Problem**: How do we know our metrics are good?

**Solution**: Compare with Ground Truth

### Quarterly: Manual Audit
Pick 3 random ADRs and ask:
1. **Did it solve the original problem?** (Yes/No/Partial)
2. **Is it still being used?** (Check logs)
3. **Would you do it again?** (Hindsight)

If answers diverge from metrics:
→ **Metrics are lying** → Fix the metrics

### Example Divergence:
```
Metric says: "ADR usage: 60% still relevant"
Manual audit finds: "Only 2/5 ADRs actually get used, rest are abandoned"

→ Problem: "Usage" metric counts any reference, not actual use
→ Fix: Track "used in last 30 days" not "ever referenced"
```

---

## ASCII: Full Process with Feedback Loops

```
                    ┌──────────────┐
                    │   IDEA       │
                    └──────┬───────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │  PROPOSE        │
                  │  (3 AIs)        │
                  └────────┬────────┘
                           │
                     ┌─────▼──────┐
                     │ FAST FILTER│ (human gut check)
                     └─────┬──────┘
                           │
                           ▼
    ┌────────────────────────────────────────┐
    │        TOURNAMENT EVALUATION           │
    │  ┌──────────────────────────────────┐  │
    │  │ Quality Gates (automatic)        │  │
    │  │ • R² > 0.7?                      │  │
    │  │ • τ > 0.8?                       │  │
    │  │ • Brittleness < 2?               │  │
    │  └──────────┬───────────────────────┘  │
    │             ▼                           │
    │        [VALID] / [INVALID]             │
    └────────────┬───────────────────────────┘
                 │
                 ├─ [INVALID] → Refine eval criteria ──┐
                 │                                      │
                 ▼ [VALID]                              │
         ┌───────────────┐                             │
         │    REFINE     │                             │
         │  (max 5 loop) │                             │
         └───────┬───────┘                             │
                 │                                      │
           ┌─────▼──────┐                              │
           │ PREFLIGHT  │ (human smoke test)           │
           └─────┬──────┘                              │
                 │                                      │
                 ▼                                      │
         ┌───────────────┐                             │
         │  IMPLEMENT    │                             │
         └───────┬───────┘                             │
                 │                                      │
                 ▼                                      │
         ┌───────────────┐                             │
         │  ADR          │                             │
         │  (decision)   │                             │
         └───────┬───────┘                             │
                 │                                      │
                 ▼                                      │
         ┌───────────────┐                             │
         │  USE          │                             │
         │  (track)      │                             │
         └───────┬───────┘                             │
                 │                                      │
           [1 week later]                              │
                 │                                      │
                 ▼                                      │
         ┌───────────────┐                             │
         │  VERIFY       │                             │
         │  (did it work?)                             │
         └───────┬───────┘                             │
                 │                                      │
                 ├─ Works well → Continue               │
                 ├─ Has issues → Note in ADR            │
                 └─ Unused → Mark for review            │
                                                        │
           [1 month later]                             │
                 │                                      │
                 ▼                                      │
         ┌────────────────┐                            │
         │  RETROSPECTIVE │                            │
         │  (ADR review)  │                            │
         └───────┬────────┘                            │
                 │                                      │
                 ├─ Still good → Archive                │
                 ├─ Needs tweaks → Create new idea      │
                 └─ Total failure → Learn why ─────────┘
                                                        │
           [Quarterly]                                  │
                 │                                      │
                 ▼                                      │
    ┌────────────────────────────────┐                 │
    │    PROCESS AUDIT               │                 │
    │  • Conversion rates            │                 │
    │  • Quality gate pass rates     │                 │
    │  • ADR usage patterns          │                 │
    │  • Manual audit vs metrics     │                 │
    └────────────┬───────────────────┘                 │
                 │                                      │
                 └─ Process broken? → Improve ──────────┘
                 └─ Process good? → Continue

```

---

## Key Insights

1. **Validation at Every Stage**
   - Automatic gates (R², τ, brittleness)
   - Manual checks (gut, smoke test, retrospective)
   - Usage tracking (passive verification)

2. **Multiple Feedback Loops**
   - Fast (per-run quality gates)
   - Medium (1 week, 1 month verification)
   - Slow (quarterly retrospectives)

3. **Self-Correcting**
   - Invalid tournaments → refine criteria
   - Unused ADRs → stop building that way
   - Process metrics lie → fix the metrics

4. **Fail-Safe Defaults**
   - Tournament INVALID → don't trust ranking
   - Spec doesn't converge → manual review
   - Implementation differs from spec → investigate
   - ADR never used → mark for review

5. **Ground Truth Check**
   - Quarterly manual audit
   - Compare metrics vs reality
   - If diverge → fix metrics, not reality

---

## Start Simple

**Phase 1** (this week):
- Implement minimal-linear MCP
- Run 2-3 evaluations
- Track: Did tournament give good results?

**Phase 2** (next week):
- Add usage tracking (simple JSONL)
- Check: Are we using the tools?

**Phase 3** (next month):
- First retrospective
- Review: Did process add value or just overhead?

**Phase 4** (next quarter):
- Process audit
- Decide: Keep, modify, or abandon?

The process itself is subject to the same evaluation it provides.
