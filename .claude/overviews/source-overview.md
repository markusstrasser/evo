<!-- Generated: 2026-03-28T15:17:35Z | git: 49905601 | model: gemini-3-flash-preview -->

<!-- Generated: 2026-03-28T15:17:28Z | git: 49905601 | model: gemini-3-flash-preview -->

<!-- INDEX
[MODULE] kernel.ops — The 3 atomic operations (create, place, update)
[MODULE] kernel.transaction — Transaction pipeline: normalize, validate, apply, derive
[MODULE] kernel.intent — High-level intent registration and compilation to operations
[MODULE] kernel.db — Canonical tree state and automatic index derivation
[MODULE] shell.view-state — Managed ephemeral UI state (selection, focus, buffer)
[MODULE] shell.executor — Side-effecting runtime for applying intents to state
[MODULE] shell.storage — Logseq-style markdown serialization and filesystem sync
[FLOW] User Event → Intent → Operations → DB Update → Render — Primary state loop
[CLASS] Anchor — Relational positioning algebra (:first, :last, :before, :after)
[CLASS] Node — Canonical tree element with type and properties
[LIB] replicant — Pure-function DOM rendering engine used for UI
[SCRIPT] generate-overview.sh — Automated architectural documentation generator
-->

### [MODULE] kernel.ops
The "Three-Op Kernel" core. Every structural change in the system is expressed as a sequence of these three atomic, pure functions:
*   `create-node`: Adds a node shell to the database (idempotent).
*   `place`: Moves a node to a relative position under a parent, handling removal from previous parents.
*   `update-node`: Performs a recursive deep-merge of properties into an existing node.

### [MODULE] kernel.transaction
The execution pipeline that ensures atomicity and integrity. It processes operation lists through four stages:
*   **Normalization**: Filters no-ops (like moving a block to its current position) and enriches nodes with timestamps.
*   **Validation**: Enforces schema and graph invariants (no cycles, valid parents, existing nodes).
*   **Application**: Executes the operations against the database.
*   **Derivation**: Triggers automatic re-computation of all tree indexes.

### [MODULE] kernel.intent
The semantic translation layer. High-level user actions (e.g., "indent", "smart-split") are registered here with Malli specs and handlers. These handlers compile high-level intents into the atomic kernel operations. It includes an audit system to ensure intents cite the Functional Requirements (FRs) they implement from `resources/specs.edn`.

### [MODULE] kernel.db
Defines the canonical database shape (`:nodes`, `:children-by-parent`, `:roots`) and the `derive-indexes` system. After every transaction, it recomputes O(1) lookup maps for tree traversal:
*   `:parent-of`, `:index-of`, `:prev-id-of`, `:next-id-of`.
*   `:doc/pre` and `:doc/id-by-pre`: Pre-order traversal sequences for the document tree.
*   Plugin hooks: Allows extensions (like `backlinks-index`) to contribute to the `:derived` map.

### [MODULE] shell.view-state
Manages ephemeral UI state separate from the persistent document graph.
*   `!view-state`: Holds state that triggers re-renders (selection, focus, folding, zoom, current page).
*   `!buffer`: A separate high-velocity atom for keystroke-by-keystroke text updates during editing, avoiding render overhead until blur or explicit commit.

### [MODULE] shell.executor
The bridge between the pure kernel and the side-effecting browser environment. It orchestrates the application of intents by:
1.  Injecting the current editing buffer into the intent.
2.  Dispatching to the kernel and applying resulting state changes.
3.  Synchronizing the browser URL, navigation history, and system clipboard.
4.  Triggering filesystem persistence.

### [MODULE] shell.storage
Handles bidirectional sync between the DB and Logseq-style markdown files via the File System Access API.
*   **Serialization**: Converts the tree into markdown with `property:: value` headers and `- ` bullet hierarchies.
*   **Parsing**: Reconstructs the tree from markdown, handling multiline blocks and indentation levels.
*   **Assets**: Manages binary image storage with content-hash deduplication in an `/assets` subfolder.

### [FLOW] User Event → Intent → Operations → DB Update → Render
1.  **Input**: A DOM event (keydown, click) is captured by `shell.global-keyboard` or `components.block`.
2.  **Intent**: The event is resolved to a semantic intent (e.g., `{:type :indent}`).
3.  **Compilation**: `kernel.intent` compiles the intent into kernel operations (e.g., `{:op :place ...}`).
4.  **Transaction**: `kernel.transaction` validates and applies ops, then derives new indexes.
5.  **State Transition**: `shell.executor` resets the `!db` and updates `!view-state`.
6.  **Re-render**: A watch on the atoms triggers `replicant.dom/render`, updating the UI.

### [CLASS] Anchor
A data-driven algebra for specifying relative positions in a sequence without using absolute indices.
*   Keywords: `:first`, `:last`.
*   Maps: `{:before "id"}`, `{:after "id"}`.
*   Logic: `kernel.position/resolve-insert-index` converts an anchor + current siblings into a concrete integer index.

### [CLASS] Node
The fundamental unit of the graph. Every node is a map:
*   `:type`: A keyword (e.g., `:page`, `:block`, `:embed`).
*   `:props`: A map of arbitrary data (e.g., `:text`, `:title`, `:created-at`).
*   Hierarchy is stored separately in the DB's `:children-by-parent` map.

### [LIB] replicant
A pure-function DOM rendering engine. UI components (in `src/components/`) are pure functions that return Hiccup vectors. Replicant handles efficient DOM patching and provides lifecycle hooks used for cursor management and focus trapping.

### [SCRIPT] generate-overview.sh
A Babashka-powered utility that uses `repomix` to scan the codebase and a LLM to regenerate this architectural documentation whenever significant changes occur, ensuring the overview stays in sync with the implementation.
