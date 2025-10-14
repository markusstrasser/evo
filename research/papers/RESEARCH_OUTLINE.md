# Research Outline: MCP-Based Review Process Tooling

## Executive Summary

**Goal**: Find state-of-the-art approaches for AI-native software review workflows (ideas → proposals → specs → ADRs) that can inform MCP tooling design.

**Method**: Deploy deep research agents to search recent papers (2023-2025), extract patterns, and synthesize actionable insights.

**Timeline**: 2-3 hours parallel execution

---

## Research Questions (8 Areas)

### 1. AI-Native Development Workflows
**Core Question**: How are AI systems being integrated into software development pipelines end-to-end?

**Paper Search Queries**:
- "LLM-assisted software development workflow 2024"
- "AI-native development environment architecture"
- "Large language model code review automation"
- "Human-AI collaboration software engineering 2024"
- "Prompt-driven software design patterns"

**What to Extract**:
- Workflow architectures (tools, stages, data flow)
- Human-in-the-loop patterns
- Quality gates and validation approaches
- Provenance tracking mechanisms
- Success metrics and failure modes

---

### 2. Multi-Agent Software Engineering Systems
**Core Question**: How do multiple AI agents collaborate on software tasks?

**Paper Search Queries**:
- "Multi-agent systems software engineering 2024"
- "LLM agent collaboration architecture"
- "Hierarchical LLM agents software development"
- "Agent orchestration patterns code generation"
- "Swarm intelligence software design"

**What to Extract**:
- Agent coordination protocols
- Task decomposition strategies
- Conflict resolution mechanisms
- Communication patterns between agents
- Composition vs orchestration tradeoffs

---

### 3. Software Architecture Decision Support
**Core Question**: How are AI systems helping with architectural decision-making?

**Paper Search Queries**:
- "AI architectural decision records generation"
- "LLM software architecture recommendation"
- "Automated design pattern suggestion 2024"
- "Architecture evaluation using language models"
- "Tradeoff analysis AI software design"

**What to Extract**:
- Decision support frameworks
- Evaluation criteria encoding
- Alternative generation approaches
- Tradeoff visualization methods
- Decision record formats

---

### 4. Incremental Specification Refinement
**Core Question**: How do systems iteratively refine specifications with AI assistance?

**Paper Search Queries**:
- "Incremental specification refinement LLM"
- "Interactive program synthesis language models"
- "Specification by example AI 2024"
- "Type-driven development language models"
- "Test-driven specification generation"

**What to Extract**:
- Refinement loop architectures
- Convergence criteria
- User feedback integration
- Quality gate checkpoints
- Anti-patterns (infinite loops, drift)

---

### 5. Provenance and Lineage Tracking
**Core Question**: How do AI systems track data/decision provenance?

**Paper Search Queries**:
- "Provenance tracking machine learning systems"
- "Data lineage AI development pipeline"
- "Auditable AI decision systems 2024"
- "Explainable AI provenance graphs"
- "Version control AI-generated artifacts"

**What to Extract**:
- Provenance graph structures
- URI/identifier schemes
- Immutable audit logs
- Diff and merge strategies
- Query patterns for lineage

---

### 6. Knowledge Management for Software Development
**Core Question**: How are AI systems managing software knowledge (patterns, decisions, context)?

**Paper Search Queries**:
- "Knowledge graphs software engineering LLM"
- "Organizational memory software development AI"
- "Context management large language models code"
- "Software pattern mining language models 2024"
- "Semantic search code documentation"

**What to Extract**:
- Knowledge representation formats
- Retrieval strategies (RAG patterns)
- Context window management
- Incremental knowledge updates
- Cross-project pattern transfer

---

### 7. Tool Composition and Extensibility
**Core Question**: How are modular AI tool systems designed for extensibility?

**Paper Search Queries**:
- "Composable AI tools software development"
- "Plugin architecture language model systems"
- "Function calling LLM tool composition 2024"
- "MCP model context protocol research"
- "Modular AI agent frameworks"

