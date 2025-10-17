#!/usr/bin/env bash
# REPL-First Debugging Skill - Documentation and examples
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

usage() {
    cat <<EOF
REPL-First Debugging Skill - Interactive debugging guidance

USAGE:
    run.sh <command>

COMMANDS:
    guide             Show debugging workflow guide
    patterns          Show common debugging patterns
    pitfalls          Show common pitfalls and solutions
    browser-helpers   Show browser console DEBUG helpers
    examples          Show debugging examples
    help              Show this help

This is primarily a documentation/guidance skill.
See SKILL.md for full details.

EXAMPLES:
    # Show workflow guide
    run.sh guide

    # See common patterns
    run.sh patterns

    # Browser console helpers
    run.sh browser-helpers
EOF
}

show_guide() {
    cat <<'EOF'
=== REPL-First Debugging Workflow ===

❌ Slow Way (Don't Do This):
1. Make educated guess
2. Edit code
3. Wait for compile
4. Reload browser
5. Check console
6. Repeat... (5+ iterations = 5+ minutes)

✅ Fast Way (REPL-First):
1. Reproduce in REPL/console first - Verify the problem
2. Test hypothesis in REPL - Try fixes interactively
3. Only then update code - Apply the working fix
4. Verify with browser - Final integration test

Time savings: 30 seconds vs 5+ minutes per bug!

Key principle: Test hypotheses interactively BEFORE editing code.

See SKILL.md for detailed examples.
EOF
}

show_patterns() {
    cat <<'EOF'
=== Common Debugging Patterns ===

## Pattern 1: Reproduce First
;; In REPL
(def test-data {...})
(my-function test-data)  ;; Observe the bug
;; Test fixes interactively, then update code

## Pattern 2: Async Iteration
(def handle (js/showDirectoryPicker))
(def values (.values handle))
(js/Array.from values)  ;; => [] Empty!
(.next values)  ;; => Promise! - It's async!
;; Fix found in 30 seconds!

## Pattern 3: State Exploration
@(re-frame.core/subscribe [:current-view])
(re-frame.core/dispatch [:test-event])
@(re-frame.core/subscribe [:result])

## Pattern 4: Function Testing
(def real-events (get @app-db :events))
(filter active? real-events)
(filter active? [])  ;; Edge case

See SKILL.md for more patterns.
EOF
}

show_pitfalls() {
    cat <<'EOF'
=== Common Pitfalls & Solutions ===

1. Browser Cache (Stale Code)
   Symptom: Changes not appearing
   Debug: DEBUG.reload()

2. Async Iteration
   Symptom: Array.from(asyncIterator) returns []
   Fix: Use for-await or promises

3. Event Sourcing Status
   Symptom: Events not appearing
   Debug: DEBUG.inspectEvents()

4. Stale Code Check
   Symptom: Code changes not taking effect
   Debug: myFunction.toString().includes("my code")

See SKILL.md for detailed solutions.
EOF
}

show_browser_helpers() {
    cat <<'EOF'
=== Browser Console DEBUG Helpers ===

State Overview:
  DEBUG.summary()           // Cards, events, stacks
  DEBUG.events()            // All events
  DEBUG.activeEvents()      // Only active
  DEBUG.undoneEvents()      // Only undone
  DEBUG.cards()             // All cards
  DEBUG.dueCards()          // Cards due now

Event Status:
  DEBUG.inspectEvents()     // Recent events with ✅/❌
  core.build_event_status_map(DEBUG.events())

Stacks:
  DEBUG.undoStack()         // Undo stack
  DEBUG.redoStack()         // Redo stack

Utilities:
  DEBUG.reload()            // Hard reload (clear cache)

Quick Checks:
  DEBUG.events().length
  DEBUG.activeEvents().length

See SKILL.md for more helpers.
EOF
}

show_examples() {
    cat <<'EOF'
=== Debugging Example: Array.from Bug ===

Full workflow (took 30 seconds vs 5+ minutes):

1. Reproduce in REPL:
   (def handle (js/showDirectoryPicker))
   (def values (.values handle))
   (js/Array.from values)  ;; => [] Empty!

2. Test hypothesis:
   (.next values)  ;; => Promise! It's async!

3. Fix found! Now update code:
   ;; Change to: for-await or promises

4. Verify: Reload, test - done!

More examples in SKILL.md and examples/ directory.
EOF
}

main() {
    case "${1:-help}" in
        guide)
            show_guide
            ;;
        patterns)
            show_patterns
            ;;
        pitfalls)
            show_pitfalls
            ;;
        browser-helpers)
            show_browser_helpers
            ;;
        examples)
            show_examples
            ;;
        help|--help|-h)
            usage
            ;;
        *)
            echo "Unknown command: $1"
            usage
            exit 1
            ;;
    esac
}

main "$@"
