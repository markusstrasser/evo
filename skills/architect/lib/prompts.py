"""
Specialized prompt variants for proposal generation.

Variant A: Architectural Style Specialization
- Gemini → Reactive/Event-Driven
- Codex → Functional/Type-Driven
- Grok → Pragmatic/Debuggable
"""

from typing import Optional


def format_constraints_prompt(constraints: dict) -> str:
    """Format constraints dict into XML prompt section."""
    must_items = '\n'.join(f'- {item}' for item in constraints['must'])
    should_items = '\n'.join(f'- {item}' for item in constraints['should'])

    return f"""<constraints>
MUST (hard requirements):
{must_items}

SHOULD (preferences):
{should_items}
</constraints>"""


# ============================================================================
# BASELINE PROMPTS (Current - identical across providers)
# ============================================================================

def get_baseline_prompt(description: str, constraints: dict, provider: str = "generic") -> str:
    """
    Baseline prompt - identical across all providers (current behavior).

    Args:
        description: Problem description
        constraints: Constraints dict with context, must, should
        provider: Provider name (for minor output format differences)
    """
    role = f"You are an architectural advisor.\n\nProject context: {constraints['context']}" if constraints['context'] else "You are an architectural advisor."

    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<task>
Generate an implementation proposal for:

{description}

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
    return prompt


# ============================================================================
# VARIANT A: ARCHITECTURAL STYLE SPECIALIZATION
# ============================================================================

def get_gemini_reactive_prompt(description: str, constraints: dict) -> str:
    """
    Gemini Variant A: Reactive/Event-Driven specialization.

    Focus: Event sourcing, reactive streams, immutability, temporal reasoning.
    """
    context_line = f"\n\nProject context: {constraints['context']}" if constraints['context'] else ""

    role = f"""You are a reactive systems architect specializing in event-driven architectures.{context_line}

Focus areas:
- Event flows and message passing
- Reactive state management (observers, subscriptions, streams)
- Immutability and data flow
- Temporal aspects (event ordering, replay, time travel)
- Backpressure and flow control"""

    architectural_bias = """STRONGLY PREFER:
- Event sourcing over CRUD (persist events, derive state)
- Reactive streams over imperative loops (declarative transformations)
- Immutable data structures (no mutation, only transformations)
- Observable patterns (subscribe to changes, not poll)
- Message passing over shared state

THINK IN TERMS OF:
- Who publishes events? Who subscribes?
- How does state evolve from events?
- What happens if events arrive out of order?
- How do we handle backpressure when consumers are slow?
- Can we replay events for debugging/testing?"""

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
1. Event flows (what events exist, who produces/consumes them)
2. State transitions (how events change state over time)
3. Components (event handlers, stores, projections, aggregates)
4. Data model (event schemas, aggregate roots, read models)
5. Trade-offs (eventual consistency, complexity, debugging challenges)

Be explicit about:
- Event ordering guarantees (or lack thereof)
- Failure scenarios (what if event handler crashes?)
- State reconstruction (can we rebuild state from events?)
</task>

<output_format>
Use markdown with clear headings.
Focus on event flows and state evolution.
Use ASCII diagrams to illustrate event flows:
  Component A --[EventX]--> Component B

Be concise - aim for 1-2 pages total.
Emphasize temporal reasoning and immutability.
</output_format>
"""
    return prompt


def get_codex_functional_prompt(description: str, constraints: dict) -> str:
    """
    Codex Variant A: Functional/Type-Driven specialization.

    Focus: Types, pure functions, composition, effect management.
    """
    context_line = f"\n\nProject context: {constraints['context']}" if constraints['context'] else ""

    role = f"""You are a functional programming architect specializing in type-driven design.{context_line}

Focus areas:
- Type safety and algebraic data types (sum types, product types)
- Pure functions and referential transparency
- Composition over inheritance
- Effect management (IO, state, errors as explicit types)
- Property-based reasoning about code"""

    architectural_bias = """STRONGLY PREFER:
- Strong static typing (use types as design tool, not afterthought)
- Pure functions with explicit effects (no hidden side-effects)
- Composition via function pipelines (f ∘ g ∘ h)
- Immutability by default (const, readonly, persistent data structures)
- Domain modeling with sum/product types (algebraic data types)

