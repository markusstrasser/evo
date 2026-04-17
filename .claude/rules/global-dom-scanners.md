# Global DOM Scanners — Contract

Libraries that scan the live DOM to transform content (MathJax, Prism,
highlight.js, Mermaid, KaTeX, linkify, Twemoji, Turndown paste hooks)
can silently corrupt user content if their activation surface overlaps
normal prose or code. The 2026-04-17 incident — MathJax typesetting
`cljs$core$key` and `$100$total` into ""-delimited inline math and
eating the `$` delimiters — is the canonical example.

## Contract for every DOM-scanning library

Any library added to the app that traverses the DOM and mutates text
(or appends shadow trees that replace text) MUST satisfy all four:

1. **Default-off activation.** The library does NOT process content by
   default. Processing is gated on an explicit opt-in class (e.g.
   MathJax's `processHtmlClass: 'math'`).

2. **Anchored class regex.** When the opt-in class is passed to the
   library, it is anchored with word boundaries (`\bname\b`). Otherwise
   `name-ignore`, `name-block`, `name-inline`, or any future class
   containing the substring can accidentally re-enable processing on
   ignored ancestors.

3. **Ignored containers on every view surface.** Every user-content
   container (view-mode `.block-content`, edit-mode contenteditable,
   anywhere the user's raw text lives in the DOM) carries the library's
   corresponding `<name>-ignore` class. Adding a new content surface
   without this class is a regression.

4. **E2E regression test.** One Playwright spec per library that renders
   user content containing the library's trigger syntax (e.g. `$`
   chars, `` ` `` backticks, `http://` URLs, emoji-like tokens) and
   asserts the textContent round-trips unchanged. Living documentation
   that the ignore contract holds.

## Current roster

- **MathJax 3** (`public/index.html`) — `processHtmlClass: '\\bmath\\b'`,
  `ignoreHtmlClass: 'math-ignore'`. View containers carry
  `.block-content.math-ignore`, edit container carries
  `.block-content.math-ignore`. Explicit math spans emitted by the
  parser carry class `math`. Regression: `test/e2e/inline-format-rendering.spec.js`.

## Non-members

These libraries do NOT scan the DOM globally and do not need the
contract (they transform text as input, not as DOM):

- **Turndown** — runs on pasted HTML strings from the clipboard,
  returns markdown text. No DOM mutation outside the paste call site.
- **Three.js** — operates on its own canvas, does not touch block text.

## When adding a new scanner

- State which opt-in class it uses.
- Pin the class regex with `\b...\b` if the library accepts a regex.
- Add the matching `-ignore` class to every user-content container in
  `src/components/block.cljs` (view, edit, and any future embed render).
- Write an E2E test that renders user content containing the library's
  trigger syntax and asserts textContent unchanged when the content is
  not in the opt-in class.
- Append an entry to the "Current roster" above.

## Why this matters

Global DOM mutation violates kernel purity — the shell library changes
what the user wrote without going through the event-sourced pipeline.
When these mutations silently corrupt text, the user sees garbled
content with no rollback path (the DB still holds the original but the
render disagrees). Every scanner we add enlarges this surface; the
contract keeps it explicit.
