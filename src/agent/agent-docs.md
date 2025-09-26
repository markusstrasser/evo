# Agent Documentation

Concise documentation for agent tools. Each namespace lists its purpose and public functions with brief descriptions.

## agent.core

Enhanced unified entry point for all agent utilities and analysis tools.

### Functions
- `explore`: Analyze store comprehensively
- `diagnose`: Interactive diagnostics
- `trends`: Analyze performance trends
- `pipeline`: Create composable analysis pipeline
- `execute`: Execute pipeline
- `workflows`: List pipelines
- `config`: Get configuration
- `set-config!`: Set configuration
- `set-profile!`: Set profile
- `save-config!`: Save configuration
- `show-config`: Show configuration
- `create-monitor`: Create monitor
- `start-monitoring`: Start monitoring
- `stop-monitoring`: Stop monitoring
- `monitor-status`: Get monitoring status
- `monitor-report`: Get monitoring report
- `setup-monitoring`: Setup basic monitoring
- `contextual-help`: Get contextual help
- `help`: Get contextual help
- `suggest-tools`: Get adaptive suggestions
- `learn`: Start learning session
- `save-session`: Save session
- `load-session`: Load session
- `list-sessions`: List sessions
- `session-info`: Get session info
- `end-session`: End session
- `analyze-namespace-health`: Analyze namespace health
- `find-potential-forward-decl-issues`: Find forward declaration issues
- `validate-db-structure`: Validate db structure
- `profile-operation`: Profile operation
- `db-diff`: Compare db states
- `check-operation-result`: Check operation result
- `scan-for-errors`: Scan for errors
- `check-consistency`: Check consistency
- `test-operations-batch`: Test operations batch
- `health-check`: Perform health check
- `test-workflow`: Test workflow
- `inspect-references`: Inspect references
- `find-orphaned-references`: Find orphaned references
- `validate-reference-integrity`: Validate reference integrity
- `simulate-reference-hover`: Simulate reference hover
- `reference-stats`: Get reference stats
- `test-reference-operations`: Test reference operations
- `reference-health-check`: Reference health check
- `reload!`: Reload application
- `inspect-store`: Inspect store state
- `cljs!`: Execute ClojureScript code
- `trigger-action!`: Trigger action
- `select-node!`: Select node
- `apply-operation!`: Apply operation
- `watch-build!`: Watch build
- `cljs-repl`: Connect to browser REPL
- `inspect-node`: Inspect node
- `list-nodes`: List nodes
- `show-selection`: Show selection
- `show-references`: Show references
- `test-reference-ui`: Test reference UI
- `dev-status`: Show dev status
- `analyze-current-system`: Analyze current system
- `quick-diagnostics`: Run quick diagnostics
- `system-health-check`: Perform system health check
- `start-full-monitoring`: Start full monitoring
- `create-analysis-session`: Create analysis session
- `quick-start`: Quick start guide
- `pprint-db-diff`: Pretty print db diff
- `pprint-health-check`: Pretty print health check
- `pprint-analysis-result`: Pretty print analysis result
- `pprint-session-info`: Pretty print session info
- `pprint-monitor-status`: Pretty print monitor status

## agent.dev-tools

Enhanced development tools for ClojureScript workflow

### Functions
- `reload!`: Reload the application in the browser
- `inspect-store`: Inspect the current application store state
- `cljs!`: Execute arbitrary ClojureScript code in the browser
- `trigger-action!`: Trigger a replicant action in the browser
- `select-node!`: Select a specific node by ID
- `apply-operation!`: Apply a selected operation
- `watch-build!`: Start watching the frontend build
- `cljs-repl`: Connect to the browser REPL
- `inspect-node`: Inspect a specific node by ID
- `list-nodes`: List all node IDs in the current store
- `show-selection`: Show currently selected nodes
- `show-references`: Show current reference graph
- `test-reference-ui`: Test the reference UI by selecting nodes and adding a reference
- `simulate-keyboard-event!`: Simulate a keyboard event in the browser
- `test-keyboard-navigation`: Test basic keyboard navigation (arrow keys)
- `test-keyboard-operations`: Test keyboard operations (create, delete, etc.)
- `test-keyboard-selection`: Test keyboard selection operations
- `test-keyboard-references`: Test keyboard reference operations
- `keyboard-health-check`: Run comprehensive keyboard system health check
- `system-health-check`: Run comprehensive system health check
- `dev-status`: Show development environment status

## agent.store-inspector

Store inspection tools for debugging and analysis

