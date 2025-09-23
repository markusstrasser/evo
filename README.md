# Evolver

## Design: Semantic UI REPL
You're designing a conversational interface canvas. The product is a tool where a user sculpts a fully reactive application by issuing natural language commands to an AI. Its core design principle moves beyond static layout tools by treating the interface not as a rigid tree of visual elements, but as a dynamic graph of interconnected components. A user can direct the AI to forge not just structural parent-child relationships, but also behavioral triggers, data-binding links, and semantic connections. This enables the rapid, iterative construction of both complex application logic and visual appearance from a single, unified conversational prompt, creating a fluid and deeply inspectable design environment.

## Design: Tree Data Structure

Our kernel uses DataScript with three complementary attributes for tree operations:

- **`:parent`** - Child→parent reference. Enables efficient positional queries (`:before`, `:after`) and parent lookups
- **`:children`** - Parent→children references with `:db/isComponent true`. Provides automatic cascading deletion when parents are deleted
- **`:order`** - Fractional indexing for stable sibling ordering. Supports precise positioning without renumbering siblings

This bidirectional design trades minimal storage redundancy for declarative deletion and efficient queries in both directions.

# TODO
[ ] 



# ref docs

https://code.thheller.com/blog/shadow-cljs/2024/10/18/fullstack-cljs-workflow-with-shadow-cljs.html