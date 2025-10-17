# Provider Specialization Experiment: Maximum Diversity from Fixed Models

**Date:** 2025-10-17
**Status:** Design Phase
**Goal:** Find optimal system prompt specializations for Gemini 2.5 Pro, GPT-5 Codex, and Grok-4 to maximize proposal diversity and quality

---

## Research Question

**Given 3 fixed best-in-class models** (Gemini 2.5 Pro, GPT-5 Codex, Grok-4), what system prompt specializations maximize:
1. **Diversity** (proposals explore different solution spaces)
2. **Quality** (proposals are implementable and well-reasoned)
3. **Coverage** (proposals cover different architectural patterns/styles)

## Current State Analysis

**Problem:** All three providers currently receive nearly identical prompts (see `skills/architect/lib/providers.py`):

```python
# Current prompts (lines 119-205) - IDENTICAL except minor output format differences
prompt = f"""<role>
{role}  # "You are an architectural advisor"
</role>

{format_constraints_prompt(constraints)}  # Same MUST/SHOULD requirements

<task>
Generate an implementation proposal for: {description}

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons (be honest about tradeoffs)
5. Red flags to watch for during implementation
</task>

<output_format>
Use markdown with clear headings.
Be concise - aim for 1-2 pages total.
Use bullet points over paragraphs where possible.
</output_format>
"""
```

**Result:** Limited diversity - all models approach the problem similarly.

---

## Experiment Design

### Phase 1: Design Specialized Prompts (3 variants × 3 providers = 9 conditions)

#### Variant A: Architectural Style Specialization

**Hypothesis:** Assigning different architectural paradigms creates diverse solution spaces.

**Gemini Specialization - Reactive/Event-Driven:**
```python
<role>
You are a reactive systems architect specializing in event-driven architectures.

Focus areas:
- Event flows and message passing
- Reactive state management (observers, subscriptions)
- Immutability and data flow
- Temporal aspects (event ordering, replay)
- Backpressure and flow control
</role>

<architectural_bias>
STRONGLY PREFER:
- Event sourcing over CRUD
- Reactive streams over imperative loops
- Immutable data structures
- Declarative transformations
- Observable patterns
</architectural_bias>

<output_format>
Structure your proposal around:
1. Event flows (what events, who produces/consumes)
2. State transitions (how events change state)
3. Components (event handlers, stores, projections)
4. Data model (event schemas, aggregate roots)
5. Trade-offs (consistency, complexity, debugging)

Be explicit about event ordering and failure scenarios.
</output_format>
```

**Codex Specialization - Functional/Type-Driven:**
```python
<role>
You are a functional programming architect specializing in type-driven design.

Focus areas:
- Type safety and algebraic data types
- Pure functions and referential transparency
- Composition over inheritance
- Effect management (IO, state, errors)
- Property-based reasoning
</role>

<architectural_bias>
STRONGLY PREFER:
- Strong static typing (use TypeScript/types as design tool)
- Pure functions with explicit effects
- Composition via function pipelines
- Immutability by default
- Domain modeling with sum/product types
</architectural_bias>

<output_format>
Structure your proposal around:
1. Type signatures (core types and their relationships)
2. Pure functions (composition and transformation logic)
3. Effect boundaries (where side-effects occur)
4. API design (function signatures as contracts)
5. Trade-offs (ceremony, learning curve, testability benefits)

Show example type definitions and function signatures.
</output_format>
```

**Grok Specialization - Pragmatic/Debuggable:**
```python
<role>
You are a pragmatic systems architect specializing in debuggable, maintainable code.

Focus areas:
- Developer ergonomics and clarity
- Observable state and debugging hooks
- Simple over clever
- Graceful degradation
- Operational concerns (logging, metrics, errors)
</role>

<architectural_bias>
STRONGLY PREFER:
- Explicit over implicit (no magic)
- Simple data structures (objects, maps, arrays)
- Clear control flow (avoid callbacks-of-callbacks)
- Observable intermediate states
- Incremental adoption (no big-bang rewrites)
</architectural_bias>

<output_format>
Structure your proposal around:
1. Developer mental model (how would you explain this to a junior dev?)
2. Core abstractions (what are the 2-3 key concepts?)
3. Debugging strategy (how to inspect state, trace execution)
4. Failure modes (what goes wrong and how to detect it)
5. Trade-offs (simplicity vs performance, verbosity vs magic)

Emphasize debuggability and operational visibility.
</output_format>
```

