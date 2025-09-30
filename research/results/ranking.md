# Battle Test Results: Reitit Routing Engine Analysis

## Executive Summary

Tested 2 models (Gemini, Codex) with 3 parallel queries each (6 total, but only 5 unique due to duplication).

**Winner: Codex-1** - Most comprehensive technical depth with concrete implementation patterns and strong EVO parallels.

**Quality Distribution:**
- **Excellent (9-10/10)**: Codex-1, Codex-3
- **Very Good (8-9/10)**: Codex-2
- **Good (7-8/10)**: Gemini-1/3 (duplicate)
- **Good (6-7/10)**: Gemini-2

---

## Detailed Rankings

### 🥇 #1: Codex-1 (10/10)

**Strengths:**
- **Technical Depth**: Exceptional detail on radix trie implementation with `common-prefix` path compression
- **Concrete Patterns**: Specific function references (`split-path`, `-insert`, `linear-matcher`, `quarantine-router`)
- **Implementation Details**:
  - Preallocated param records via `record-parameters` for JVM optimization
  - Two compiler backends (Java `reitit.Trie` vs pure Clojure)
  - Specificity ordering via `[depth, length]` tuple
- **EVO Parallels**: Strong section connecting canonical state → derived indexes, NORMALIZE/VALIDATE/APPLY phases
- **Architectural Decisions**: Explains compile-once strategy, O(1) lookup for static routes via HashMap

**Quote Highlights:**
> "Radix edges with `common-prefix` cut trie depth... Preallocated param records on JVM to reduce map churn."

> "Reitit's canonical input is data (route tree with `:data`), its derived index is the compiled trie plus name/lookup tables. EVO's derived indexes (`:pre/:post`, etc.) play the same role: fast, read-optimized structures derived from a single source of truth."

**Structure:** Clear 4-section breakdown with dedicated "Patterns → EVO Parallels" section and concrete implementation decisions list.

**Token Usage:** 25,640

---

### 🥈 #2: Codex-3 (9.5/10)

**Strengths:**
- **Organization**: Numbered sections (1-4) with clear hierarchy and subsections
- **EVO Integration**: "Relating Patterns to EVO's Tree Database" section explicitly maps concepts
- **Implementation Choices**: Dedicated "Notable Implementation Choices" section at end
- **Clarity**: Bullet points with clear explanations of radix compression, compiler backends, etc.
- **Completeness**: Covers all 4 requested areas with excellent technical depth

**Quote Highlights:**
> "Reitit's 'resolved routes' are canonical state; the compiled trie/matchers are derived indexes for fast queries, just like EVO's pre/post/id-by-pre traversal indexes."

> "Route data inheritance and accumulation up/down the route tree mirrors EVO's structural layer that compiles high-level intents into core operations using derived indexes."

**Structure:** Most reader-friendly with numbered sections and clear subsections. Best for understanding flow: normalize → validate → compile/derive.

**Distinguishing Feature:** Only response with explicit "Canonical vs. derived" and "Normalize → Validate → Compile/Derive" subsections that directly map to EVO phases.

**Token Usage:** 23,790

---

### 🥉 #3: Codex-2 (8.5/10)

**Strengths:**
- **Router Selection**: Most detailed explanation of router specializations (`single-static-path-router`, `lookup-router`, `trie-router`, `mixed-router`)
- **Reverse Routing**: Only response covering `match-by-name`, `impl/path-for`, and bi-directional routing
- **Encoding Details**: Specific coverage of URL encoding/decoding with `url-encode`, `maybe-url-decode`, `form-encode`
- **Data-First Composition**: Strong section on middleware/interceptor composition with `meta-merge` and `:update-paths`

**Quote Highlights:**
> "Optimized fast paths: static-only becomes O(1) `HashMap`, single static path uses interned equality, mixed splits static vs wildcard to avoid unnecessary trie traversal."

> "Pluggable compilation target: `TrieCompiler` protocol cleanly abstracts matching; JVM gets a tuned Java engine; CLJS gets a tight, portable implementation."

**Structure:** Traditional 4-section structure with good subsections. Strong "Concrete Patterns and Decisions" section.