**What to Extract**:
- Composition patterns (mounting, proxying, chaining)
- Interface design principles
- Discovery mechanisms
- Error handling across boundaries
- Performance considerations

---

### 8. Quality Gates and Validation
**Core Question**: How are AI systems validated in development workflows?

**Paper Search Queries**:
- "Validation AI-generated code quality gates"
- "LLM output quality assessment 2024"
- "Test generation language models verification"
- "Formal methods AI code synthesis"
- "Runtime monitoring AI assistants"

**What to Extract**:
- Validation criteria (correctness, style, maintainability)
- Automated test generation approaches
- Human review integration
- Statistical quality models
- Red-teaming strategies

---

## Execution Protocol

### Phase 1: Parallel Paper Search (45 min)

**For Each Research Question**:

1. **Deploy Search Agent**:
   ```bash
   # Use exa-code MCP for academic search
   # Query: [research question] + "arxiv OR proceedings 2023..2025"
   ```

2. **Extract Top Papers**:
   - Get 5-10 most relevant papers per query
   - Prioritize: 2024-2025 papers, high citation count, conference papers
   - Include: arXiv preprints, ICSE, FSE, ASE, NeurIPS, ICLR

3. **Generate Summary**:
   - Key contribution (1 sentence)
   - Relevant findings (3-5 bullet points)
   - Architecture diagram (if applicable)
   - Code/data availability
   - Limitations and future work

**Output Structure**:
```
research/papers-mcp-tooling/
├── 1-ai-native-workflows/
│   ├── paper-001-summary.md
│   ├── paper-002-summary.md
│   └── synthesis.md
├── 2-multi-agent-systems/
│   └── ...
└── index.md (overview)
```

---

### Phase 2: Cross-Area Synthesis (30 min)

**Synthesis Agent Tasks**:

1. **Pattern Extraction**:
   - Common architectures across papers
   - Recurring primitives (graphs, logs, URIs, prompts)
   - Successful composition patterns
   - Known failure modes

2. **Contradiction Analysis**:
   - Where do papers disagree?
   - Which approaches are context-dependent?
   - What tradeoffs are unavoidable?

3. **Gap Analysis**:
   - What's missing from research?
   - What's specific to our use case (solo dev, REPL-driven, Clojure)?
   - Where do we need novel approaches?

**Output**: `research/papers-mcp-tooling/SYNTHESIS.md`

---

### Phase 3: Actionable Design (30 min)

**Design Agent Task**:

Using synthesis + our MCP context (Gemini/Codex proposals):

1. **Rank Patterns**:
   - Which patterns fit our constraints best?
   - What's the 80/20 for solo dev + AI agents?
   - What can be prototyped quickly?

2. **Concrete Architecture**:
   - Updated MCP server design
   - Tool signatures with paper-inspired improvements
   - Resource URI scheme (informed by provenance research)
   - Quality gates (informed by validation research)

3. **Implementation Roadmap**:
   - Phase 1: Minimal viable MCP (1 day)
   - Phase 2: Add quality gates (1 day)
   - Phase 3: Multi-stage orchestration (2 days)
   - Phase 4: Provenance visualization (1 day)

**Output**: `research/papers-mcp-tooling/DESIGN.md`

---

### Phase 4: Prototype Specification (30 min)

**Spec Agent Task**:

Create concrete specs for Phase 1 (minimal viable MCP):

1. **Server Interface**:
   ```python
   # review_server.py
   from fastmcp import FastMCP

   # Tools (with signatures, types, docstrings)
   # Resources (URI scheme, data formats)
   # Prompts (templates, examples)
   ```

2. **Data Schemas**:
   ```python
   # schemas.py (Pydantic models)
   class Idea: ...
   class Proposal: ...
   class Spec: ...
   class ADR: ...
   class ReviewRun: ...
   ```

3. **Test Cases**:
   ```python
   # test_review_server.py
   async def test_create_run(): ...
   async def test_generate_proposals(): ...
   async def test_provenance_tracking(): ...
   ```

**Output**: `mcp/review/SPEC.md`

---

