# Evolver

## Design: Semantic UI REPL
You're designing a conversational interface canvas. The product is a tool where a user sculpts a fully reactive application by issuing natural language commands to an AI. Its core design principle moves beyond static layout tools by treating the interface not as a rigid tree of visual elements, but as a dynamic graph of interconnected components. A user can direct the AI to forge not just structural parent-child relationships, but also behavioral triggers, data-binding links, and semantic connections. This enables the rapid, iterative construction of both complex application logic and visual appearance from a single, unified conversational prompt, creating a fluid and deeply inspectable design environment.




## Design Decisions
Core insight: Trees in DataScript require fighting the tool - it's a flat EAV store pretending to do hierarchies. But if committed to this approach, the API must prioritize atomic operations that maintain tree coherence.
What we learned:

Position isn't conflation, it's a requirement. Creating an entity without position leaves the tree incoherent. Atomic create-with-position is a legitimate compound operation, not bad design.
Order calculation belongs in the system, not client. The LLM/client should declare intent ({:after "x"}) not implementation details (order strings). This keeps fractional indexing mechanics hidden.
Manual cascade deletion was the right call. DataScript's :db/isComponent is broken. Explicit descendant collection is more predictable.

The entire complexity stems from supporting operations like :after and :before.

# ref docs

## Unknowns:

The bottleneck in UI development isn't expressing intent, it's debugging when intent doesn't match behavior.
The real challenge: ambiguity resolution at scale. Maybe I'll end up building a disambiguation UI that's more complex than just... writing code.
# TODO
[ ]
https://code.thheller.com/blog/shadow-cljs/2024/10/18/fullstack-cljs-workflow-with-shadow-cljs.html