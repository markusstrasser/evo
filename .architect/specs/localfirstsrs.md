Local-First Anki Clone: Implementation Specification
Architecture Overview & Rationale
Goal: Build a local-first spaced repetition app that:

Stores data as real files on disk (git-friendly, editor-friendly)
Has zero server dependencies (pure static site)
Uses pure functional UI (Replicant's data-driven approach)
Maintains review history and uses mock scheduling algorithm (just for testing)

Tech Stack Reasoning:

ClojureScript + Replicant (johansson): Pure data-driven views, events as data, trivially testable
File System Access API: Direct disk access without server, real files users can edit
EDN + Markdown: Human-readable, git-diffable, parseable by both app and external tools
Promesa: Tames the Promise-based File API into readable code


* The markdown has the actual card content. The q/a delimiter is arbitrary, but we can start with ";". Later there can be more parsing schemes.
* The log.edn has all events that took place (the reduced log => the state).
* The meta.edn has the actual review and scheduling info for each card.

Cards should use hashes. If the content changes, it's like it's a new card and the metadata/review history is reset.



Format like so:

QA card
```md
What is the capital of France? ; Paris
```
or cloze deletion:
```md
Human DNA has [3 Billion] base pairs
```


Other user actions:

* Reviewing: "forgot", "hard", "good", "easy". Undo/redo.
* Picking the target folder for the cards/data first time using the app (later it's saved in browser cache (local/browser storage))

---

Be sure to test as much as you can without needing human feedback through testing the UI/file access. I guess some is unavoidable at the end.