#!/bin/bash
# GPT-5 Codex CLI Examples (Use only when API isn't suitable)

# Example 1: Interactive exploration
# Good for: REPL-like sessions, quick questions
interactive_exploration() {
    codex -m gpt-5-codex -c model_reasoning_effort="high" \
        "Compare event sourcing vs CQRS for a solo developer"
}

# Example 2: Batch automation (when API not available)
# ⚠️ Only use if you can't use the API
batch_automation() {
    prompt="Generate a proposal for implementing undo/redo"

    echo "$prompt" | codex exec \
        -m gpt-5-codex \
        -c model_reasoning_effort="high" \
        --full-auto -
}

# Example 3: Session continuity
# Good for: Multi-turn research that needs context
session_example() {
    # Start session
    codex -m gpt-5-codex -c model_reasoning_effort="high" \
        "Explain event sourcing architecture"

    # Later, resume
    codex --resume
}

# Example 4: Session tracking
tracked_session() {
    codex --session-summary session.txt \
        -m gpt-5-codex \
        -c model_reasoning_effort="high" \
        "Architecture review"
}

# Example 5: Common pitfalls
bad_batch_example() {
    # ❌ WRONG: This will hang waiting for interaction
    # codex -p "prompt" -

    # ✅ RIGHT: Use exec --full-auto
    echo "prompt" | codex exec -m gpt-5-codex --full-auto -
}

# Example 6: Minimal reasoning for speed
minimal_reasoning() {
    codex -m gpt-5-codex -c reasoning_effort="minimal" \
        "Extract JSON from this log line: timestamp=2025-10-17 user_id=12345"
}

# Note: Prefer API integration (see api-integration.py) over CLI
# Use CLI only for:
# 1. Interactive exploration
# 2. Legacy scripts not yet migrated
# 3. Session continuity needs
