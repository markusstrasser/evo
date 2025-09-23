# Evolver

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