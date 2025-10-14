# Research Report: Claude Code Plugin and Hook System

## Executive Summary

Claude Code provides a powerful plugin and hook system (released June 2025) that enables workflow automation through shell commands triggered at specific lifecycle events. The system supports 9 hook types (PreToolUse, PostToolUse, UserPromptSubmit, SessionStart, etc.), custom slash commands, specialized subagents, and MCP server integration. For our Anki review-flow workflow, we can leverage hooks to automatically track review sessions, validate card modifications, sync state to external storage, and enforce data integrity - all without modifying core application logic.

---

## Focus Area 1: Hook System Architecture

### Library Documentation

**Available Hook Types** (from official Claude Code docs):
1. **PreToolUse**: Runs before tool execution - can block tools via exit codes
2. **PostToolUse**: Runs after tool completion - captures results
3. **UserPromptSubmit**: Fires when user submits a prompt
4. **Notification**: Triggered when Claude sends notifications
5. **Stop**: Fires when Claude finishes responding
6. **SubagentStop**: Fires when a subagent completes
7. **PreCompact**: Before context compaction
8. **SessionStart**: At session initialization
9. **SessionEnd**: At session completion

**Configuration Format**:
\`\`\`json
{
  "hooks": {
    "EventName": [
      {
        "matcher": "ToolPattern",  // Regex or exact match
        "hooks": [
          {
            "type": "command",
            "command": "path/to/script.py",
            "timeout": 60  // Optional, default 60s
          }
        ]
      }
    ]
  }
}
\`\`\`

**Environment Variables**:
- \`CLAUDE_PROJECT_DIR\`: Absolute path to project root
- \`CLAUDE_PLUGIN_ROOT\`: Absolute path to plugin directory

**Input/Output**:
- Hook receives JSON via stdin with \`tool_name\`, \`tool_input\`, \`tool_response\`
- Exit codes: 0 = success/continue, 2 = block tool execution (PreToolUse only)
- Can output JSON to modify tool inputs (v2.0.10+)

### Real-World Usage

**Example 1**: Chief of Staff Agent - Report tracking
\`\`\`python
# PostToolUse hook for Write/Edit tools
def track_report(tool_name, tool_input, tool_response):
    file_path = tool_input.get("file_path", "")
    history = load_or_create_history()
    history["reports"].append({
        "timestamp": datetime.now().isoformat(),
        "file": os.path.basename(file_path),
        "action": "created" if tool_name == "Write" else "modified",
        "word_count": len(content.split())
    })
    save_history(history)
\`\`\`

**Example 2**: Validation Hook - Block restricted files
\`\`\`python
# PreToolUse hook to prevent editing sensitive files
import sys, json
data = json.load(sys.stdin)
path = data.get('tool_input', {}).get('file_path', '')
sys.exit(2 if any(p in path for p in ['.env', 'package-lock.json', '.git/']) else 0)
\`\`\`

**Example 3**: Auto-formatting
\`\`\`json
{
  "PostToolUse": [{
    "matcher": "Write|Edit",
    "hooks": [{"type": "command", "command": "npx prettier --write $file_path"}]
  }]
}
\`\`\`

### Current Best Practices (2024-2025)

**Security First** (from official docs):
> "You must consider the security implication of hooks as you add them, because hooks run automatically during the agent loop with your current environment's credentials."

**Best Practices**:
1. **Always exit 0 for PostToolUse hooks** - Don't break workflows on logging failures
2. **Use PreToolUse for validation only** - Block dangerous operations, not log them
3. **Validate inputs** - Check file paths, command patterns before acting
4. **Use environment variables** - \`$CLAUDE_PROJECT_DIR\` for portability
5. **Log to stderr for debug** - Keep stdout for user-facing messages
6. **Keep hooks fast** - Default 60s timeout; aim for <5s
7. **Test with \`claude --debug\`** - Shows detailed hook execution logs

### Recommendation for Anki Workflow

**Use hooks for cross-cutting concerns**:
1. **PostToolUse:Write/Edit** - Track card file modifications, sync to backup
2. **PreToolUse:Bash** - Validate commands that modify review data
3. **SessionEnd** - Export session summary (cards reviewed, success rate)
4. **Stop** - Auto-save event log to external storage

**Don't use hooks for**:
- Core business logic (keep in \`lab.anki.core.cljc\`)
- Performance-critical operations (hook overhead ~50-200ms)
- Complex workflows requiring multiple steps (use subagents instead)

---

## Focus Area 2: Plugin System

### Library Documentation

**Plugin Components**:

1. **Commands** (\`commands/\` directory)
   - Markdown files with YAML frontmatter
   - Add custom slash commands (e.g., \`/commit-push-pr\`)
   - Can specify allowed tools, context queries

2. **Agents** (\`agents/\` directory)
   - Markdown files describing specialized subagents
   - Define role, responsibilities, tools, model
   - Auto-invoked or manually called

3. **Hooks** (\`hooks/hooks.json\` or inline in \`plugin.json\`)
   - Event handlers for tool lifecycle
   - Shell commands triggered automatically

4. **MCP Servers** (\`.mcp.json\`)
   - Connect Claude Code with external tools/services
   - Auto-start when plugin enabled

**Plugin Manifest** (\`plugin.json\`):
\`\`\`json
{
  "name": "enterprise-plugin",
  "version": "1.0.0",
  "description": "Internal workflows and compliance",
  "author": "Your Team",
  "homepage": "https://github.com/your-org/plugin",
  "commands": "./commands",
  "agents": "./agents",
  "hooks": "./hooks/hooks.json"
}
\`\`\`

### Recommendation for Anki Workflow

**Create an "anki-review" plugin** (when reusing across projects):
\`\`\`
anki-review-plugin/
├── .claude-plugin/
│   └── plugin.json
├── commands/
│   ├── review-session.md    # Start review session
│   ├── export-stats.md      # Export review stats
│   └── card-audit.md        # Audit card consistency
├── agents/
│   └── review-analyzer.md   # Analyze review patterns
├── hooks/
│   ├── hooks.json
│   └── scripts/
│       ├── track-review.clj   # Track review events
│       ├── validate-card.clj  # Validate card syntax
│       └── backup-state.sh    # Backup to external storage
└── .mcp.json                # Optional: connect to external SRS API
\`\`\`

---

## Focus Area 3: Real-World Plugin Examples

### Key Patterns

**Pattern 1: TDD Enforcement Hook**
\`\`\`json
{
  "PreToolUse": [{
    "matcher": "Write|Edit",
    "hooks": [{
      "command": ".claude/hooks/ensure-test-exists.py",
      "description": "Ensure test file exists before implementation"
    }]
  }]
}
\`\`\`

**Pattern 2: Git Auto-Commit Hook**
\`\`\`json
{
  "PostToolUse": [{
    "matcher": "Write|Edit|MultiEdit",
    "hooks": [{
      "command": ".claude/hooks/git-auto-commit.sh",
      "description": "Auto-commit after file changes"
    }]
  }]
}
\`\`\`

**Pattern 3: Observability Pattern** - Track all events externally
\`\`\`python
def send_event(hook_type, data):
    try:
        requests.post("http://localhost:3000/api/events", 
                     json={"type": hook_type, "data": data},
                     timeout=1)
    except:
        pass  # Don't block workflow on observability failure
\`\`\`

---

## Focus Area 4: Use Cases for Anki Workflow

### Our Current Architecture

**Core System** (\`lab.anki.core.cljc\`):
- Event-sourced: \`:card-created\`, \`:review\`, \`:undo\`, \`:redo\`
- Pure functions: \`parse-card\`, \`schedule-card\`, \`reduce-events\`
- Undo/redo stacks built from event log
- Card metadata: \`created-at\`, \`due-at\`, \`reviews\`, \`last-rating\`

### Hook Integration Points

**1. PostToolUse:Write/Edit - Card File Tracking**
\`\`\`clojure
;; hooks/track-card-changes.clj
;; Track modifications to cards/*.md files
(defn track-card-change [tool-name file-path]
  (when (str/starts-with? file-path "cards/")
    (let [card-text (slurp file-path)
          card-data (parse-card card-text)
          card-hash (card-hash card-data)]
      ;; Append to audit log
      (spit "audit/card-changes.jsonl"
            (json/write-str {:timestamp (now-ms)
                            :file file-path
                            :card-hash card-hash
                            :action (if (= tool-name "Write") "created" "modified")})
            :append true))))
\`\`\`

**2. SessionEnd - Export Review Summary**
\`\`\`clojure
;; hooks/export-session-summary.clj
;; Generate markdown report of review session
(defn export-summary [events]
  (let [reviews (filter #(= :review (:event/type %)) events)
        stats {:total-reviews (count reviews)
               :by-rating (frequencies (map #(get-in % [:event/data :rating]) reviews))
               :duration-ms (- (:event/timestamp (last events))
                              (:event/timestamp (first events)))}]
    (spit (str "reviews/session-" (now-ms) ".md")
          (format-summary stats))))
\`\`\`

**3. PreToolUse:Bash - Validate Review Commands**
\`\`\`clojure
;; hooks/validate-review-command.clj
;; Block dangerous bash commands during review
(defn validate-command [command]
  (let [dangerous-patterns #{"rm -rf" "rm cards/" "rm events.edn"}]
    (if (some #(str/includes? command %) dangerous-patterns)
      (do (println "⛔ Blocked dangerous command:" command)
          (System/exit 2)) ; Block execution
      (System/exit 0))))
\`\`\`

**4. Stop - Backup Event Log**
\`\`\`bash
#!/bin/bash
# hooks/backup-event-log.sh
# Copy event log to timestamped backup
cp events.edn "backups/events-$(date +%s).edn"
echo "📦 Event log backed up"
\`\`\`

---

## Actionable Recommendations

### 1. Add Session Backup Hook (Immediate - 15 min)

**Do this**: Create \`.claude/hooks/backup-event-log.sh\`
\`\`\`bash
#!/bin/bash
timestamp=$(date +%Y%m%d-%H%M%S)
mkdir -p backups
cp events.edn "backups/events-$timestamp.edn"
echo "📦 Backed up event log to backups/events-$timestamp.edn"
\`\`\`

**Configure in** \`.claude/settings.local.json\`:
\`\`\`json
{
  "hooks": {
    "SessionEnd": [{
      "matcher": "*",
      "hooks": [{"command": "$CLAUDE_PROJECT_DIR/.claude/hooks/backup-event-log.sh"}]
    }]
  }
}
\`\`\`

**Why**: Prevents data loss, minimal overhead, proven pattern from real projects.

### 2. Add Card Change Tracking Hook (Next - 30 min)

**Do this**: Create \`.claude/hooks/track-card-changes.clj\`
\`\`\`clojure
#!/usr/bin/env bb
(ns hooks.track-card-changes
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn track-change [tool-name file-path]
  (when (and file-path (re-find #"cards/.*\\.md$" file-path))
    (let [entry {:timestamp (System/currentTimeMillis)
                 :file file-path
                 :action (if (= tool-name "Write") "created" "modified")}]
      (spit "audit/card-changes.jsonl"
            (str (json/write-str entry) "\\n")
            :append true)
      (println "📝 Tracked card change:" file-path))))

(let [input (json/read-str (slurp *in*))
      tool-name (get input "tool_name")
      file-path (get-in input ["tool_input" "file_path"])]
  (track-change tool-name file-path))
\`\`\`

**Configure**:
\`\`\`json
{
  "PostToolUse": [{
    "matcher": "Write|Edit",
    "hooks": [{"command": "$CLAUDE_PROJECT_DIR/.claude/hooks/track-card-changes.clj"}]
  }]
}
\`\`\`

**Why**: Provides audit trail for debugging, catches external modifications, basis for future conflict detection.

### 3. Avoid These Anti-Patterns

**Don't do**: Put review logic in hooks
- ❌ Wrong: Calculate due dates in PostToolUse hook
- ✅ Right: Keep \`schedule-card\` in \`core.cljc\`, hook just logs

**Don't do**: Block tools for validation (yet)
- ❌ Wrong: Add PreToolUse to validate card syntax (adds latency)
- ✅ Right: Use clj-kondo or tests for validation

**Don't do**: Chain hooks together
- ❌ Wrong: Hook A calls Hook B calls Hook C
- ✅ Right: Each hook is independent, state-free

---

## References

### Library Documentation
- [Hooks Reference](https://docs.claude.com/en/docs/claude-code/hooks) - Complete hook types, configuration
- [Hooks Guide](https://docs.claude.com/en/docs/claude-code/hooks-guide) - Getting started, examples
- [Plugins Reference](https://docs.claude.com/en/docs/claude-code/plugins-reference) - Plugin structure

### Code Examples
- [claude-code-hooks-mastery](https://github.com/disler/claude-code-hooks-mastery) - Comprehensive examples
- [claude-code-hooks-multi-agent-observability](https://github.com/disler/claude-code-hooks-multi-agent-observability) - Real-time monitoring
- ~/Projects/best/claude-cookbooks/claude_code_sdk/chief_of_staff_agent/ - Production hooks

---

**Research completed**: 2025-10-13

**Key findings**: Claude Code hooks provide powerful workflow automation without modifying core logic. For Anki review flow, start with SessionEnd backup hook and PostToolUse card tracking. Keep core logic in Clojure, use hooks only for observability and automation. Defer plugin packaging and PreToolUse validation until proven necessary.
