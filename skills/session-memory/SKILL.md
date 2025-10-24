---
name: Session Memory & Analysis
description: Read, analyze, and extract insights from Claude Code conversation sessions. Find reusable patterns, extract potential skills, search conversation history. Triggers on session, conversation, history, memory, extract skills. Requires Babashka and access to ~/.claude/projects/.
---

# Session Memory & Analysis

## Prerequisites

**Tools:**
- `bb` (Babashka) - For session parsing/analysis
- `jq` - JSON processing (optional, for manual inspection)

**Data:**
- Claude Code sessions in `~/.claude/projects/`
- JSONL format conversation logs

**No API keys required** - purely local analysis

## Quick Start

```bash
# List recent sessions for current project
./run.sh list

# Read specific session
./run.sh read 0c7b3880-e100-49c2-983b-1aa4ff2bb82e

# Analyze session for insights
./run.sh analyze 0c7b3880-e100-49c2-983b-1aa4ff2bb82e

# Search across all sessions
./run.sh search "kernel IR" "compounding"

# Extract potential skills from successful workflows
./run.sh skills 0c7b3880-e100-49c2-983b-1aa4ff2bb82e

# Export session to markdown
./run.sh export 0c7b3880-e100-49c2-983b-1aa4ff2bb82e --format markdown
```

## When to Use

Use this skill when you need to:
- **Review past conversations** - Find what worked in previous sessions
- **Extract reusable patterns** - Turn ad-hoc work into skills
- **Search conversation history** - Find when you solved similar problems
- **Analyze agent behavior** - Understand what tools/patterns were used
- **Build memory/context** - Create knowledge base from sessions
- **Export conversations** - Share or document work

## Available Commands

### list - List Sessions

```bash
# List recent sessions for current project
./run.sh list

# List all sessions (all projects)
./run.sh list --all

# List with summaries
./run.sh list --verbose

# Filter by date
./run.sh list --since "2025-10-01"
```

Output shows:
- Session ID
- Timestamp (last modified)
- Message count
- Summary (if available)

### read - Read Session

```bash
# Read full session
./run.sh read <session-id>

# Read recent messages only
./run.sh read <session-id> --limit 10

# Read user messages only
./run.sh read <session-id> --role user

# Show tool usage
./run.sh read <session-id> --show-tools
```

Displays:
- Message threading (parent/child)
- User/assistant roles
- Tool invocations and results
- Thinking blocks (optional)

### analyze - Analyze Session

```bash
# Full analysis
./run.sh analyze <session-id>

# Specific analysis type
./run.sh analyze <session-id> --type tools      # Tool usage patterns
./run.sh analyze <session-id> --type skills     # Potential skills
./run.sh analyze <session-id> --type errors     # Error patterns
```

Extracts:
- **Tool usage** - Which tools were used, frequency, success rate
- **Workflows** - Common command sequences
- **Patterns** - Repeated problem-solving approaches
- **Errors** - Issues encountered and how resolved
- **Insights** - Key learnings and decisions

### search - Search Sessions

```bash
# Semantic search (finds conceptually similar messages)
./run.sh search --sem "kernel IR architecture" --limit 5

# Lexical search (BM25 full-text)
./run.sh search --lex "three-op kernel" --limit 10

# Hybrid search (best of both, default)
./run.sh search --hybrid "compounding patterns" --threshold 0.7

# Show similarity scores
./run.sh search --sem --scores "event sourcing"

# Plain text search (fallback to ripgrep)
./run.sh search "specific text string"
```

**Search modes:**
- `--sem` - Semantic search using embeddings (best for concepts)
- `--lex` - Lexical BM25 search (best for exact phrases)
- `--hybrid` - Combines both (recommended, default)

**Options:**
- `--limit N` - Return top N results (default: 10)
- `--threshold X` - Minimum similarity score (default: 0.6)
- `--scores` - Show similarity scores in output
- `--context N` - Show N lines of context (ripgrep only)

**Note:** First search will build index automatically. Use `bb rebuild-index` to refresh embeddings.

