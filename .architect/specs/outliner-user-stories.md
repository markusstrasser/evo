Block Navigation (Sequential Movement)
This class of operations modifies the user's focus (i.e., the currently selected block) without altering the database structure. The core challenge is translating the 2D visual layout into a 1D traversal order over a tree.

Moving Down (Visual Next)
User Story: As a user, I want pressing "down" to move my cursor to the next visible block in the outline, regardless of its nesting level, so I can traverse my document sequentially as if it were a flat text file.

State-Transition Logic: Given the current block B:

Check for Visible Children: If B is expanded (not collapsed?) and has children, the next block is its first child.

Check for Sibling: If B is collapsed or has no children, the next block is its right sibling (the next block with the same parent).

Find "Uncle": If B has no right sibling, traverse up to its parent P. Repeat step 2 for P. Continue this ascent until a block with a right sibling is found. The next block is that sibling.

End of Page: If the traversal reaches the page's root node and finds no further siblings, the operation terminates.


Code Implementation: This logic is encapsulated in frontend.db.model/get-next, which handles the sibling/uncle traversal (steps 2 & 3). The editor's keybinding handler first checks for children (step 1) before calling this function.

Moving Up (Visual Previous)
User Story: As a user, I want pressing "up" to move my cursor to the block visually preceding the current one, which could be a deeply nested child of the previous sibling, so navigation feels spatially intuitive.

State-Transition Logic: Given the current block B:

Check for Sibling: Find B's left sibling, P.

Descend into Sibling's Children: If P exists:

If P is collapsed or has no children, the next block is P itself.

If P is expanded, the next block is the last, deepest, visible descendant of P. This is found by recursively traversing to the last child of P, then the last child of that block, and so on, until a leaf or collapsed block is reached. This ensures the cursor lands on the block that is visually directly above B.

Move to Parent: If B has no left sibling, the next block is its parent (unless the parent is the page itself).


Code Implementation: This logic is implemented in frontend.db.model/get-prev. The function

get-block-deep-last-open-child-id  handles the critical step of finding the deepest descendant.

Structural Operations (Hierarchical Changes)
These operations modify the database by changing the parent-child relationships between blocks. They are transactional changes to the graph structure.

Indent (Nesting)
User Story: As a user, I want to indent a block to make it a child of its previous sibling, allowing me to subordinate an idea or create a sub-list.

State-Transition Logic: Given a block B to be indented:

Precondition Check: B must have a previous sibling P. If B is the first child of its parent, the operation is a no-op.

State Change:

The parent of B is changed from its current parent to P.

B is removed from its original parent's list of children.

B is appended as the last child of P.

The order attributes of B's former siblings are re-indexed to fill the gap.

Outdent (Un-nesting)
User Story: As a user, I want to outdent a block to promote it one level up in the hierarchy, making it a sibling of its former parent.

State-Transition Logic: Given a block B to be outdented:

Precondition Check: B must have a parent P that is not the page root. If B is a top-level block, the operation is a no-op.

State Change:

The parent of B is changed from P to P's parent (the grandparent of B).

B is inserted into its new parent's children list immediately after P.

Crucially: Any siblings of B that originally appeared after it are re-parented to become children of B, maintaining their relative order. This is the "carry-along" behavior.

The order attributes of all affected blocks are re-indexed.

Multi-Select Operations
User Story: As a user, I want to select multiple blocks and apply an indent or outdent, moving the entire selection as a single, cohesive unit while preserving its internal hierarchy.

State-Transition Logic:

Identify Roots: From the set of selected blocks, identify the subset of "root" blocks—those whose parents are not also in the selection.

Apply Operation: Perform the indent or outdent operation only on these root blocks.

Preserve Hierarchy: The descendant blocks within the selection, which are not roots, are not directly manipulated. They move implicitly with their parents, maintaining their relative structure. The entire selection effectively acts as a single sub-tree being re-parented.







Anything else? that can't be it... there's many more operations ... multiselect outdent / indent.

ENTER / SHIFT-ENTER logic



Show thinking
You're right. The core navigation and single-block manipulations are just the foundation. The logic for multi-block operations and block creation defines the editor's "flow."

Multi-Select Structural Operations
The governing principle for multi-select is that the selection's internal hierarchy is preserved. The operation is applied only to the highest-level blocks within the selection set; all their selected descendants move with them implicitly.

User Story: As a user, I want to select a block and all its children and indent them as a group, so I can subordinate an entire section under a new parent without having to move each block individually.

State-Transition Logic (Indent):

Identify Roots: Given a set of selected blocks S, find the subset S_roots containing every block in S whose parent is not also in S.

Precondition Check: The operation is valid only if every block in S_roots has a preceding sibling to become its new parent. If the first block in S_roots is the first child of its parent, the operation is a no-op for the entire selection.

