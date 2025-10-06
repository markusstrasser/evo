---
id: card-kernel-ops
type: basic
deck: kernel-architecture
tags:
  - kernel
  - architecture
  - core-ops
difficulty: easy
created: 2025-10-05T16:00:00Z
---

# Front

What are the three core operations of the kernel?

# Back

The kernel has exactly **3 operations**:

1. **`create-node`** - Creates a node in the `:nodes` map
2. **`place`** - Moves a node in the tree (under a parent, at a position)
3. **`update-node`** - Merges props into an existing node

All higher-level operations (like `:indent`, `:outdent`, `:delete`) compile down to these 3 primitives.

# Extra

From the kernel architecture:
> "Closed Operation Set: The kernel is defined by just three operations. All higher-level behaviors are compiled down to these primitives."
