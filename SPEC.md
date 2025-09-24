# Evolver UI Specifications

## Core User Stories & Features (Extracted from Tests)

### Selection System
- **Click to Select**: Users can click on any block to select it
- **Clear Selection**: `Escape` key clears all current selections
- **Select All**: `Ctrl+Shift+A` should select all blocks in the tree
- **Multi-Selection**: Users can select multiple blocks for bulk operations
- **Visual Feedback**: Selected blocks show `selected` CSS class and visual highlighting

### Keyboard Navigation
- **Sibling Navigation**: 
  - `Alt+↑` navigates to previous sibling
  - `Alt+↓` navigates to next sibling
- **Tree Navigation**: Navigate through tree hierarchy using arrow keys
- **Selection Movement**: Navigation changes which node is currently selected

### Block Operations
- **Delete Blocks**: 
  - `Delete` or `Backspace` removes selected blocks from tree
  - Requires at least one block to be selected
- **Create Operations**:
  - `Enter` creates new sibling block after selected
  - `Shift+Enter` creates new sibling block before selected
  - Child block creation under selected blocks
- **Structure Operations**:
  - `Tab` indents selected blocks (move under parent)
  - `Shift+Tab` outdents selected blocks (move to parent level)
- **Move Operations**:
  - `Alt+Shift+↑` moves selected block up
  - `Alt+Shift+↓` moves selected block down

### Reference System
- **Add References**: Create links between any two nodes
  - Select exactly 2 nodes, then use add-reference operation
- **Remove References**: Remove existing links between nodes
- **Bidirectional References**: References work in both directions
- **Reference Integrity**: References are cleaned up when nodes are deleted
- **Visual Indicators**: Referenced nodes show visual connection indicators
- **Reference Queries**:
  - Get all nodes that reference a specific node
  - Get all nodes referenced by a specific node

### Undo/Redo System
- **Operation History**: All operations are logged for undo/redo
- **Undo Operations**: `Ctrl+Z` undoes last operation
- **Redo Operations**: `Ctrl+Y` redoes previously undone operation
- **Reference Undo/Redo**: Reference operations are fully undoable
- **State Restoration**: Undo/redo restores complete application state

### Data Validation & Error Handling
- **Schema Validation**: Database structure is validated against schemas
- **Command Validation**: Commands are validated before execution
- **Error Display**: Invalid operations show clear error messages
- **Transaction Logging**: All state changes are logged with timestamps
- **Integrity Checks**: Reference integrity is maintained across operations

### Store & State Management
- **Reactive Updates**: State changes trigger automatic UI updates
- **Store Structure**: 
  - `:nodes` - map of node-id to node data
  - `:view` - contains `:selected` set of node IDs
  - `:children-by-parent` - tree structure mapping
  - `:references` - reference graph mapping
  - `:history` - operation history for undo/redo
- **Selection State**: Selection is maintained in store and synced with DOM
- **Error Tracking**: Errors are tracked in store for display

## Current Implementation Status

### ✅ **WORKING (Verified via Chrome DevTools)**
- **Click Selection**: Click any block to select/deselect it
- **Escape Key**: Clears selection successfully  
- **Store Updates**: Direct store mutations work correctly
- **DOM Synchronization**: Selected blocks get `selected` CSS class
- **Error Handling**: Errors are displayed in UI when commands fail
- **Core Data Operations**: Kernel functions work for node operations
- **Reference System**: Add/remove references work at data layer
- **Schema Validation**: Database validation works correctly

### ❌ **BROKEN (Needs Implementation)**
- **Command Registry**: `select-node` and other UI commands missing
- **Keyboard Shortcuts**: Most keyboard shortcuts don't trigger commands
- **Navigation Commands**: Arrow key navigation not implemented
- **Block Creation**: Create child/sibling operations not working
- **Delete Operations**: Delete commands not connected to UI
- **Undo/Redo UI**: Keyboard shortcuts for undo/redo not working
- **Command Dispatch**: UI actions throw "Unknown command" errors

### ⚠️ **PARTIAL (Architecture Present, UI Integration Missing)**
- **Reference Operations**: Data layer works, UI commands missing
- **Validation System**: Works but causes undo operation failures
- **Middleware Pipeline**: Present but not fully integrated with UI
- **Tree Structure**: Data management works, UI operations need commands

### Middleware Pipeline System
- **Command Processing**: Multi-step pipeline for safe command execution
- **Validation Steps**: Commands validated before and after execution
- **Error Collection**: Errors accumulated throughout pipeline execution
- **Effect Tracking**: Side effects tracked and applied systematically
- **Logging Pipeline**: All operations logged with execution context
- **Pipeline Order**: Steps execute in deterministic order for consistency
- **Safe Execution**: Commands that fail don't corrupt application state

### Quality Assurance & Testing
- **Property-Based Testing**: Generative testing with random interaction sequences
- **UI Consistency Checking**: Automated validation of UI state consistency
- **Command Contract Testing**: Parameter format validation across all commands
- **Chrome DevTools Integration**: Automated browser testing capabilities
- **Regression Testing**: Protection against critical bugs via automated tests
- **State Invariant Checking**: Validation that application state remains consistent
- **Fuzzy Testing**: Random user interaction simulation for edge case discovery

### Advanced Features
- **Command Parameter Validation**: Type checking and format validation for all commands
- **Keyboard Shortcut Customization**: Configurable key mappings for all operations
- **Tree Structure Integrity**: Automatic validation of parent-child relationships
- **Node Type System**: Extensible node types with validation schemas
- **Operation Batching**: Multiple operations can be batched into transactions
- **State Snapshots**: Complete application state can be captured and restored
- **Error Recovery**: Graceful handling of invalid operations without data loss

### Browser Integration Features  
- **Real-time Rendering**: Changes reflected immediately in browser UI
- **CSS Class Management**: Dynamic application of selection/state classes
- **Event System Integration**: Native browser events properly captured and processed
- **Console Logging**: Comprehensive logging for development and debugging
- **Hot Reload Support**: Development changes applied without losing state
- **Performance Monitoring**: Built-in performance tracking and optimization

## Key Insight
The core functionality (data operations, state management, validation) works perfectly. The gap is in the **command registry** - UI interactions try to dispatch commands that don't exist in the command system. The architecture is sound, but the glue layer between UI events and data operations is incomplete.