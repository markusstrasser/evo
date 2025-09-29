# Proposal 20 · Intent Grammar as Trie Algebra (Reitit)

**Current touchpoints**
- `src/kernel/intents` (planned planner) and `src/kernel/core.cljc:567-600` – intents keyed by flat keywords.

**Finding**
Reitit compiles path grammars into tries (`modules/reitit-core/src/reitit/trie.cljc:14-198`). It normalises paths, splits wildcards, and uses tries to generate deterministic matchers.

**Proposal**
Represent high-level intents as words over kernel axes (existence, topology, order, references). Build a trie-based grammar to compile sequences like `[:select :move :into]` into canonical transaction plans.

```clojure
(defrecord Step [axis payload])
(defrecord Branch [children wilds catch-all result])

(defn compile-intents [intents]
  (reduce grammar/insert (->Branch {} {} {} nil) intents))
```

Planners walk the trie to auto-complete workflows, detect ambiguity (conflicting prefixes), and provide deterministic command lowering for apps like Roam Research or Figma.

**Expected benefits**
- Deterministic planner with prefix-aware auto-completion.
- Easier to author domain-specific grammars (e.g., VR layout vs. note-taking) by extending the trie.

**Implementation notes**
1. Port the minimal insertion/splitting logic (common-prefix, wild/catch-all) from Reitit to operate on `Step` records.
2. Define a compiler protocol to emit transaction builders, documentation, and metamorphic tests from the trie.
3. Add conflict detection akin to Reitit’s `conflicting-paths?` to fail fast on ambiguous grammars.

**Trade-offs**
- Trie maintenance introduces data-structure complexity; requires tooling to visualise grammars for designers.
- Highly dynamic intent systems might prefer rule engines; trie approach favours deterministic static grammars.