**Distinguishing Feature:** Most comprehensive router selection logic and reverse routing coverage.

**Token Usage:** 22,899

---

### #4: Gemini-1 & Gemini-3 (7.5/10) ⚠️ DUPLICATE

**Issue:** These two responses are **byte-for-byte identical** - violates instruction "don't use the same prompt more than once per model"

**Strengths:**
- **Executive Summary**: Strong opening that sets context
- **EVO Analogies**: Good parallels throughout (canonical state vs derived index, pure functions)
- **Clear Structure**: Well-organized sections with code examples
- **Compilation Focus**: Emphasizes the Node trie → Matcher tree compilation process

**Weaknesses vs Codex:**
- Less technical depth on implementation specifics
- Fewer concrete function references
- Less detail on router specializations
- No mention of reverse routing or encoding details
- Weaker on compile-time optimizations

**Quote Highlights:**
> "Reitit, like EVO, is fundamentally a data-driven system that employs a **compilation** phase. It transforms a declarative data structure (a nested vector of routes) into a highly optimized, specialized data structure (a prefix trie of `Matcher` objects) for fast runtime execution."

> "The `Node` trie is a descriptive data structure. For maximum performance, it's compiled into a tree of objects implementing the `Matcher` protocol."

**Structure:** Executive summary + 4 numbered sections with good subsections and code examples.

**Value:** Solid explanation but less technical depth than Codex responses.

---

### #5: Gemini-2 (6.5/10)

**Strengths:**
- Similar structure to Gemini-1 but with different phrasing
- Good conflict detection explanation
- Covers all 4 required areas

**Weaknesses:**
- Less detailed than Gemini-1/3 in most areas
- Shorter explanations overall
- Less emphasis on concrete implementation
- EVO parallels are weaker
- Feels more like a summary than deep analysis

**Quote Highlights:**
> "The `quarantine-router` splits compiled routes: Non-conflicting → `mixed-router` (fast path), Conflicting → `linear-router`, which preserves author order"

**Structure:** Similar to other Gemini responses but more concise (sometimes too concise).

**Value:** Covers the basics but doesn't add much beyond Gemini-1/3.

---

## Comparative Analysis

### Technical Depth Ranking
1. **Codex-1** - Radix compression, preallocated records, two compilers, specificity ordering
2. **Codex-3** - Canonical vs derived, normalize→validate→compile pipeline
3. **Codex-2** - Router specializations, reverse routing, encoding/decoding
4. **Gemini-1/3** - Node trie → Matcher compilation, basic EVO parallels
5. **Gemini-2** - High-level overview, minimal implementation details

### EVO Parallels Ranking
1. **Codex-3** - Explicit "Relating Patterns to EVO" with canonical/derived, pipeline phases, invariants
2. **Codex-1** - Strong "Patterns → EVO Parallels" section mapping compilation, indexes, composition
3. **Codex-2** - Good EVO parallels on tree indexes, pipeline phases, composition via data
4. **Gemini-1/3** - Decent analogies but less systematic
5. **Gemini-2** - Weak EVO connections

### Code References Ranking
1. **Codex-1** - Extensive function refs: `split-path`, `-insert`, `common-prefix`, `record-parameters`, etc.
2. **Codex-2** - Good coverage: `path-matcher`, `url-encode`, `meta-merge`, `fast-map`, etc.
3. **Codex-3** - Solid refs: `normalize`, `compile`, `linear-matcher`, `quarantine-router`
4. **Gemini-1/3** - Some refs but less comprehensive
5. **Gemini-2** - Minimal function references

### Clarity & Organization Ranking
1. **Codex-3** - Numbered sections, clear subsections, best flow
2. **Codex-1** - Clear 4-section structure with dedicated patterns section
3. **Gemini-1/3** - Executive summary + numbered sections
4. **Codex-2** - Traditional structure, slightly denser
5. **Gemini-2** - Less organized, more scattered

