# Tasks

## Implementation Tasks

### Block Embeds
- TASK: Implement {{embed ((block-id))}} syntax
- This would show the full block tree, not just inline text
- Currently only ((block-id)) works (inline text only)
- id: task-implement-embeds

### Page References
- TASK: Implement [[page-name]] syntax
- Links to other pages/documents
- Logseq uses this for page-to-page linking
- id: task-page-refs

### Performance
- Optimize rendering for large trees
- Add virtualization for 1000+ blocks
- id: task-performance

## Completed Tasks

### Basic Block References ✓
- Implemented ((block-id)) inline refs
- Added cycle detection
- Added missing block error handling
- id: task-block-refs-done

## Examples from Projects Page

Referencing the evolver project: ((project-evolver))

The key features are: ((key-features))

## Testing Embeds (NOT YET WORKING)

This syntax doesn't work yet:
{{embed ((tech-stack))}}

But this works (inline text only):
((tech-stack))

## Circular Reference Test

This should show a cycle warning: ((circular-test-1))
id: circular-test-1
