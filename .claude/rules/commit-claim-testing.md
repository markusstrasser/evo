# Commit Claims Must Have Claim-Level Tests

When a commit message makes a guarantee — that something **anchors**,
**escapes**, **validates**, **sanitizes**, **rejects**, or **pins** —
there must be a test named for that claim, asserting it at the level
of the claim, not just at the integration surface.

## Why

The motivating incident: commit `71846480 [infra] Pin MathJax
processHtmlClass to \bmath\b — anchor the class match` claimed the
regex anchors the class name. An integration test confirmed MathJax's
public surface still worked — because MathJax's internal walker wraps
the pattern with space-boundaries, which accidentally rescued the
claim. Nothing tested the claim directly.

A claim-level test (`/\bmath\b/.test("math-ignore")` → should be false)
would have failed immediately and forced a real fix — tightening the
anchor to a space-boundary regex (shipped in `075e894c`).

## What counts as a claim-level test

For a commit that claims X:
- The test name should include the verb X ("rejects", "anchors",
  "validates", "sanitizes", "pins").
- The assertion should be at the level of the primitive X operates on
  — the regex, the predicate, the escape function — not a downstream
  consumer that might incidentally work.
- Positive AND negative cases. "Anchors to word" means both "matches
  foo bar math baz" AND "does not match foo math-ignore baz".

## Examples

| Claim | Wrong test | Right test |
|---|---|---|
| "Regex `\bmath\b` anchors" | "MathJax renders `$x$` correctly" | `assert /\bmath\b/.test("math-ignore") === false` |
| "Validator rejects private-use-area" | "App doesn't crash when you type weird chars" | `assert (not (valid-text? "\uE001"))` |
| "Escape fn sanitizes `<`" | "No XSS in integration test" | `assert (= "&lt;" (escape "<"))` |
| "Parser rejects unclosed dollars" | "Block renders" | `assert (all-text? "$x y")` |

## Process note for agents

When writing a commit message, check whether the subject or body uses
one of the trigger verbs (anchor, escape, validate, sanitize, reject,
pin). If it does, search the diff for a test with that verb in its
name. If none, either add one, or rephrase the commit so the claim
matches what the tests actually cover.

## Scope

This rule is advisory — not enforced by hook. The session-analyst
subagent should surface commits matching trigger verbs that lack a
corresponding claim-level test as an anti-pattern.