### Actionability Ranking
1. **Codex-1** - Concrete implementation decisions list at end
2. **Codex-3** - Notable implementation choices section
3. **Codex-2** - Concrete patterns section with specifics
4. **Gemini-1/3** - Good analogies but less actionable
5. **Gemini-2** - High-level, least actionable

---

## Key Differences

### Codex vs Gemini
- **Codex**: More technical depth, specific function references, implementation patterns, compiler details
- **Gemini**: Better narrative flow, executive summaries, but less technical depth

### Within Codex (1 vs 2 vs 3)
- **Codex-1**: Most comprehensive, best implementation patterns
- **Codex-2**: Best router selection and reverse routing coverage
- **Codex-3**: Best organization and EVO mapping

### Within Gemini (1/3 vs 2)
- **Gemini-1/3**: More thorough, better structure
- **Gemini-2**: More concise, weaker overall

---

## Unique Contributions by Response

### Codex-1 Only:
- Preallocated param records for JVM (`record-parameters`)
- Radix edge compression with `common-prefix`
- `[depth, length]` specificity tuple
- Conflict quarantine strategy details

### Codex-2 Only:
- Reverse routing (`match-by-name`, `impl/path-for`)
- URL encoding/decoding (`url-encode`, `maybe-url-decode`, `form-encode`)
- Router specialization selection logic
- Compressed substring trie benefits

### Codex-3 Only:
- Explicit "Canonical vs. derived" subsection
- "Normalize → Validate → Compile/Derive" pipeline mapping
- "Invariants and surgery" comparison
- Most structured EVO parallels

### Gemini-1/3 Only:
- Executive summary framing
- Step-by-step Matcher execution example
- Denormalization analogy for middleware

### Gemini-2 Only:
- (No unique contributions - mostly overlaps with Gemini-1/3 but less detailed)

---

## Token Efficiency

| Response | Tokens | Quality | Tokens/Quality |
|----------|--------|---------|----------------|
| Codex-1  | 25,640 | 10.0    | 2,564          |
| Codex-3  | 23,790 | 9.5     | 2,504          |
| Codex-2  | 22,899 | 8.5     | 2,694          |
| Gemini-1 | ~12,000* | 7.5   | ~1,600         |
| Gemini-2 | ~12,000* | 6.5   | ~1,846         |

*Estimated based on response length (Gemini didn't include token counts in output)

**Best Token Efficiency:** Gemini-1 (shorter but solid coverage)
**Best Value:** Codex-3 (high quality with reasonable token count)

---

## Recommendations

### For Learning Reitit:
1. Start with **Codex-3** for clear organization and structure
2. Read **Codex-1** for implementation depth
3. Use **Codex-2** for router selection specifics

### For EVO Comparisons:
1. **Codex-3** has the most explicit EVO mapping
2. **Codex-1** has the strongest compilation parallels
3. Skip Gemini responses - weaker on EVO connections

### For Implementation:
1. **Codex-1** for concrete patterns and decisions
2. **Codex-2** for router selection and reverse routing
3. **Codex-3** for architectural overview

---

## Issues Found

### Critical:
- **Gemini Duplicate**: Responses 1 and 3 are identical (violates "don't use same prompt twice" rule)

### Observations:
- Codex responses included extensive reasoning summaries (~1430 lines) before actual response
- Gemini responses were more concise but less technical
- All responses covered the 4 required areas (trie, matching, composition, conflicts)
- Codex consistently provided more function-level references
- All responses attempted EVO parallels, but Codex did it more systematically

---

## Conclusion

**Winner: Codex-1**

Codex-1 provides the best balance of technical depth, concrete implementation patterns, and strong EVO parallels. It's the most actionable response with specific function references and architectural decisions.

**Runner-up: Codex-3**

Codex-3 is the most reader-friendly with excellent organization and the clearest EVO mapping. Best for understanding the overall architecture.

**Best Value: Codex-3**

Highest quality-to-token ratio while maintaining excellent technical depth.

**Least Useful: Gemini-2**

Adds little beyond Gemini-1/3 and lacks technical depth of Codex responses.

**Major Issue: Gemini produced duplicate responses** (1 and 3 identical), reducing unique value to 5 responses instead of 6.