### skills - Extract Skills

```bash
# Extract potential skills from session
./run.sh skills <session-id>

# Generate skill template
./run.sh skills <session-id> --generate

# Interactive skill creation
./run.sh skills <session-id> --interactive
```

Identifies:
- Repeated workflows
- Successful tool compositions
- Reusable patterns
- Command sequences

### export - Export Session

```bash
# Export to markdown
./run.sh export <session-id> --format markdown

# Export to JSON
./run.sh export <session-id> --format json

# Export to HTML
./run.sh export <session-id> --format html

# Export with filters
./run.sh export <session-id> --format markdown --hide-thinking
```

## Session File Format

Claude Code stores sessions as JSONL files in:
```
~/.claude/projects/-Users-alien-Projects-{project}/
```

Each line is a JSON record:

```clojure
{:type "user" | "assistant" | "system" | "file-history-snapshot"
 :uuid "message-uuid"
 :parentUuid "parent-message-uuid"  ; conversation threading
 :sessionId "session-uuid"
 :timestamp "2025-10-21T15:49:00.000Z"
 :message {:role "user" | "assistant"
           :content [...blocks]}}
```

Content blocks can be:
- `{:type "text" :text "..."}` - Plain text
- `{:type "thinking" :thinking "..."}` - Internal reasoning
- `{:type "tool_use" :name "..." :input {...}}` - Tool invocation
- `{:type "tool_result" :content "..."}` - Tool response

## Babashka API

The skill provides reusable Babashka libraries in `lib/`:

### session.clj - Core Session Reading

```clojure
(require '[session-memory.session :as session])

;; List sessions
(session/list-sessions)
;; => [{:id "0c7b..." :project "/path" :modified "2025-10-21" :messages 1175}]

;; Read session
(session/read-session "0c7b3880-e100-49c2-983b-1aa4ff2bb82e")
;; => [{:type "user" :message {...} :uuid "..."} ...]

;; Extract messages only
(session/messages "0c7b3880-e100-49c2-983b-1aa4ff2bb82e")
;; => [{:role "user" :content "..." :timestamp "..."} ...]

;; Get conversation tree
(session/thread-tree "0c7b3880-e100-49c2-983b-1aa4ff2bb82e")
;; => Nested message tree structure
```

### analysis.clj - Session Analysis

```clojure
(require '[session-memory.analysis :as analysis])

;; Analyze tool usage
(analysis/tool-stats "session-id")
;; => {:Bash {:count 47 :success-rate 0.95}
;;     :Read {:count 23 :success-rate 1.0} ...}

;; Extract workflows
(analysis/extract-workflows "session-id")
;; => [{:pattern ["Glob" "Read" "Edit"] :frequency 5} ...]

;; Find errors
(analysis/error-patterns "session-id")
;; => [{:error "..." :resolution "..." :tools-used [...]} ...]

;; Extract potential skills
(analysis/suggest-skills "session-id")
;; => [{:name "..." :description "..." :steps [...]} ...]
```

### search.clj - Search Engine

```clojure
(require '[session-memory.search :as search])

;; Search across sessions
(search/query "kernel IR" "compounding")
;; => [{:session-id "..." :message-id "..." :match "..." :context "..."} ...]

;; Search with filters
(search/query "REPL" {:project-only true :role "assistant"})

;; Build index (for faster search)
(search/build-index!)
```

### export.clj - Export Utilities

```clojure
(require '[session-memory.export :as export])

;; Export to markdown
(export/to-markdown "session-id" {:hide-thinking true})

;; Export to JSON
(export/to-json "session-id" {:pretty true})

;; Export conversation tree
(export/tree-view "session-id")
```

## Integration with Other Skills

### With code-research

Extract research queries from past sessions:

```bash
# Find all repomix + llmx workflows
./run.sh search "repomix" "llmx" | grep "tool_use"
```

### With diagnostics

Build error catalog from session history:

```bash
# Extract all errors and resolutions
./run.sh analyze <session-id> --type errors >> dev/error-catalog.edn
```