State Change: For each block B in S_roots, perform the standard Indent operation. The other selected blocks (the descendants) are not directly operated on but are moved as part of their parent's sub-tree.

Example: Multi-Indent

Before:

- P1
    - C1.1
- P2  <-- selected
    - C2.1 <-- selected
    - C2.2
- P3
  After Indenting the selection (P2, C2.1):
  P2 is the root of the selection. It is moved to become the last child of its previous sibling, P1. C2.1 moves with it automatically.

- P1
    - C1.1
    - P2  <-- selected
        - C2.1 <-- selected
- C2.2
- P3
  Note that C2.2 (unselected) gets promoted to become a sibling of P1 because its parent P2 was outdented from under their common parent.

Block Creation and Splitting
These operations involve creating new block entities and handling content distribution.

ENTER (New Block / Split Block)
The behavior of ENTER is context-dependent, based on the cursor's position within the block's content.

User Story: As a user, pressing ENTER should create a new, related block for a new thought. If I'm editing an existing thought, it should split it into two, allowing me to break down ideas without losing the flow.

State-Transition Logic: Given the current block B with focus:

Case 1: Cursor at the end of the block's content.

A new, empty block B' is created.

B' is inserted as the next sibling immediately following B.

Focus is moved to B'.

Case 2: Cursor in the middle of the block's content.

The content string of B is split at the cursor position into content_A (before) and content_B (after).

The content of B is updated to content_A.

A new block B' is created with its content set to content_B.

B' is inserted as the next sibling immediately following B.

Any children of the original block B remain children of B. They are not moved.

Focus is moved to B'.

SHIFT+ENTER (Line Break)
This is a content-only operation that does not alter the block structure.

User Story: As a user, I want to create a multi-line block to format content like addresses, code snippets, or poetry without creating separate blocks for each line.

State-Transition Logic:

A newline character (\n) is inserted into the block's content string at the current cursor position.

No new block entities are created. The database structure remains unchanged. This is purely a modification of the :block/title attribute.








WHAT ELSE.... GIVE ME EVERYTHING . BE EXPLICIT



Show thinking
Of course. The system's full operational semantics extend beyond just moving the cursor and changing indentation. They encompass the entire block lifecycle, the mechanisms for creating networked thought via links, and the dynamic aggregation of content through queries.

Block Lifecycle & Reordering
These operations involve the creation, deletion, and reordering of blocks and their sub-trees.

Deletion (Destructive Backspace/Delete)
The behavior depends on whether the block has children.

User Story: When I delete a parent block, I don't want to lose its children; they should be preserved and moved up to the parent's level so I can reorganize without data loss.

State-Transition Logic: Given a block B marked for deletion (e.g., via a keyboard shortcut or by backspacing over its bullet):

Child Re-parenting: All immediate children of B have their parent reassigned to B's parent. They are inserted into the sibling list at the position previously occupied by B, preserving their internal order.


