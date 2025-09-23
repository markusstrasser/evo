# Agent Gotchas: MCP + REPL Development

## Actual Errors From This Session

### 1. ClojureScript REPL Context Confusion
**Error I Made**: Tried `(shadow/repl :frontend)` from ClojureScript REPL
```
------ WARNING - :undeclared-ns ------------------------------------------------
No such namespace: shadow, could not locate shadow.cljs
```
**Reality**: The `clojurescript_eval` tool IS the ClojureScript REPL. Don't try to connect again.

### 2. Missing Namespace Requires  
**Error I Made**: Used `r/render` without requiring the namespace
```
------ WARNING - :undeclared-var -----------------------------------------------
Use of undeclared Var r/render
```
**Fix**: Always require first
```clojure
(require '[replicant.dom :as r])
```

### 3. Wrong shadow-cljs API Attempt
**Error I Made**: Tried `(require '[shadow.cljs.devtools.api :as shadow])`
```
The required namespace "shadow.cljs.devtools.api" is not available
"shadow/cljs/devtools/api.clj" was found on the classpath. Maybe this library only supports CLJ?
```
**Reality**: That's a Clojure-only namespace. In ClojureScript REPL, just work directly.

### 5. File Write Safety Check
**Error I Made**: Tried to overwrite `agent.md` without reading it first
```
File has been modified since last read: /Users/alien/Projects/evo/agent.md
Please read the WHOLE file again with `collapse: false` before editing.
```
**Fix**: Always read file before writing to prevent overwrites.

### 6. DataScript `:db/isComponent` Backwards Logic
**Error I Made**: Used `:db/isComponent true` on `:parent` attribute expecting parent deletion to cascade to children
```clojure
{:parent {:db/valueType :db.type/ref :db/isComponent true}}
```
**Reality**: `:db/isComponent true` means "when you delete THIS entity, also delete the entity it points to". So deleting a child deletes its parent!

**What Actually Happens**: 
- Child has `:parent` with `:db/isComponent true` 
- Deleting child → deletes parent → orphans all other children
- This is backwards from intuitive parent-child relationship

**Fix**: Remove `:db/isComponent` and implement manual recursive deletion:
```clojure
{:parent {:db/valueType :db.type/ref}} ; No isComponent!

(defn delete! [conn entity-id]
  ;; Find all descendants recursively and delete them manually
  (let [descendants (find-all-descendants db entity-id)]
    (d/transact! conn (mapv #([:db/retractEntity [:id %]]) descendants))))
```

## Key Takeaways

1. **Don't try to "connect" to ClojureScript REPL** - You're already in it
2. **Require namespaces before using** - Even obvious ones like replicant  
3. **Check atom state first** - `@store` before assuming values
4. **Read files before editing** - MCP safety mechanism
5. **ClojureScript ≠ Clojure** - Different available namespaces
6. **`:db/isComponent` works backwards** - Child deletion cascades UP to parent, not down