#### Variant B: Problem-Solving Approach Specialization

**Hypothesis:** Different reasoning strategies explore different solutions.

**Gemini - Top-Down Decomposition:**
```python
<reasoning_approach>
Use top-down decomposition:
1. Start with high-level architecture (boxes and arrows)
2. Decompose into subsystems
3. Define interfaces between subsystems
4. Drill into subsystem internals

Think in layers: UI → Application → Domain → Infrastructure
</reasoning_approach>
```

**Codex - Bottom-Up Composition:**
```python
<reasoning_approach>
Use bottom-up composition:
1. Identify atomic operations (primitives)
2. Design composition patterns (how primitives combine)
3. Build higher-level abstractions from primitives
4. Show how system emerges from composition

Think in building blocks: Functions → Pipelines → Services → System
</reasoning_approach>
```

**Grok - Constraint-Driven Design:**
```python
<reasoning_approach>
Use constraint-driven design:
1. List all constraints (performance, memory, complexity)
2. Identify constraint conflicts (trade-offs)
3. Design to satisfy critical constraints first
4. Add features within constraint envelope

Think in budgets: Time budget, space budget, complexity budget
</reasoning_approach>
```

#### Variant C: Output Detail Specialization

**Hypothesis:** Different detail levels create complementary proposals.

**Gemini - High-Level Vision:**
```python
<detail_level>
Focus on strategic architecture (30,000 ft view):
- System-wide patterns and principles
- Architectural styles and paradigms
- Long-term evolution and migration paths
- Integration with existing systems

Keep implementation details abstract.
Target: CTO/tech lead audience.
</detail_level>
```

**Codex - Implementation Blueprint:**
```python
<detail_level>
Focus on tactical implementation (ground level):
- Specific libraries/frameworks to use
- Code structure and module organization
- API signatures and data formats
- Testing strategies

Provide concrete implementation guidance.
Target: Senior developer audience.
</detail_level>
```

**Grok - Operational Playbook:**
```python
<detail_level>
Focus on operational concerns (runtime view):
- Deployment and configuration
- Monitoring and observability
- Error handling and recovery
- Performance characteristics

Provide operational runbook.
Target: DevOps/SRE audience.
</detail_level>
```

---

### Phase 2: Measure Diversity

**Metrics:**

1. **Semantic Diversity (Embedding Distance)**
   - Convert each proposal to embedding (OpenAI text-embedding-3-large)
   - Calculate pairwise cosine distances
   - Higher distance = more diverse
   ```python
   import openai
   import numpy as np
   from sklearn.metrics.pairwise import cosine_distances

   embeddings = [openai.Embedding.create(input=p, model="text-embedding-3-large")
                 for p in proposals]
   distances = cosine_distances(embeddings)
   diversity_score = np.mean(distances[np.triu_indices_from(distances, k=1)])
   ```

2. **Vocabulary Diversity (TF-IDF Overlap)**
   - Identify unique technical terms in each proposal
   - Measure overlap using Jaccard distance
   ```python
   from sklearn.feature_extraction.text import TfidfVectorizer

   vectorizer = TfidfVectorizer(max_features=100, stop_words='english')
   tfidf = vectorizer.fit_transform(proposals)

   # Get top terms per proposal
   feature_names = vectorizer.get_feature_names_out()
   for doc in tfidf:
       top_terms = [feature_names[i] for i in doc.toarray()[0].argsort()[-10:]]

   # Calculate Jaccard distance between term sets
   ```

3. **Pattern Coverage (Manual Annotation)**
   - Tag each proposal with architectural patterns mentioned:
     - Event sourcing, CQRS, Repository, Saga, Hexagonal, etc.
     - Functional patterns: Monad, Functor, Algebraic Effects, etc.
     - Pragmatic patterns: Service Layer, Transaction Script, etc.
   - Count unique patterns across all proposals
   ```python
   patterns_by_proposal = {
       "proposal-1": {"event-sourcing", "cqrs", "saga"},
       "proposal-2": {"monad", "algebraic-effects", "pipeline"},
       "proposal-3": {"service-layer", "repository", "transaction-script"}
   }
   total_coverage = len(set.union(*patterns_by_proposal.values()))
   overlap = len(set.intersection(*patterns_by_proposal.values()))
   ```

