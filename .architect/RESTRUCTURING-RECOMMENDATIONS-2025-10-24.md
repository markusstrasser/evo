Of course. Here is a pragmatic analysis and recommendation for restructuring your architectural documentation system.

### 1. Recommended Directory Structure

This structure prioritizes a clear distinction between the **process** (`investigations/`) and the **outcomes** (`adr/`, `specs/`). It's designed to be easily navigated by both you and an LLM, with a clear chronological flow.

```
.architect/
├── README.md               # (New) A brief guide to this directory's structure and purpose.
├── project-constraints.md  # (Keep) Guiding principles for all architectural work.
|
├── adr/                    # (Keep) Formal, numbered Architectural Decision Records. The final source of truth.
|   └── ADR-001-some-decision.md
|
├── investigations/         # The core workspace. Each sub-directory is a self-contained decision-making process.
│   └── 2025-10-17-skill-vs-mcp/
│       ├── README.md       # The initial question, context, and final summary/rationale.
│       ├── proposals/      # Raw proposals from each LLM.
│       │   ├── gemini.md
│       │   └── codex.md
│       └── ranking.json    # (Optional) Structured data from ranking/tournaments.
|
├── research/               # Exploratory work, analysis, and background material not tied to a specific decision.
│   ├── provider-specialization-notes.md
│   └── component-systems-analysis.md
|
├── experiments/            # (Keep) Results from hands-on coding spikes or prototypes.
│   └── 2025-10-14-anki-refactor-summary.md
|
└── archive/                # (Keep) Deprecated ADRs, abandoned investigations, and old research.
```

### 2. Minimal Taxonomy

Your workflow can be simplified to four essential activities. This taxonomy maps directly to the recommended directory structure.

1.  **Investigation:** The process of exploring a specific architectural question. This involves generating proposals, ranking them, and synthesizing a conclusion. This is the "why" and "how" we decided.
    - **Artifacts:** Problem statements, LLM proposals, ranking data, summaries.
    - **Location:** `investigations/{datestamp-slug}/`

2.  **Decision:** The formal, final outcome of an investigation. This is the "what" we decided.
    - **Artifacts:** Architectural Decision Records (ADRs).
    - **Location:** `adr/`

3.  **Research:** Open-ended exploration of topics, technologies, or patterns not yet tied to a concrete decision. This builds foundational knowledge.
    - **Artifacts:** Analysis documents, literature reviews, technology comparisons.
    - **Location:** `research/`

4.  **Experimentation:** Hands-on validation of an idea through code. This proves feasibility.
    - **Artifacts:** Code spike summaries, performance metrics, PoC results.
    - **Location:** `experiments/`

### 3. Naming Conventions

Simple, consistent naming is critical for LLM navigation and chronological understanding.

-   **Directories (Investigations, Experiments):** Use `YYYY-MM-DD-kebab-case-description`. The date provides chronological order, and the slug provides context.
    -   `investigations/2025-10-17-architect-skill-vs-mcp`
    -   `experiments/2025-10-09-anki-refactor-poc`

-   **ADRs:** Continue the existing convention. It's a standard that works well.
    -   `adr/ADR-001-structural-edits-as-lowering.md`

-   **Research Files:** Use descriptive kebab-case names. Dates are less critical here unless the research is time-sensitive.
    -   `research/wasm-component-model-analysis.md`

### 4. Git Strategy

For a solo developer, the history of thought is as valuable as the final decision. Therefore, almost everything should be committed.

-   **Commit:**
    -   `adr/`: The history of formal decisions is critical.
    -   `investigations/`: The entire directory. This is the audit trail for *why* a decision was made. It's invaluable for future context.
    -   `research/`, `experiments/`: These are valuable assets that inform future work.
    -   `project-constraints.md`: This is a core project document.

-   **Gitignore:**
    -   Only ignore transient, machine-generated files that are not part of the decision record.
    -   Example: `/.architect/temp/`, `/.architect/logs/`, `*.session.jsonl`.
    -   **Do not ignore** the `investigations/` directories (previously `review-runs`). They are the historical record.

### 5. Integration with Skills

The `architect` skill should be updated to work with the new, streamlined structure.

1.  **`architect propose "question"`:**
    -   Creates a new directory: `investigations/YYYY-MM-DD-slug/`.
    -   Creates `investigations/YYYY-MM-DD-slug/README.md` with the initial question and context.
    -   Saves LLM outputs to `investigations/YYYY-MM-DD-slug/proposals/{provider}.md`.

2.  **`architect rank {investigation-id}`:**
    -   Reads from the `proposals/` subdirectory.
    -   Writes its output to `investigations/YYYY-MM-DD-slug/ranking.json`.

3.  **`architect decide {investigation-id}`:**
    -   Reads the proposals and ranking data.
    -   Appends a summary and rationale to `investigations/YYYY-MM-DD-slug/README.md`.
    -   Upon approval, it generates a new, numbered file in `adr/`.

This workflow creates a clean, traceable, and self-contained record for every architectural decision.

### 6. Cleanup Actions

Here are the specific commands to migrate your current structure to the recommended one.

```bash
# 1. Create the new top-level directories
mkdir -p .architect/investigations .architect/research .architect/archive

# 2. Move ADRs (already well-structured)
# No action needed, adr/ is kept as is.

# 3. Consolidate all investigation-related work into the new structure.
# This is the main cleanup step. We'll create one investigation for the "skill-vs-mcp" decision.
mkdir -p .architect/investigations/2025-10-17-skill-vs-mcp/proposals
mv .architect/review-runs/fa9f9a58-03bf-49f5-bb4a-abd6783cd52c .architect/investigations/2025-10-17-skill-vs-mcp/raw-review-run # (For reference)
echo "# Investigation: Should architect-mcp become an Agent Skill?" > .architect/investigations/2025-10-17-skill-vs-mcp/README.md
cat .architect/architect-mcp-skill-decision.md >> .architect/investigations/2025-10-17-skill-vs-mcp/README.md
rm .architect/architect-mcp-skill-decision.md

# 4. Consolidate background materials and analysis into research/
mv .architect/background/* .architect/research/
mv .architect/analysis/* .architect/research/
mv .architect/research-directions-summary.md .architect/research/
rmdir .architect/background .architect/analysis

# 5. Move specs and proposals to archive for now, to be sorted later if needed.
mv .architect/specs .architect/archive/
mv .architect/proposals .architect/archive/

# 6. Move ambiguous results/reports to archive.
mv .architect/reports .architect/archive/
mv .architect/results .architect/archive/

# 7. Clean up old review-runs (assuming they are superseded or can be archived)
mv .architect/review-runs .architect/archive/old-review-runs

# 8. Create a new, clarifying README for the root directory.
echo "# The Architect System\n\nThis directory contains the architectural decision-making process and records for this project.\n\n- **/adr**: Final, approved Architectural Decision Records.\n- **/investigations**: The process (proposals, ranking, rationale) leading to a decision.\n- **/research**: Background research and analysis.\n- **/experiments**: Code spike results.\n- **/archive**: Old or deprecated materials." > .architect/README.md

```