### Functions
- `inspect-store`: Inspect the current store state with optional filtering
- `check-reference-integrity`: Check that references are bidirectional and valid
- `get-operation-history`: Get recent operation history with summaries
- `validate-current-selection`: Validate that the current selection is valid for operations
- `performance-metrics`: Get performance metrics for the store
- `quick-state-dump`: Quick dump of key state for debugging

## agent.reference-tools

Tools for debugging and inspecting the reference system

### Functions
- `inspect-references`: Inspect all references in the current database
- `find-orphaned-references`: Find references to nodes that don't exist
- `validate-reference-integrity`: Check that all references point to valid nodes
- `simulate-reference-hover`: Simulate hovering over a node to see what gets highlighted
- `reference-stats`: Get statistics about the reference system
- `test-reference-operations`: Test adding and removing references
- `reference-health-check`: Comprehensive health check for the reference system

## agent.enhanced-explorer

Enhanced interactive exploration tools with rich formatting and analysis capabilities.

### Functions
- `configure!`: Configure explorer behavior globally or for current session
- `analyze-store-comprehensive`: Comprehensive store analysis with rich formatting
- `interactive-diagnostics`: Guided interactive diagnostics
- `analyze-performance-trends`: Analyze performance trends over time

## agent.tool-composer

Tool composition system for chaining analysis operations.

### Functions
- `->>`: Thread-last macro for tool composition
- `->`: Thread-first macro for tool composition
- `pipeline`: Create a composable analysis pipeline
- `conditional-pipeline`: Create a pipeline that executes conditionally
- `parallel-pipeline`: Execute multiple pipelines in parallel
- `transform-result`: Transform pipeline results using a function
- `aggregate-results`: Aggregate multiple results into a single result
- `filter-results`: Filter results based on a predicate
- `execute-pipeline`: Execute a pipeline with given arguments
- `list-pipelines`: List all available pipelines

## agent.monitoring

Monitoring and alerting system for system health.

### Functions
- `create-monitor`: Create a new monitor
- `start-monitoring`: Start all monitors
- `stop-monitoring`: Stop all monitors
- `monitoring-status`: Get current monitoring status
- `monitoring-report`: Generate monitoring report
- `setup-basic-monitoring`: Setup basic monitoring suite
- `get-alerts`: Get current alerts

## agent.configuration

Configuration management system.

### Functions
- `get-config`: Get current configuration
- `set-config!`: Set configuration value
- `set-profile!`: Set active profile
- `save-config`: Save configuration to file
- `show-config`: Display current configuration
- `load-config`: Load configuration from file
- `reset-config!`: Reset to default configuration

## agent.context-help

Context-aware help and learning system.

### Functions
- `contextual-help`: Get help based on current context
- `adaptive-suggestions`: Get tool suggestions
- `learning-session`: Start interactive learning session
- `get-topic-help`: Get help on specific topic
- `search-help`: Search help content

## agent.session-persistence

Session persistence and management.

### Functions
- `save-session`: Save current session
- `load-session`: Load a saved session
- `list-sessions`: List all saved sessions
- `session-info`: Get session information
- `end-session`: End current session
- `cleanup-sessions`: Clean up old sessions

## agent.state-validation

State validation and health checking.

### Functions
- `comprehensive-health-check`: Run full health check
- `validate-state`: Validate current state
- `check-schema`: Check schema compliance
- `validate-transitions`: Validate state transitions

## agent.debug-helpers

Debug helpers for operations and consistency.

### Functions
- `db-diff`: Compare two database states
- `check-operation-result`: Check result of an operation
- `scan-for-errors`: Scan for errors in state
- `check-consistency`: Check state consistency
- `debug-operation`: Debug a specific operation

## agent.repl-workflow

REPL workflow tools for testing and development.

### Functions
- `test-operations-batch`: Test batch of operations
- `health-check`: Perform health check
- `test-workflow`: Test a workflow
- `profile-workflow`: Profile workflow performance

## agent.code-analysis

Code analysis tools for namespaces and dependencies.

### Functions
- `analyze-namespace-health`: Analyze namespace health
- `find-potential-forward-decl-issues`: Find forward declaration issues
- `validate-db-structure`: Validate database structure
- `profile-operation`: Profile operation performance

## agent.examples

Example usage patterns for agent utilities

### Examples
- Analyzing namespace health
- Database structure validation
- Performance profiling
- Debugging database changes
- Batch testing operations
- System health check
- Comprehensive system analysis
- Performance profiling with enhanced metadata
- Pretty printing results
- Quick diagnostics output