4. **Approach Diversity (Structural Diff)**
   - Compare section structure and content themes
   - Measure: Do proposals organize information differently?
   ```python
   # Extract section headings
   headings_by_proposal = {
       "proposal-1": ["Event Flows", "State Transitions", "Event Handlers"],
       "proposal-2": ["Type Signatures", "Pure Functions", "Effect Boundaries"],
       "proposal-3": ["Mental Model", "Core Abstractions", "Debugging Strategy"]
   }
   structural_similarity = len(set.intersection(*map(set, headings_by_proposal.values()))) / \
                           max(len(h) for h in headings_by_proposal.values())
   ```

---

### Phase 3: Measure Quality

**Metrics:**

1. **Tournament Ranking (Existing Infrastructure)**
   - Run standard tournament evaluation
   - Track: winner, confidence, kendall-τ, R²
   ```bash
   skills/architect/run.sh rank <run-id>
   ```

2. **Human Preferences (Ground Truth)**
   - For each test case, have human architect rank proposals (1-3)
   - Compare human ranking with tournament ranking
   - Measure: Spearman correlation, Kendall τ
   ```python
   from scipy.stats import spearmanr, kendalltau

   human_ranks = [1, 3, 2]  # Human preference order
   tournament_ranks = [1, 2, 3]  # Tournament results

   spearman_corr, _ = spearmanr(human_ranks, tournament_ranks)
   kendall_corr, _ = kendalltau(human_ranks, tournament_ranks)
   ```

3. **Implementability Score (Checklist)**
   - Does proposal specify:
     - [ ] Data structures/types
     - [ ] Component responsibilities
     - [ ] Integration points
     - [ ] Error handling approach
     - [ ] Trade-offs documented
   - Score: 0-5 (one point per item)

4. **Feasibility Score (Expert Review)**
   - Expert rates each proposal:
     - **5** = Ready to implement
     - **4** = Minor details missing
     - **3** = Significant gaps
     - **2** = Major issues
     - **1** = Not viable

---

### Phase 4: Test Cases (20 scenarios)

**Simple Cases (5):**
1. "Add undo/redo to a text editor"
2. "Implement search with autocomplete"
3. "Add dark mode toggle"
4. "Cache API responses"
5. "Add pagination to list view"

**Medium Complexity (10):**
6. "State management for a collaborative editor"
7. "Real-time presence indicators"
8. "Offline-first sync strategy"
9. "Event sourcing for audit trail"
10. "Multi-tenant data isolation"
11. "Rate limiting for API"
12. "Background job processing"
13. "File upload with progress"
14. "Notification system"
15. "A/B testing framework"

**Complex Cases (5):**
16. "Distributed transaction coordinator"
17. "CQRS with event sourcing"
18. "Conflict resolution for CRDTs"
19. "Incremental migration from monolith to microservices"
20. "Zero-downtime schema migrations"

---

### Phase 5: Data Collection Protocol

**For each test case:**

1. **Generate proposals with all 3 variants:**
   ```bash
   # Baseline (current identical prompts)
   skills/architect/run.sh propose "Test case description" --providers gemini,codex,grok

   # Variant A (architectural styles)
   # Modify providers.py to use specialized prompts
   skills/architect/run.sh propose "Test case description" --providers gemini,codex,grok

   # Variant B (reasoning approaches)
   # ... repeat for each variant
   ```

2. **Measure diversity:**
   ```python
   # Run analysis script
   python scripts/analyze-diversity.py --run-id <run-id> --output results/diversity-{run-id}.json
   ```

3. **Run tournament:**
   ```bash
   skills/architect/run.sh rank <run-id>
   ```

4. **Collect human rankings:**
   - Present proposals blind (remove provider names)
   - Ask human to rank 1-3
   - Record reasoning/notes

5. **Calculate scores:**
   ```python
   python scripts/calculate-experiment-scores.py --run-id <run-id> --human-ranks 1,3,2
   ```

---

## Implementation Plan

### Week 1: Infrastructure (8 hours)