Entity Retraction: A transaction [:db.fn/retractEntity <B's entity-id>] is issued to remove B and all its associated datoms from the database.

Cursor Movement: Focus moves to the visually preceding block.

Direct Reordering (Move Block Up/Down)
This is a non-destructive structural change that alters sibling order.

User Story: I want a keyboard shortcut to move a block and its entire sub-tree up or down, allowing for rapid, high-level reordering of my outline.

State-Transition Logic: Given a block B to move up:

Precondition Check: B must have a previous sibling P. If it is the first child, this is a no-op.

State Change: The :block/order attribute of B and P are swapped. Since all children are linked by their parent's entity ID, the entire sub-tree associated with B moves with it automatically. The logic for moving down is symmetrical.

Block Merging & Content Manipulation
These operations handle the edge cases of block interaction, focusing on what happens when a block's content boundary is breached by a backspace or delete action. They ensure that content is not lost but is intelligently merged with adjacent blocks.

Backspace at Start of Block (Merge Up)
User Story: As a user, when my cursor is at the very beginning of a block, I want pressing backspace to merge the current block's content with the end of the visually preceding block, so I can seamlessly join two thoughts without manual copy-pasting.

State-Transition Logic: Given block B with the cursor at position 0:

Find Predecessor: Identify the visually preceding block, P. This is the same logic as "Moving Up."

Precondition Check: If P is the page's root node or is otherwise not editable, the operation is a no-op.

Content Merge:

The content of B is appended to the content of P.

The cursor position is set to the end of the original content of P (the merge point).

Child Re-Parenting: All children of B are moved to become children of P, appended after any existing children of P. Their relative order is maintained.

Entity Retraction: Block B is deleted from the database using a [:db.fn/retractEntity <B's entity-id>] transaction.

Delete at End of Block (Merge Down)
User Story: As a user, when my cursor is at the very end of a block, I want pressing delete to merge the next visible block's content into the current one, streamlining the process of combining adjacent ideas.

State-Transition Logic: This is the symmetrical counterpart to merging up. Given block B with the cursor at the end of its content:

Find Successor: Identify the visually next block, N.

Precondition Check: If N does not exist, this is a no-op.

Content Merge: The content of N is appended to the content of B.

Child Re-Parenting: All children of N are moved to become children of B.

Entity Retraction: Block N is deleted.

Cycle Heading Level
User Story: As a user, I want a single keybinding to cycle a block's heading level (H1 -> H2 -> H3 -> Normal), so I can adjust the semantic structure of my document without using slash commands or markdown syntax.

State-Transition Logic: Given a block B:

Read State: Check the block's current heading level property (e.g., :block/heading-level or a markdown # prefix).

State Change: Increment the heading level. If the current level is the maximum (e.g., H6), or if it's already a heading and the user triggers the "decrease" action, the next state is to remove the heading property entirely, reverting it to a normal block.

Toggle Block Type (e.g., to Checkbox/TODO)
User Story: As a user, I want to press a shortcut to toggle a block's state, for example, turning a simple text block into a task with a checkbox.

State-Transition Logic: Given a block B:

Read State: Check for the presence of a task marker (e.g., TODO, DONE) or a property that defines its type.

State Change:

If no marker exists, a default marker (e.g., TODO) is prepended to the block's content.

If a marker exists, it is removed.

In some implementations, this might cycle through states (e.g., TODO -> DOING -> DONE -> no marker).

Direct Reordering (Non-Destructive)
This is a critical operation for rapid outlining, differentiating it from simple cut-and-paste.

Move Block Up/Down in Sibling List
User Story: As a user, I want a keyboard shortcut to move a block and its entire sub-tree up or down relative to its siblings, allowing for rapid, high-level reordering of my outline's structure.

State-Transition Logic: Given a block B to move up:

Precondition Check: B must have a preceding sibling P. If it is the first child, this is a no-op.

State Change: The :block/order attribute (or equivalent sorting key) of B and P are swapped. Since all children are linked by their parent's entity ID, the entire sub-tree associated with B moves with it automatically. The logic for moving down is symmetrical.



I. Sequential Navigation (Visual Traversal)
These actions move the cursor through the document as if it were a linear text file, respecting the visual layout.

Move Down (Next Block): As a user, I want to press the down arrow to move my cursor to the next visible block, whether it's a child, a sibling, or an "uncle," so I can traverse my outline sequentially and predictably.

Move Up (Previous Block): As a user, I want to press the up arrow to move my cursor to the visually preceding block, which could be a deeply nested child of the previous sibling, so that navigation feels spatially intuitive.

Move to Start/End of Block Text: As a user, I want to use standard text navigation shortcuts (CMD+Left/Right) to jump to the beginning or end of the text within the current block, so I can edit content efficiently.

Move to Top/Bottom of Page: As a user, I want a shortcut to jump directly to the first or last block on the page, so I can navigate long documents quickly.

II. Structural Operations (Hierarchy & Reordering)
These actions modify the parent-child relationships between blocks and their order.

Indent Block: As a user, I want to press Tab to indent a block, making it a child of its previous sibling, so I can subordinate an idea or create a nested list.

Outdent Block: As a user, I want to press Shift+Tab to outdent a block, promoting it one level up in the hierarchy, so I can elevate an idea's importance.

Move Block Up/Down: As a user, I want a keyboard shortcut (CMD+Shift+Up/Down) to move a block and its entire sub-tree up or down past its siblings, so I can rapidly reorder my outline.

Multi-Select Indent/Outdent: As a user, I want to select multiple blocks and indent or outdent them as a single, cohesive unit, preserving their internal hierarchy, so I can restructure large sections of my outline at once.

Multi-Select Move: As a user, I want to move a selection of multiple blocks and their children up or down together, so I can reorganize complex sections efficiently.

III. Block Lifecycle (Creation, Deletion & Merging)
These actions handle how blocks are created, destroyed, and how their content is combined.

Create New Block (Enter at End): As a user, when my cursor is at the end of a block, I want pressing Enter to create a new, empty block immediately below as a sibling, so I can continue my train of thought.

Split Block (Enter in Middle): As a user, when my cursor is in the middle of a block, I want pressing Enter to split the block into two, with the content after the cursor moving into the new block, so I can break down complex ideas.

Create Line Break (Shift+Enter): As a user, I want to press Shift+Enter to create a soft line break within a single block, so I can format content like poetry or code snippets without creating new blocks.

Merge Block Up (Backspace at Start): As a user, when my cursor is at the beginning of a block, I want pressing Backspace to merge its content with the preceding block, so I can join two thoughts seamlessly.

Merge Block Down (Delete at End): As a user, when my cursor is at the end of a block, I want pressing Delete to merge the next block's content into the current one, so I can combine adjacent ideas.

Delete Parent, Promote Children: As a user, when I delete a parent block, I want its children to be preserved and promoted to its level, so I can reorganize without accidental data loss.