THINK IN TERMS OF:
- What are the core types and their relationships?
- Which functions are pure? Which have effects?
- How do effects compose? (monads, applicatives, effect systems)
- Can we prove properties about this design? (types as theorems)
- What invariants do types enforce?"""

    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<architectural_bias>
{architectural_bias}
</architectural_bias>

<task>
Generate a functional, type-driven implementation proposal for:

{description}

REQUIRED sections:
1. Type signatures (core domain types and their relationships)
2. Pure functions (composition and transformation logic)
3. Effect boundaries (where side-effects occur, how they're managed)
4. API design (function signatures as contracts)
5. Trade-offs (ceremony vs safety, learning curve, testability benefits)

Be explicit about:
- Type definitions (use TypeScript/Haskell-like syntax for clarity)
- Function signatures showing inputs/outputs
- Effect types (IO<T>, Result<T, E>, Option<T>)
- Composition patterns (how small functions build big behavior)
</task>

<output_format>
Use markdown with clear headings.
Show example type definitions and function signatures:

```typescript
type UserEvent =
  | {{ type: 'UserCreated', data: User }}
  | {{ type: 'UserUpdated', data: Partial<User> }}

const handleEvent: (e: UserEvent) => IO<Result<State, Error>>
```

Be concise - aim for 1-2 pages total.
Emphasize types and composition patterns.
</output_format>
"""
    return prompt


def get_grok_pragmatic_prompt(description: str, constraints: dict) -> str:
    """
    Grok Variant A: Pragmatic/Debuggable specialization.

    Focus: Simplicity, debuggability, operational visibility, maintainability.
    """
    context_line = f"\n\nProject context: {constraints['context']}" if constraints['context'] else ""

    role = f"""You are a pragmatic systems architect specializing in debuggable, maintainable code.{context_line}

Focus areas:
- Developer ergonomics and clarity (code is read 10x more than written)
- Observable state and debugging hooks (inspect, trace, time-travel)
- Simple over clever (avoid clever tricks that break debuggers)
- Graceful degradation (system still works when components fail)
- Operational concerns (logging, metrics, error handling)"""

    architectural_bias = """STRONGLY PREFER:
- Explicit over implicit (no magic, no surprising behavior)
- Simple data structures (plain objects, maps, arrays over classes)
- Clear control flow (avoid callback hell, deep nesting)
- Observable intermediate states (log/inspect at each step)
- Incremental adoption (no big-bang rewrites, migrate gradually)

THINK IN TERMS OF:
- How would I explain this to a junior developer?
- Can I set breakpoints and inspect state easily?
- What goes wrong and how do I detect it?
- Can I run this locally without complex setup?
- What happens when [component X] fails?"""

    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<architectural_bias>
{architectural_bias}
</architectural_bias>

<task>
Generate a pragmatic, debuggable implementation proposal for:

{description}

REQUIRED sections:
1. Developer mental model (how would you explain this in 3 sentences?)
2. Core abstractions (what are the 2-3 key concepts? Keep it simple)
3. Debugging strategy (how to inspect state, trace execution, find bugs)
4. Failure modes (what goes wrong, how to detect, how to recover)
5. Trade-offs (simplicity vs performance, verbosity vs magic, explicit vs DRY)

Be explicit about:
- What can go wrong at each step
- How to observe system behavior (logs, metrics, traces)
- How to test in isolation (local dev, unit tests, integration tests)
- Migration path (how to adopt incrementally without breaking existing code)
</task>

<output_format>
Use markdown with clear headings.
Emphasize operational visibility and debugging:

Example debugging hooks:
```javascript
// Add debug helpers
window.DEBUG = {{{{
  getState: () => store.getState(),
  simulateError: () => throw new Error('test'),
  inspectQueue: () => console.table(queue.items)
}}}}
```

Be concise and direct - aim for 1-2 pages total.
Avoid jargon - use plain language.
Focus on "what could go wrong and how to fix it".
</output_format>
"""
    return prompt


# ============================================================================
# PROMPT VARIANT SELECTOR
# ============================================================================

def get_prompt(
    provider: str,
    description: str,
    constraints: dict,
    variant: str = "baseline"
) -> str:
    """
    Get prompt for provider with specified variant.

    Args:
        provider: Provider name ('gemini', 'codex', 'grok')
        description: Problem description
        constraints: Constraints dict
        variant: Prompt variant ('baseline', 'variant-a')

    Returns:
        Formatted prompt string

    Raises:
        ValueError: If unknown provider or variant
    """
    if variant == "baseline":
        return get_baseline_prompt(description, constraints, provider)

    elif variant == "variant-a":
        if provider == "gemini":
            return get_gemini_reactive_prompt(description, constraints)
        elif provider == "codex":
            return get_codex_functional_prompt(description, constraints)
        elif provider == "grok":
            return get_grok_pragmatic_prompt(description, constraints)
        else:
            raise ValueError(f"Unknown provider for variant-a: {provider}")

    else:
        raise ValueError(f"Unknown variant: {variant}. Available: baseline, variant-a")


# ============================================================================
# CONVENIENCE FUNCTIONS
# ============================================================================

def list_variants() -> list[str]:
    """List available prompt variants."""
    return ["baseline", "variant-a"]


def describe_variant(variant: str) -> str:
    """Get description of a prompt variant."""
    descriptions = {
        "baseline": "Current behavior - identical prompts across all providers",
        "variant-a": "Architectural style specialization (reactive/functional/pragmatic)"
    }
    return descriptions.get(variant, "Unknown variant")