- [ ] Create `skills/architect/lib/prompts.py` module
- [ ] Implement 3 prompt variants (A, B, C) × 3 providers
- [ ] Add `--prompt-variant` flag to run.sh
- [ ] Create `scripts/analyze-diversity.py`:
  - Embedding distance calculation
  - TF-IDF overlap analysis
  - Pattern extraction
  - Structural comparison
- [ ] Create `scripts/calculate-experiment-scores.py`:
  - Aggregate diversity metrics
  - Aggregate quality metrics
  - Generate comparison tables

### Week 2: Data Collection (12 hours)

- [ ] Run 20 test cases × 4 configurations (baseline + 3 variants) = 80 runs
  - Automated: 60 runs (simple + medium)
  - Manual oversight: 20 runs (complex)
- [ ] Collect diversity metrics for all 80 runs
- [ ] Run tournaments for all 80 runs
- [ ] Human ranking for all 20 test cases (4 proposals each = 80 rankings)

### Week 3: Analysis (8 hours)

- [ ] Generate diversity comparison report
- [ ] Generate quality comparison report
- [ ] Statistical significance tests (t-tests, ANOVA)
- [ ] Visualizations (scatter plots, heatmaps)
- [ ] Write final research report

---

## Expected Outcomes

### Hypothesis 1: Architectural Style Specialization (Variant A) wins

**Predicted Results:**
- **Highest diversity scores** (0.65-0.75 embedding distance vs 0.40-0.50 baseline)
- **Best pattern coverage** (15-20 unique patterns vs 8-12 baseline)
- **Comparable quality** (tournament ranking similar to baseline)

**Reasoning:** Fundamentally different architectural paradigms (reactive vs functional vs pragmatic) naturally explore different solution spaces.

### Hypothesis 2: Trade-off between Diversity and Quality

**Predicted Results:**
- Variant A: High diversity, medium-high quality
- Variant B: Medium diversity, high quality (better reasoning)
- Variant C: Low diversity, highest quality (complementary details)

**Sweet spot:** Variant A for proposal generation, switch to uniform detailed prompts for refinement.

### Hypothesis 3: Test Case Complexity Moderates Effect

**Predicted Results:**
- Simple cases: Specialization has less impact (problems have obvious solutions)
- Medium cases: **Largest impact** (multiple valid approaches)
- Complex cases: Specialization risks incorrect assumptions (need more constraint grounding)

---

## Risk Mitigation

**Risk 1: Specialization reduces quality**
- **Mitigation:** Use constraints file to ground all variants in project requirements
- **Fallback:** Keep baseline prompts as default, use specialized variants only for high-stakes decisions

**Risk 2: Diversity doesn't translate to value**
- **Mitigation:** Track "valuable diversity" (proposals that surface different trade-offs) vs "noise diversity" (proposals that vary arbitrarily)
- **Metric:** Ask humans: "Did seeing multiple proposals change your decision?"

**Risk 3: Implementation complexity**
- **Mitigation:** Keep variant selection as simple CLI flag `--prompt-variant A|B|C|baseline`
- **Progressive disclosure:** Start with Variant A only, add B/C if beneficial

**Risk 4: Cost explosion**
- **Mitigation:** Use diversity metrics to prune redundant proposals (if 2 proposals have <0.30 distance, drop one)
- **Budget:** 80 runs × 3 proposals × $0.50/proposal ≈ $120 for full experiment

---

## Success Criteria

**Minimum success:** Variant A improves diversity by 30%+ over baseline without sacrificing quality

**Target success:** Variant A improves diversity by 50%+ AND maintains or improves quality (tournament correlation with humans ≥ 0.85)

**Stretch success:** Discover optimal variant per test case complexity (simple→baseline, medium→Variant A, complex→Variant C)

---

## Next Steps

1. **Immediate (today):**
   - Review this design with stakeholder
   - Prioritize one variant to implement first (recommend: Variant A)

2. **Week 1 (implementation):**
   - Implement Variant A prompts in `skills/architect/lib/prompts.py`
   - Test on 3 sample cases manually
   - Build diversity analysis tooling

3. **Week 2 (pilot):**
   - Run 5 test cases (1 simple, 3 medium, 1 complex)
   - Analyze results
   - Decide: continue with full 20-case study or pivot?

---