## Agent Deployment Plan

### Agents Needed

1. **Paper Search Agents** (8 parallel):
   - One per research question
   - Uses: Exa MCP for academic search
   - Runtime: ~30-45 min each
   - Output: Paper summaries

2. **Synthesis Agent** (1):
   - Consumes all paper summaries
   - Uses: Codex high reasoning for cross-cutting analysis
   - Runtime: ~30 min
   - Output: Pattern synthesis, gap analysis

3. **Design Agent** (1):
   - Consumes synthesis + existing MCP proposals
   - Uses: Codex high reasoning for architecture
   - Runtime: ~30 min
   - Output: Concrete MCP design

4. **Spec Agent** (1):
   - Consumes design
   - Uses: Codex for detailed specification
   - Runtime: ~30 min
   - Output: Implementation spec with types/tests

### Execution Script

```bash
#!/bin/bash
# research-mcp-tooling.sh

set -euo pipefail

RESEARCH_DIR="research/papers-mcp-tooling"
mkdir -p "$RESEARCH_DIR"

# Phase 1: Paper Search (parallel)
echo "Phase 1: Launching 8 paper search agents..."

queries=(
  "ai-native-workflows:LLM-assisted software development workflow 2024"
  "multi-agent-systems:Multi-agent systems software engineering 2024"
  "decision-support:AI architectural decision records generation"
  "spec-refinement:Incremental specification refinement LLM"
  "provenance:Provenance tracking machine learning systems"
  "knowledge-mgmt:Knowledge graphs software engineering LLM"
  "tool-composition:Composable AI tools software development"
  "quality-gates:Validation AI-generated code quality gates"
)

for query in "${queries[@]}"; do
  IFS=':' read -r area search_query <<< "$query"

  mkdir -p "$RESEARCH_DIR/$area"

  # Launch agent
  (
    echo "Searching: $search_query"

    # Use Exa MCP to search papers
    echo "Query: $search_query
    Focus on: 2023-2025 papers, arxiv, conferences (ICSE, FSE, ASE, NeurIPS)
    Extract: architecture, patterns, quality gates, tradeoffs
    Format: Markdown with paper summaries" \
    | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - \
    > "$RESEARCH_DIR/$area/papers.md" 2>&1

    echo "✓ Complete: $area"
  ) &
done

# Wait for all searches
wait
echo "✓ Phase 1 complete: 8 paper summaries generated"

# Phase 2: Synthesis
echo "Phase 2: Synthesizing findings..."

cat > /tmp/synthesis-prompt.md << 'EOF'
Analyze all paper summaries and extract:
1. Common architectural patterns
2. Successful composition approaches
3. Known failure modes
4. Gaps in research
5. Recommendations for MCP review tooling

Context:
- Solo developer + AI agents
- REPL-driven Clojure development
- Focus on simplicity over performance
- Need: Ideas → Proposals → Specs → ADRs workflow
EOF

find "$RESEARCH_DIR" -name "papers.md" -exec cat {} \; | \
  cat /tmp/synthesis-prompt.md - | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto - \
  > "$RESEARCH_DIR/SYNTHESIS.md"

echo "✓ Phase 2 complete: Synthesis generated"

# Phase 3: Design
echo "Phase 3: Generating architecture..."

cat research/mcp-tooling-proposals/gemini-2025-10-13.md \
    research/mcp-tooling-proposals/codex-2025-10-13.md \
    "$RESEARCH_DIR/SYNTHESIS.md" | \
  cat - << 'EOF' | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -

Given:
1. Existing MCP proposals (Gemini/Codex)
2. Paper synthesis

Task: Design concrete MCP architecture for review process.
Include:
- Server structure (composition)
- Tool signatures
- Resource URI scheme
- Quality gates
- Implementation roadmap (4 phases)
EOF
  > "$RESEARCH_DIR/DESIGN.md"

echo "✓ Phase 3 complete: Architecture designed"

# Phase 4: Specification
echo "Phase 4: Generating implementation spec..."

cat "$RESEARCH_DIR/DESIGN.md" | \
  cat - << 'EOF' | \
  codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -

Generate Phase 1 implementation spec:
1. Python server code (FastMCP)
2. Pydantic schemas
3. Test cases
4. README

Output: Complete, runnable spec
EOF
  > "mcp/review/SPEC.md"

echo "✓ Phase 4 complete: Spec generated"

# Summary
echo ""
echo "=== RESEARCH COMPLETE ==="
echo "Outputs:"
echo "  - $RESEARCH_DIR/*/papers.md (8 files)"
echo "  - $RESEARCH_DIR/SYNTHESIS.md"
echo "  - $RESEARCH_DIR/DESIGN.md"
echo "  - mcp/review/SPEC.md"
echo ""
echo "Next: Review DESIGN.md and implement Phase 1"
```

