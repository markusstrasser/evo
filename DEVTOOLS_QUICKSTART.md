# DevTools V2 - Quick Start Guide

## What's New?

The devtools UI has been completely redesigned with Dataspex integration. Instead of plain text dumps, you now get:

- **Interactive tree view** - Expand/collapse data structures
- **Proper diffs** - See exactly what changed with color coding
- **Visual state inspection** - Compare DB vs rendered UI
- **Dataspex integration** - Professional data browser

## Getting Started

### 1. Start the app

```bash
npm start
```

Open http://localhost:8080/blocks-ui.html

### 2. Use the app

The enhanced devtools panel appears at the bottom of the page. Try:
- Type some text
- Create new blocks (Enter)
- Move blocks (Cmd+Shift+Up/Down)
- Select blocks (Shift+Up/Down)

Each action creates a log entry.

### 3. Inspect an operation

1. Look at the **Operation Log** section
2. Click any entry (e.g., "UPDATE-TEXT")
3. See the before/after diff

### 4. Change view modes

Use the buttons to switch between:
- **Split View** - Before/after side-by-side
- **Diff Only** - Just the changes
- **Tree Only** - Current state

### 5. Use Dataspex (Optional)

1. Install [Dataspex Chrome Extension](https://chromewebstore.google.com/detail/dataspex/blgomkhaagnapapellmdfelmohbalneo)
2. Open Chrome DevTools → Dataspex panel
3. Click "Inspect in Dataspex" button in the devtools
4. Explore your data with Dataspex's rich UI

## Key Features

### Interactive Tree View

Click the **▶** arrows to expand nested data:

```
▶ root
  ▶ nodes {10 keys}
    ▶ "block-1" {2 keys}
      - type :block
      ▶ props {2 keys}
        - text "Hello world"
```

### Color-Coded Diffs

Changes are highlighted:
- 🟢 **Green** - Added data
- 🔴 **Red** - Removed data
- 🟡 **Yellow** - Modified data

### DB State Dashboard

Quick overview shows:
- Total Nodes
- Block count
- Page count
- Selection state
- Editing state

## REPL Usage

### Inspect current DB

```clojure
;; From ClojureScript REPL
(require '[dataspex.core :as dataspex])
(dataspex/inspect "Current DB" @shell.blocks-ui/!db)

;; From browser console
DEBUG.state()
```

### Compute diffs manually

```clojure
(require '[dev.data-diff :as dd])

;; Get two states
(def before {...})
(def after {...})

;; Compute diff
(dd/compute-diff before after)
;; => {:before {...}
;;     :after {...}
;;     :diff <editscript>
;;     :changes [{:type :+ :path [...] :value ...}]}
```

### Compare visual states

```clojure
;; Extract what user should see
(dd/extract-visual-state db "page-id")

;; Compare before/after visual states
(dd/compare-visual-states db-before db-after "page-id")

;; Detect mismatch between DB and visual rendering
(dd/detect-db-visual-mismatch db expected-visual "page-id")
```

## Debugging Workflow

### Problem: UI doesn't match what I expect

1. Perform the buggy action
2. Select the log entry in devtools
3. Switch to **Diff Only** mode
4. Check what actually changed vs what you expected
5. Use `dd/compare-visual-states` to see visual diff

### Problem: Need to inspect complex nested data

1. Click "Inspect in Dataspex" button
2. Use Dataspex's professional UI to navigate
3. View audit trail for full history

### Problem: Can't see why something failed

1. Check browser console for errors
2. Look at the **DB State** metrics (are counts correct?)
3. Expand tree view to find unexpected structure
4. Use REPL to query: `(DEBUG.node "block-id")`

## File Locations

- **UI Component**: `src/components/devtools_v2.cljs`
- **Diff Utilities**: `src/dev/data_diff.cljs`
- **Operation Logging**: `src/dev/tooling.cljs`
- **Wired in**: `src/shell/blocks_ui.cljs`
- **Documentation**: `docs/DEVTOOLS_V2.md`

## Next Steps

- Read full docs: `docs/DEVTOOLS_V2.md`
- Watch Dataspex tour: https://youtu.be/5AKvD3nGCYY
- Try the REPL helpers in `dev.data-diff`
- Install Dataspex browser extension for advanced inspection

## Tips

- **Use keyboard shortcuts** - All operations create log entries automatically
- **Clear log regularly** - Click "Clear Log" to reset
- **Expand selectively** - Tree view is lazy, only expands what you click
- **Compare states** - Use Split View to spot differences easily
- **Send to Dataspex** - For really complex data, use Dataspex's advanced UI

Enjoy your enhanced debugging experience! 🛠️
