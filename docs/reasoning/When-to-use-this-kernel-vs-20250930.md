When to use this kernel vs. “build the domain app from scratch”?

Choose the kernel if:
	•	You need true composition across many surfaces (outliner + canvas + timeline + scene graph) with one undo/redo/op-log and one invariants layer.
	•	You want agent-friendly infrastructure: stable IDs, deterministic ops, auditable traces, and test DSLs that LLMs can learn/execute.
	•	You care about portability & replay: “import → compile intents → emit ops → same laws” across domains.
	•	You expect to reimplement others fast: an agent can mirror a repo’s behavior by writing compilers and derived indexes instead of forking their data model.

Skip the kernel (or use it thinly) if:
	•	You’re shipping one deep product with bespoke realtime constraints where the optimal data model is not a tree (e.g., hardcore DAW with nonlocal scheduling constraints), and you can accept bespoke undo/redo.
	•	The IR mismatch forces contortions (you need edge-rich algebra in the core, not as policy).
	•	You have hard realtime perf budgets that demand in-place, mutable structures (you can still mirror into the kernel asynchronously for audit/agents).
