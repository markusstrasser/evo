# Architectural Summary Prompt

Generate a comprehensive architectural overview of the `src/core/` codebase suitable for intelligent discussion about extending or modifying the system without getting bogged down in low-level implementation details.

## Goal
Create a document that combines:
- High-level concepts and schemas (YES)
- Function signatures with semantic descriptions (YES)
- Threading patterns and data flow (YES)
- ASCII art diagrams (YES)
- Low-level implementation code (NO - only when it clarifies)

## Input
Read all files in `src/core/`:
- `core.db` - database structure and derived indexes
- `core.ops` - core operations
- `core.interpret` - transaction pipeline
- `core.schema` - Malli schemas and contracts
- `core.struct` - structural editing layer (extension example)
- `core.demo` and `core.demo_struct` - usage examples

## Output Format Requirements

### Visual Style
Use Unicode box-drawing characters extensively:
```
═══ for major section separators
─── for subsection separators
┌─ ─┐ for boxes
│ vertical bars for box sides
├─ ─┤ for box dividers
└─ ─┘ for box bottoms
→ for data flow
├─→ for branching flow
╔═══╗ for emphasis boxes
```

### Example Section Format
```
═════════════════════════════════════════════════════════════════════════════
 1. SECTION NAME (module name)
═════════════════════════════════════════════════════════════════════════════

┌─ Subsection ───────────────────────────────────────────────────────────────┐
│                                                                             │
│  Content with structure definitions, showing:                              │
│    • Key data shapes                                                        │
│    • Threading patterns                                                     │
│    • Algorithms                                                             │
│                                                                             │
│  Threading pattern example:                                                │
│    children-by-parent                                                       │
│      ├─→ compute-parent-of   → {:parent-of ...}                            │
│      ├─→ compute-index-of    → {:index-of ...}                             │
│      └─→ compute-siblings    → {:prev-id-of ... :next-id-of ...}           │
└─────────────────────────────────────────────────────────────────────────────┘

API Surface:
  (function-name [args] → ReturnType)
    Description of what it does
    Key behaviors or guarantees
```

### For Data Flow / Pipelines
Use vertical ASCII flow diagrams:
```
                      ┌────────────────────────┐
                      │   Input Data           │
                      └───────────┬────────────┘
                                  │
                   ╔══════════════╧══════════════╗
                   ║      PHASE NAME            ║
                   ╚══════════════╤══════════════╝
                                  │
              (function-name [args] → result)
                                  │
              ┌───────────────────┼────────────────────┐
              │                   │                    │
         sub-step-1          sub-step-2           sub-step-3
              │                   │                    │
              └───────────────────┴────────────────────┤
                                                       │
                                                       ▼
                                              next phase...
```

### Threading Patterns
Always show how data flows through functions:
```
normalize-ops:
  ops ->> (remove-noop-places db)
      ->> merge-adjacent-updates

validate-ops (stateful reduction):
  (reduce (fn [[db issues] op]
            (if (seq (validate-op db op))
              (reduced [db issues])      ; SHORT CIRCUIT
              [(apply-op db op) issues])) ; THREAD FORWARD
          [db []]
          ops)
```

## Sections to Include (in order)

