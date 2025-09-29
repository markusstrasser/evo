# Proposal 18 · Intent Parameter Stack Models (Logseq Domain Study)

**Context**
To serve MLIR-style clients (Roam Research, Obsidian, Figma, VR editors), we need concrete user workflows. Logseq’s README captures outliner-centric flows.

**Current status**
Logseq README isn’t mirrored under `/Users/alien/Projects`; once it is, extract:
- Daily journal creation / block manipulation.
- Property editing & metadata tagging.
- Query blocks and transclusion patterns.

**Plan**
1. Summarize each workflow as a sequence of kernel primitive ops (`:insert`, `:move`, `:add-ref`, `:update-node`).
2. Identify missing sugar ops or planner macros to cover gaps (e.g., “indent with carry”, “link to page”).
3. Store scenarios in `docs/mlir_ui_domains.md` and feed them into metamorphic pattern tests (Proposal 6).

**Trade-offs**
- Requires periodic sync with evolving product docs (Logseq, Figma, game editors) to stay relevant.
- Overfitting to a single product risks ignoring other domains; keep scenario set diverse (note-taking, design canvas, game level editor).