## Appendix A: Diversity Analysis Script

```python
#!/usr/bin/env python3
"""
Analyze diversity of proposals from a run.

Usage:
    python scripts/analyze-diversity.py --run-id <run-id>
"""

import json
import numpy as np
import openai
from pathlib import Path
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_distances
from collections import Counter

def load_proposals(run_id: str) -> list[dict]:
    """Load proposals from .architect/review-runs/<run-id>/"""
    run_dir = Path(f".architect/review-runs/{run_id}")
    run_file = run_dir / "run.json"

    with open(run_file) as f:
        data = json.load(f)

    return data["proposals"]

def calculate_embedding_diversity(proposals: list[dict]) -> dict:
    """Calculate semantic diversity using embeddings."""
    # Get embeddings
    texts = [p["content"] for p in proposals]
    embeddings = [
        openai.Embedding.create(
            input=text,
            model="text-embedding-3-large"
        )["data"][0]["embedding"]
        for text in texts
    ]

    # Calculate pairwise distances
    embeddings_array = np.array(embeddings)
    distances = cosine_distances(embeddings_array)

    # Get upper triangle (avoid diagonal and duplicates)
    upper_tri_indices = np.triu_indices_from(distances, k=1)
    pairwise_distances = distances[upper_tri_indices]

    return {
        "mean_distance": float(np.mean(pairwise_distances)),
        "std_distance": float(np.std(pairwise_distances)),
        "min_distance": float(np.min(pairwise_distances)),
        "max_distance": float(np.max(pairwise_distances)),
        "pairwise_distances": pairwise_distances.tolist()
    }

def calculate_vocabulary_diversity(proposals: list[dict]) -> dict:
    """Calculate vocabulary diversity using TF-IDF."""
    texts = [p["content"] for p in proposals]

    # Extract technical terms
    vectorizer = TfidfVectorizer(
        max_features=100,
        stop_words='english',
        ngram_range=(1, 2)  # Unigrams and bigrams
    )
    tfidf_matrix = vectorizer.fit_transform(texts)
    feature_names = vectorizer.get_feature_names_out()

    # Get top 20 terms per proposal
    top_terms_by_proposal = []
    for doc_idx in range(len(proposals)):
        doc_scores = tfidf_matrix[doc_idx].toarray()[0]
        top_indices = doc_scores.argsort()[-20:][::-1]
        top_terms = [feature_names[i] for i in top_indices if doc_scores[i] > 0]
        top_terms_by_proposal.append(top_terms)

    # Calculate Jaccard distances
    term_sets = [set(terms) for terms in top_terms_by_proposal]

    jaccard_distances = []
    for i in range(len(term_sets)):
        for j in range(i + 1, len(term_sets)):
            intersection = len(term_sets[i] & term_sets[j])
            union = len(term_sets[i] | term_sets[j])
            jaccard_dist = 1 - (intersection / union if union > 0 else 0)
            jaccard_distances.append(jaccard_dist)

    # Count unique terms across all proposals
    all_terms = set()
    for terms in top_terms_by_proposal:
        all_terms.update(terms)

    return {
        "unique_terms_count": len(all_terms),
        "mean_jaccard_distance": float(np.mean(jaccard_distances)) if jaccard_distances else 0.0,
        "top_terms_by_proposal": top_terms_by_proposal,
        "jaccard_distances": jaccard_distances
    }

def extract_architectural_patterns(proposal_text: str) -> set[str]:
    """Extract mentions of architectural patterns (simple keyword matching)."""
    patterns = {
        # Event-driven
        "event sourcing", "cqrs", "event streaming", "message queue", "pub/sub",
        "saga", "event bus", "reactive", "observer",

        # Functional
        "monad", "functor", "algebraic effects", "pipeline", "compose",
        "pure function", "immutable", "referential transparency",

        # Object-oriented
        "repository", "service layer", "factory", "strategy", "command",
        "decorator", "adapter", "facade",

        # Architectural styles
        "microservices", "monolith", "hexagonal", "clean architecture",
        "layered architecture", "onion architecture", "mvc", "mvvm",

        # Data patterns
        "active record", "data mapper", "unit of work", "identity map",

        # Infrastructure
        "api gateway", "service mesh", "circuit breaker", "retry", "cache"
    }

    text_lower = proposal_text.lower()
    found = {p for p in patterns if p in text_lower}
    return found

def calculate_pattern_coverage(proposals: list[dict]) -> dict:
    """Calculate architectural pattern coverage."""
    patterns_by_proposal = []

    for proposal in proposals:
        patterns = extract_architectural_patterns(proposal["content"])
        patterns_by_proposal.append({
            "proposal_id": proposal["id"],
            "provider": proposal["provider"],
            "patterns": list(patterns)
        })

    # Total unique patterns mentioned across all proposals
    all_patterns = set()
    for p in patterns_by_proposal:
        all_patterns.update(p["patterns"])

    # Patterns mentioned in ALL proposals (overlap)
    if patterns_by_proposal:
        common_patterns = set(patterns_by_proposal[0]["patterns"])
        for p in patterns_by_proposal[1:]:
            common_patterns &= set(p["patterns"])
    else:
        common_patterns = set()

    return {
        "total_unique_patterns": len(all_patterns),
        "common_patterns": list(common_patterns),
        "patterns_by_proposal": patterns_by_proposal
    }

def main():
    import argparse

    parser = argparse.ArgumentParser(description="Analyze proposal diversity")
    parser.add_argument("--run-id", required=True, help="Review run ID")
    parser.add_argument("--output", required=True, help="Output JSON file")
    args = parser.parse_args()

    # Load proposals
    proposals = load_proposals(args.run_id)
    print(f"Loaded {len(proposals)} proposals")

    # Calculate diversity metrics
    print("Calculating embedding diversity...")
    embedding_diversity = calculate_embedding_diversity(proposals)

    print("Calculating vocabulary diversity...")
    vocabulary_diversity = calculate_vocabulary_diversity(proposals)

    print("Calculating pattern coverage...")
    pattern_coverage = calculate_pattern_coverage(proposals)

    # Aggregate results
    results = {
        "run_id": args.run_id,
        "proposal_count": len(proposals),
        "embedding_diversity": embedding_diversity,
        "vocabulary_diversity": vocabulary_diversity,
        "pattern_coverage": pattern_coverage
    }

    # Save to file
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(output_path, 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n✓ Results saved to {output_path}")
    print(f"\nSummary:")
    print(f"  Embedding diversity: {embedding_diversity['mean_distance']:.3f} ± {embedding_diversity['std_distance']:.3f}")
    print(f"  Vocabulary diversity (Jaccard): {vocabulary_diversity['mean_jaccard_distance']:.3f}")
    print(f"  Pattern coverage: {pattern_coverage['total_unique_patterns']} unique patterns")

if __name__ == "__main__":
    main()
```