### Header
Start with a title block using box characters:
```
╔══════════════════════════════════════════════════════════════════════════════╗
║                     THREE-OP KERNEL ARCHITECTURE                             ║
║              Tree Database with Transaction Pipeline                         ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

Then a design principles box listing core architectural principles.

### 1. Data Model (core.db)
Show:
- Canonical state structure (the actual Db map shape)
- All 7 derived indexes with explanations
- Threading patterns in `derive-indexes` (show how it threads through compute-* functions)
- API surface: `empty-db`, `derive-indexes`, `validate`
- All invariants enforced by validate

Include:
- Example data shapes
- The nested threading in `compute-traversal`
- Why each derived index exists

### 2. Core Operations (core.ops)
For each operation (create-node, place, update-node):
- Full signature with types
- Detailed semantics
- Algorithm description (place has 3 phases - make this very clear)
- Anchor resolution logic (all 5 anchor types)
- Threading through helpers
- Idempotency and safety guarantees

Special focus:
- The place operation's remove→resolve→insert algorithm
- Why anchors resolve AFTER removal
- Deep-merge behavior in update-node

### 3. Transaction Pipeline (core.interpret)
- Large vertical ASCII diagram showing all 4 phases
- Each phase with function signature and purpose
- Threading patterns (especially the reduce in validate-ops)
- Short-circuit behavior on first error
- How state threads through the pipeline
- API surface: `interpret`, `derive-db`, `validate`

Show the actual reduction pattern used in validate-ops with THREAD FORWARD and SHORT CIRCUIT labeled.

### 4. Validation Semantics
- Issue map structure
- Per-operation validation rules (what each op checks)
- All issue types (`:duplicate-create`, `:node-not-found`, `:cycle-detected`, etc.)
- Cycle detection algorithm (walk up parent chain)
- Noop detection logic for :place

Use a box layout showing each operation type and its validation rules.

### 5. Schema Contracts (core.schema - Malli)
Show schemas as Malli definitions:
- Core type definitions: Id, Parent, At (with all 5 anchor variants), Node, Db
- Operation schemas: Op-Create, Op-Place, Op-Update, Op
- Result schemas: Issue, InterpretResult, ValidateResult
- Validation API: `valid-op?`, `valid-db?`, `explain-op`, etc.
- Transformers: api-transformer, strict-transformer
- Generators: `generate-op`, `generate-transaction`, etc.

### 6. Structural Editing Layer (core.struct)
Emphasize this is an EXTENSION EXAMPLE showing how to build on top.

Show:
- Intent map structure
- `compile-intent` multimethod pattern
- All three example intents (delete, indent, outdent) with:
  - What they read from derived indexes
  - What ops they emit
  - Safety guarantees (empty vector for impossible ops)
- The threading pattern: `intents ->> (mapcat compile-intent) ->> vec`

Include the design pattern and extension points.

### 7. Usage Patterns
Two code examples in boxes:
1. Basic workflow (empty-db → interpret → check issues → use result)
2. Extension pattern (how to write your own feature ops like struct does)

### 8. Key Invariants & Guarantees
Bullet list with checkmarks (✓) of all guarantees:
- Operations are pure
- Database is immutable
- Derived indexes always fresh
- etc.

### 9. Performance Characteristics
Table or list showing Big-O for:
- derive-indexes
- Each core operation
- would-create-cycle?
- validate-ops
- interpret (full pipeline)

Explain what variables mean (n, m, k, h, d).

## Style Guidelines

### Typography
- Use `═` for major section dividers (section 1, 2, 3...)
- Use `─` for subsection dividers (boxes within sections)
- Use `•` for bullet points
- Use `✓` for guarantees/invariants
- Use `→` for simple data flow
- Use `├─→` for branching/parallel data flow
- Use `▼` for vertical continuation

### Code Formatting
- Show function signatures as: `(function-name [arg1 arg2] → ReturnType)`
- Show threading patterns explicitly with `->>` or `reduce` or `letfn`
- Include comments in threading to explain what's happening
- Mark important behaviors with labels like `; THREAD FORWARD`, `; SHORT CIRCUIT`

### Layout
- Keep lines to ~78 characters for boxes
- Align box borders carefully
- Use indentation to show nesting (2 or 4 spaces)
- Group related information in boxes
- Use whitespace generously between sections

### Content Depth
- Function signatures: YES
- Semantic descriptions: YES
- Algorithm descriptions: YES (especially place operation)
- Data shape examples: YES
- Threading patterns: YES (critical)
- Line-by-line code: NO (unless it's a key pattern like reduce)
- Helper function implementations: NO (just mention they exist)

### What to Emphasize
1. The 3 core operations and their semantics
2. The 4-phase pipeline (normalize → validate → apply → derive)
3. Threading patterns (especially in derive-indexes and validate-ops)
4. The place operation's 3-phase algorithm
5. How derived indexes enable extensions
6. The extension pattern (core.struct as example)
7. Anchor resolution logic
8. Validation rules per operation
9. Short-circuit on error

### What to De-emphasize
- Implementation details of helpers
- Exact line numbers or file locations
- Error messages verbatim
- Test code
- Demo code (unless showing usage pattern)

## Purpose
This summary should enable an intelligent agent or human to:
- Understand the complete architecture without reading source
- Propose new features or extensions confidently
- Discuss architectural changes with full context
- Understand data flow and threading patterns
- See extension points clearly
- Reason about correctness and performance
- Implement new features by following established patterns

The summary should be:
- Detailed enough for architecture discussions
- High-level enough to avoid implementation minutiae
- Visual enough to grasp structure quickly
- Complete enough to avoid needing source code for design work

## Critical Success Factors
The output MUST:
1. Show all threading patterns explicitly
2. Include the full 4-phase pipeline diagram
3. Document all 3 operations with full semantics
4. Show the place operation's 3-phase algorithm
5. List all 7 derived indexes with purposes
6. Show how core.struct extends the kernel (design pattern)
7. Use consistent box-drawing characters throughout
8. Be formatted as a single markdown code block for easy copying