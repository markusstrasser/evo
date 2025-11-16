# Triad Spec Template

Each feature spec lives under `docs/specs/` and must follow this structure so humans and AI agents can parse it deterministically.

```
feature_id: <GLOBAL-ID>
owners: <squad or person>
status: <open|in-progress|done>
source_refs:
  - keymap: <path>
  - component: <path>
  - intent: <path>
  - docs: <supporting anchors>
```

## 1. Keymap Coverage Slice
```
| Key | Context | Handler (upstream) | Handler File | Evo Intent | Status | Notes |
```
List all shortcuts that touch this behavior. Use ✅/◐/❌ to show parity.

## 2. Intent Contract Sheet
Bullet list capturing:
- Intent name(s)
- Triggers (inputs, contexts)
- Preconditions
- Inputs (data passed into the intent)
- Behavior (high-level steps; avoid prescribing implementation details)
- Side effects/constraints
- Upstream parity refs
- Tests owed / existing
- Open issues

## 3. Scenario Ledger
For each scenario:
```
### Scenario ID: <ID>
Given ...
When ...
Then ...
Tests: <namespaced tests>
Status: ✅/❌
Linked rows: <keymap/intent IDs>
```
Optionally include a user story table if you want to highlight end-user feelings, but it’s not required by default.
