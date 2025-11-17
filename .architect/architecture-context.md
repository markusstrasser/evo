# Evo Architecture Context for Review

## What is Evo?

Event-sourced UI kernel for building Logseq-like block editors. Pure ClojureScript, REPL-first, framework-agnostic core.

## Current Architecture

### Three-Op Kernel (src/kernel/)

All state changes via three primitives:
```clojure
{:op :create :id "a" :type :block :props {:text "Hello"}}
{:op :place :id "a" :under :doc :at :last}
{:op :update :id "a" :props {:text "World"}}
```

**Transaction Pipeline:**
1. Normalize (filter no-ops, resolve positions)
2. Validate (check schema, cycles, refs)
3. Apply (execute ops)
4. Derive (recompute indexes: parent-of, next-id-of, traversal orders)

**DB Shape:**
```clojure
{:nodes {"id" {:type :block :props {...}}}
 :children-by-parent {:doc ["a" "b"]}
 :roots [:doc :trash :session]
 :derived {:parent-of {"a" :doc}
           :next-id-of {"a" "b"}
           :prev-id-of {"b" "a"}
           :index-of {"a" 0}
           :pre {"a" 0 "b" 1}  ; pre-order
           :post {"a" 2 "b" 1}
           :id-by-pre {0 "a"}}}
```

### Intent → Operations Pattern (src/plugins/)

UI dispatches intents, plugins calculate operations:
```
User Action
    ↓
Component (Replicant)      # Dispatch intent
    ↓
Plugin (Intent Handler)    # Calculate operations
    ↓
Kernel (Transaction)       # Apply + derive
    ↓
Component Re-renders       # Replicant diffs DOM
```

**Example plugins:**
- `plugins.nav` - Arrow key navigation with cursor memory
- `plugins.selection` - Multi-block selection, Shift+Click ranges
- `plugins.struct` - Indent/outdent/move with boundary awareness
- `plugins.smart_editing` - Context-aware Enter, empty list unwrapping

### Session State Unification (const/session-ui-id)

All ephemeral UI state lives under one node:
```clojure
{:editing-block-id "x"
 :cursor-position 5
 :cursor-memory {block-uuid {:line-pos n}}
 :selection {:nodes [...] :direction :up}
 :slash-menu {...}
 :quick-switcher {...}}
```

### View Layer (src/shell/ + src/components/)

Replicant components are pure render functions:
- No mutations, no local state
- Lifecycle hooks for DOM-only operations (focus, selection)
- Nexus dispatcher routes events → intents

## Key Architectural Decisions

1. **Three ops vs four** - No separate `:delete` (just `:place` under `:trash`)
2. **Derived indexes auto-recomputed** - Never mutate manually
3. **Session state unified** - Single node vs scattered refs
4. **Plugins register intents** - Decoupled from kernel
5. **Framework-agnostic kernel** - Replicant adapter swappable

## Design Constraints

- Solo developer must understand/debug
- REPL-friendly (no hidden state)
- Immutable event sourcing
- Correctness over performance
- Simple over clever

## Questions for Review

1. **Is three-op kernel optimal?** Should we add `:move` or `:reparent` primitives?
2. **Is intent→operations the right abstraction?** Does it scale to complex interactions?
3. **Are derived indexes right?** Computing too much/too little? Correct boundaries?
4. **Is session state unification working?** Better than scattered refs?
5. **Is the plugin boundary clean?** Easy to add new behaviors?
6. **Any unnecessary complexity?** What can we simplify or remove?

## Red Flags to Avoid

- Solving problems we don't have
- Adding abstraction that doesn't pay for itself
- Breaking REPL-ability
- Hidden state or automation
- Clever solutions that are hard to debug
