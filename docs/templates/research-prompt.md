# Research Prompt Template

Use this template when querying external repositories for architectural patterns.

## Template Structure

```
How does {PROJECT} implement {SPECIFIC_FEATURE}?

Focus on:
1) {MECHANISM_1}
2) {MECHANISM_2}
3) {MECHANISM_3}
4) {MECHANISM_4}

{DELIVERABLE_REQUIREMENT}
```

## Guidelines

### PROJECT
- Use the actual project name (e.g., "Adapton", "DataScript", "re-frame")

### SPECIFIC_FEATURE
- Be concrete: "derived indexes" not "data management"
- Be focused: one major feature per query
- Examples: "incremental computation", "transaction pipeline", "interceptor chain"

### MECHANISMS (4 focused questions)
- Technical, specific questions
- Each should address a different aspect
- Use concrete terms: "invalidation strategy" not "how it updates"
- Examples:
  - "How it tracks dependencies between derived values"
  - "How indexes are updated atomically"
  - "The compilation pipeline structure"

### DELIVERABLE_REQUIREMENT
- Request concrete outputs
- Options:
  - "Be specific about the architecture."
  - "Show code patterns."
  - "Show implementation examples."
  - "Show the threading patterns."
  - Combination: "Be specific about the architecture and show code patterns."

## Examples

### ✅ Good Prompt (Incremental Computation)
```
How does Adapton handle incremental computation and derived values?

Focus on:
1) How it tracks dependencies between derived values
2) How it invalidates and recomputes when inputs change
3) What patterns it uses for memoization and caching
4) How it handles cycles

Be specific about the architecture and show code patterns.
```

**Why this works:**
- Specific feature (incremental computation)
- 4 focused mechanisms
- Requests both architecture AND code
- Technical vocabulary (dependencies, invalidation, memoization)

### ✅ Good Prompt (Index Architecture)
```
How does DataScript implement derived indexes and maintain them during transactions?

Focus on:
1) EAVT/AEVT/AVET index structure
2) How indexes are updated atomically
3) Efficient lookup patterns
4) Batch update strategies

Show concrete implementation patterns.
```

**Why this works:**
- Uses domain-specific terms (EAVT, atomic updates)
- Addresses both structure AND behavior
- Asks for concrete patterns

### ❌ Bad Prompt
```
How does DataScript work?
```

**Why this fails:**
- Too broad
- No focus
- No specific deliverable
- Will get surface-level overview

### ❌ Bad Prompt
```
Explain the DataScript architecture in detail.
```

**Why this fails:**
- Still too broad
- "In detail" is vague
- No structured questions to guide response

## Advanced: Large Repository Strategy

For large repos (>100MB), use tree exploration first:

```bash
# 1. Explore structure
tree -L 3 -d <local-clojurescript-checkout>

# 2. Check size with tokei
tokei <local-clojurescript-checkout>

# 3. Zoom into specific subdirectory
repomix <local-clojurescript-checkout>/src/main/clojure/cljs \
  --include "compiler.clj,analyzer.cljc" \
  --copy --output /dev/null
```

## Multi-Query Strategy

For complex systems, break into multiple focused queries:

**Instead of:**
```
How does ClojureScript compilation work? (too broad)
```

**Use multiple queries:**
```
Query 1: How does ClojureScript parse source into AST?
Query 2: How does ClojureScript validate and normalize forms?
Query 3: How does ClojureScript emit JavaScript?
Query 4: How does ClojureScript handle errors?
```

Each query targets 1/4 of the context budget, gets deeper insights.

## Context Budget Guidelines

| Repo Size | Strategy | Include Pattern |
|-----------|----------|-----------------|
| <10MB | Full repo | `src/**,README.md` |
| 10-50MB | Core + docs | `src/kernel/**,src/main/**,README.md` |
| 50-200MB | Specific dirs | `src/main/clojure/X/**/*.clj` |
| >200MB | Single files | `src/main/clojure/X/file.clj` |

## Prompt Variations by Goal

### Pattern Extraction
```
Focus on:
1) {Pattern structure}
2) {When to use pattern}
3) {Implementation approach}
4) {Tradeoffs}
Show reusable patterns.
```

### Performance Analysis
```
Focus on:
1) {Bottleneck identification}
2) {Optimization strategies}
3) {Caching/memoization}
4) {Algorithmic complexity}
Show performance-critical code paths.
```

### Error Handling
```
Focus on:
1) {Error detection}
2) {Error propagation}
3) {Error recovery}
4) {User-facing messages}
Show error handling patterns.
```

### Extension Mechanisms
```
Focus on:
1) {Extension points}
2) {Plugin architecture}
3) {Composition patterns}
4) {Backward compatibility}
Show how to extend the system.
```