### With architect

Find architectural decisions made in past sessions:

```bash
# Search architectural discussions
./run.sh search "architecture" "design decision" "tradeoff"
```

## Common Patterns

### Pattern 1: Extract Successful Workflow as Skill

```bash
# 1. Find session where you solved the problem
./run.sh search "babashka conversion"

# 2. Analyze for workflow
./run.sh analyze <session-id> --type skills

# 3. Generate skill template
./run.sh skills <session-id> --generate > skills/bb-convert/SKILL.md
```

### Pattern 2: Build Knowledge Base

```bash
# Extract all insights from recent sessions
for session in $(./run.sh list --limit 10 | awk '{print $1}'); do
  ./run.sh analyze $session >> data/knowledge-base.edn
done
```

### Pattern 3: Session Comparison

```bash
# Compare two approaches to same problem
./run.sh export <session-1> --format json > /tmp/s1.json
./run.sh export <session-2> --format json > /tmp/s2.json
# Use diff/analysis tools
```

### Pattern 4: Tool Usage Analytics

```bash
# Analyze tool usage across all sessions
./run.sh list | while read session_id _; do
  ./run.sh analyze $session_id --type tools
done | jq -s 'group_by(.tool) | map({tool: .[0].tool, total: map(.count) | add})'
```

## Configuration

**Environment variables:**

```bash
# Override Claude projects directory
export CLAUDE_PROJECTS_DIR="$HOME/.claude/projects"

# Current project auto-detection
# Uses current working directory to find project sessions

# Cache directory for search index
export SESSION_CACHE_DIR=".cache/sessions"
```

**Config file (optional):**

```edn
;; data/config.edn
{:search {:index-enabled true
          :cache-ttl 3600}
 :export {:default-format :markdown
          :hide-thinking true}
 :analysis {:tool-threshold 3
            :workflow-min-length 2}}
```

## Tips & Best Practices

1. **Use search before re-inventing** - You may have solved this before
2. **Extract skills proactively** - Don't wait, capture workflows immediately
3. **Build session summaries** - Run analysis regularly to build context
4. **Tag sessions** - Add metadata for easier categorization (future feature)
5. **Export important sessions** - Backup breakthrough conversations

## Common Pitfalls

- **Session IDs are UUIDs** - Use tab completion or `list` command
- **Large sessions are slow** - Use `--limit` for recent messages only
- **No write access** - This is read-only, won't modify sessions
- **Stale cache** - Clear `.cache/sessions/` if search is outdated

## Troubleshooting

**"Session not found"**
- Verify session ID: `./run.sh list`
- Check project path: Sessions are project-scoped
- Look in other projects: `./run.sh list --all`

**"Slow search"**
- Build index first: `./run.sh search --build-index`
- Limit search scope: `--project-only`

**"Parse errors"**
- Claude may have updated JSONL format
- Check `lib/session.clj` for schema
- Report issue with session ID

**"Empty results"**
- Session may be from different project
- Try `--all` flag
- Verify session has content: `wc -l ~/.claude/projects/.../<id>.jsonl`

## Resources (Level 3)

- `run.sh` - Main CLI wrapper
- `lib/session.clj` - Core session parsing
- `lib/analysis.clj` - Analysis algorithms
- `lib/search.clj` - Search engine
- `lib/export.clj` - Export utilities
- `examples/` - Usage examples
- `data/config.edn` - Configuration (optional)

## See Also

- Project docs: `../../CLAUDE.md#skills`
- Claude Code sessions: `~/.claude/projects/`
- Session SDK: https://docs.claude.com/en/docs/claude-code/sdk/sdk-sessions
- Conversation extractor: https://github.com/ZeroSumQuant/claude-conversation-extractor

## Future Enhancements

- **Session tagging** - Add metadata/tags to sessions
- **Semantic search** - Embedding-based search (requires API)
- **Auto-skill extraction** - Automatic skill generation from patterns
- **Session forking** - Create new sessions from historical points
- **Conversation visualization** - Thread tree visualization