---

## Appendix B: Example Specialized Prompt (Full Implementation)

```python
# In skills/architect/lib/prompts.py

def get_gemini_prompt_variant_a(description: str, constraints: dict) -> str:
    """Variant A: Reactive/Event-Driven specialization for Gemini."""

    role = f"""You are a reactive systems architect specializing in event-driven architectures.

Project context: {constraints['context']}

Focus areas:
- Event flows and message passing
- Reactive state management (observers, subscriptions)
- Immutability and data flow
- Temporal aspects (event ordering, replay)
- Backpressure and flow control"""

    architectural_bias = """
STRONGLY PREFER:
- Event sourcing over CRUD
- Reactive streams over imperative loops
- Immutable data structures
- Declarative transformations
- Observable patterns

THINK IN TERMS OF:
- Who publishes events? Who subscribes?
- How does state evolve from events?
- What happens if events arrive out of order?
- How do we handle backpressure?
"""

    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<architectural_bias>
{architectural_bias}
</architectural_bias>

<task>
Generate an event-driven implementation proposal for:

{description}

REQUIRED sections:
1. Event flows (what events, who produces/consumes)
2. State transitions (how events change state)
3. Components (event handlers, stores, projections)
4. Data model (event schemas, aggregate roots)
5. Trade-offs (consistency, complexity, debugging)

Be explicit about event ordering and failure scenarios.
</task>

<output_format>
Use markdown with clear headings.
Focus on event flows and state transitions.
Be concise - aim for 1-2 pages total.
Use diagrams (ASCII art) to illustrate event flows.
</output_format>
"""

    return prompt
```