---

## Alternative: Use Existing Tournament Eval

Instead of agents generating designs, use the **evo-eval MCP** we just built:

```python
# Compare paper-inspired designs

await compare_multiple(
    items={
        "gemini-resources": read("research/mcp-tooling-proposals/gemini..."),
        "codex-coordinator": read("research/mcp-tooling-proposals/codex..."),
        "paper-synthesis-v1": generated_from_papers_1,
        "paper-synthesis-v2": generated_from_papers_2,
    },
    evaluation_prompt="""
This is for a solo developer using AI agents to manage software review workflow.

Project goals:
- Simplicity first (80/20, not performance)
- REPL-driven development
- Data-oriented (immutable, explicit flow)
- Easy to debug (observable state)
- Composable stages (swap proposals/specs/ADRs independently)

Acceptable tradeoffs:
- More MCP servers over monolith complexity
- Manual triggers over automation loops
- Simple URIs over complex provenance graphs

Red flags:
- Hidden automation (infinite loops)
- Complex abstractions without clear benefit
- Tight coupling between stages
- Over-engineering for solo dev use case

Judge which design best fits these priorities.
""",
    judges=["gpt5-codex", "gemini25-pro"],
    max_rounds=5
)
```

This gives us:
- ✅ Ranked designs with quality metrics
- ✅ Kendall-τ stability check
- ✅ R² coherence validation
- ✅ Explicit tradeoff analysis

---

## Expected Outputs

### Immediate (3 hours)
- 8 paper summary documents
- 1 synthesis report
- 1 concrete architecture design
- 1 implementation spec (Phase 1)

### Deliverable Structure
```
research/papers-mcp-tooling/
├── index.md                      (Overview)
├── SYNTHESIS.md                  (Cross-area patterns)
├── DESIGN.md                     (Recommended architecture)
├── 1-ai-native-workflows/
│   ├── papers.md                 (5-10 paper summaries)
│   └── key-findings.md
├── 2-multi-agent-systems/
│   └── ...
├── 3-decision-support/
│   └── ...
├── 4-spec-refinement/
│   └── ...
├── 5-provenance/
│   └── ...
├── 6-knowledge-mgmt/
│   └── ...
├── 7-tool-composition/
│   └── ...
└── 8-quality-gates/
    └── ...

mcp/review/
└── SPEC.md                       (Phase 1 implementation)
```

### Follow-Up (1 day)
- Prototype Phase 1 MCP server
- Test with actual review workflow
- Iterate based on real usage

---

## Success Metrics

**Research Quality**:
- ✅ 40-80 relevant papers found (5-10 per area)
- ✅ 2024-2025 papers prioritized
- ✅ Clear patterns extracted from synthesis
- ✅ Concrete, actionable design recommendations

**Design Quality**:
- ✅ Fits project constraints (solo dev, REPL-driven, simple)
- ✅ Addresses all pain points (provenance, specs, ADRs, decoupling)
- ✅ Leverages MCP patterns (composition, resources, tools)
- ✅ Has clear implementation roadmap

**Spec Quality**:
- ✅ Runnable code (types, tests, examples)
- ✅ Can be implemented in 1 day
- ✅ Clear integration with existing tools
- ✅ Observable/debuggable architecture
