# Text Selection Utilities

Robust text selection and cursor positioning for contenteditable elements.

## Overview

The `util.text-selection` namespace provides utilities for working with text selection in contenteditable elements. It handles the complexity of the DOM Selection API and provides a simple, reliable interface for:

- Extracting plain text from complex DOM structures (text nodes, BR elements, nested elements)
- Getting cursor position and selection extent
- Creating precise Range objects at character positions
- Moving the cursor programmatically
- Handling multiline content with BR elements

**Ported from**: [use-editable](https://github.com/kitten/use-editable) - A battle-tested React hook for contenteditable.

## Why This Matters

Working with contenteditable elements is notoriously difficult:

1. **Complex DOM structure**: Content may contain text nodes, BR elements, and nested elements
2. **Selection API quirks**: Different browsers handle selection differently
3. **Cursor positioning**: Requires traversing text nodes and calculating offsets
4. **Replicant re-renders**: DOM updates can reset cursor position if not handled carefully

The `use-editable` library solved these problems for React. We've ported the core algorithms to ClojureScript for use with Replicant.

## Key Functions

### `element->text`

```clojure
(element->text element) => string
```

Extracts plain text content from a contenteditable element, handling:
- Text nodes
- BR elements (converted to `\n`)
- Nested elements
- Trailing newline (contenteditable quirk)

**Example**:
```clojure
;; HTML: "Hello<br>world"
(element->text elem) ;; => "Hello\nworld\n"
```

### `get-position`

```clojure
(get-position element) => {:position int :extent int :content string :line int}
```

Gets current cursor position information:
- `:position` - Absolute character offset from start
- `:extent` - Length of selection (0 if collapsed)
- `:content` - Text content of current line up to cursor
- `:line` - Zero-based line number

**Example**:
```clojure
;; Cursor after "Hello" in "Hello\nworld"
(get-position elem)
;; => {:position 5, :extent 0, :content "Hello", :line 0}
```

### `make-range`

```clojure
(make-range element start end) => Range
```

Creates a DOM Range object spanning character positions. Handles:
- Text node traversal
- BR element newlines
- Boundary conditions
- Negative positions (clamped to 0)

**Example**:
```clojure
;; Select "Hello" (positions 0-5)
(def range (make-range elem 0 5))
(set-current-range! range)
```

### `move-cursor!`

```clojure
(move-cursor! element pos)
```

Positions cursor at specified location. Accepts:
- Number: absolute character position
- Map: `{:row int :column int}` for line/column positioning

**Example**:
```clojure
;; Move to position 10
(move-cursor! elem 10)

;; Move to row 2, column 5
(move-cursor! elem {:row 2 :column 5})
```

### `get-state`

```clojure
(get-state element) => {:text string :position map}
```

Gets complete editor state (text content + position info).

## Integration with Block Component

The block component (`components.block`) uses these utilities for:

1. **Cursor positioning** (`:replicant/on-render` hook):
   ```clojure
   (let [text-content (text-sel/element->text node)
         text-length (count text-content)
         pos (cond
               (= cursor-pos :start) 0
               (= cursor-pos :end) text-length
               (number? cursor-pos) (min cursor-pos text-length)
               :else text-length)]
     (text-sel/set-current-range! (text-sel/make-range node pos pos)))
   ```

2. **Text extraction** (`:input` handler):
   ```clojure
   (let [new-text (text-sel/element->text target)
         position-info (text-sel/get-position target)
         cursor-pos (:position position-info)]
     ;; Update kernel with new text + cursor position
     )
   ```

3. **Selection detection** (`has-text-selection?`):
   ```clojure
   (when-let [range (text-sel/get-current-range)]
     (not (.-collapsed range)))
   ```

## Benefits Over Manual DOM Manipulation

### Before (manual Selection API)

```clojure
;; Fragile - assumes single text node
(let [selection (.getSelection js/window)
      cursor-pos (.-anchorOffset selection)]
  ;; What if there are multiple text nodes?
  ;; What if there are BR elements?
  ;; What if selection is in a nested span?
  )
```

### After (robust utilities)

```clojure
;; Handles all DOM structures correctly
(let [position-info (text-sel/get-position elem)
      cursor-pos (:position position-info)]
  ;; Always correct, regardless of DOM structure
  )
```

## Browser Compatibility

The utilities handle browser quirks:

1. **Firefox**: Doesn't support `contenteditable="plaintext-only"`, so we handle BR elements manually
2. **Chrome/Safari**: May have different Selection API behavior
3. **All browsers**: Trailing newline requirement for `pre-wrap` elements

See `use-editable` source comments for detailed browser quirk documentation.

## Testing

### Unit Tests

Unit tests are limited because they require a DOM environment:

```clojure
;; test/util/text_selection_test.cljs
(deftest placeholder-test
  (testing "Text selection utilities require DOM"
    (is true "See test/e2e/text-selection.spec.js for browser-based tests")))
```

### E2E Tests

Comprehensive browser-based tests in `test/e2e/text-selection.spec.js`:

- Cursor preservation during rapid typing
- Text selection with keyboard shortcuts
- BR element handling for newlines
- Arrow key navigation
- Paste operations
- Selection collapse behavior

**Run tests**:
```bash
bb e2e text-selection
```

## Common Patterns

### Getting current cursor position

```clojure
(when-let [position-info (text-sel/get-position elem)]
  (let [pos (:position position-info)
        line (:line position-info)
        extent (:extent position-info)]
    ;; Use position info
    ))
```

### Selecting a range of text

```clojure
;; Select characters 5-10
(let [range (text-sel/make-range elem 5 10)]
  (text-sel/set-current-range! range))
```

### Moving cursor to end

```clojure
(let [text (text-sel/element->text elem)
      length (count text)]
  (text-sel/move-cursor! elem (dec length))) ; -1 for trailing newline
```

### Checking if text is selected

```clojure
(if-let [range (text-sel/get-current-range)]
  (when-not (.-collapsed range)
    ;; Text is selected
    (let [selected-text (.toString range)]
      ;; Do something with selected text
      ))
  ;; No selection
  )
```

## Debugging

Enable debug logging:

```clojure
;; Log position changes
(let [pos (text-sel/get-position elem)]
  (js/console.log "Position:" (clj->js pos)))

;; Log full state
(let [state (text-sel/get-state elem)]
  (js/console.log "State:" (clj->js state)))
```

## References

- [use-editable source](https://github.com/kitten/use-editable) - Original implementation
- [MDN Selection API](https://developer.mozilla.org/en-US/docs/Web/API/Selection)
- [MDN Range API](https://developer.mozilla.org/en-US/docs/Web/API/Range)
- [contenteditable quirks](https://medium.engineering/why-contenteditable-is-terrible-122d8a40e480)

## Future Improvements

Potential enhancements (not currently needed):

1. **MutationObserver integration**: Track DOM changes automatically (like `use-editable` does)
2. **Undo/redo history**: Character-level undo with position tracking
3. **IME composition**: Better handling of multi-byte character input
4. **Indentation**: Smart tab handling with configurable indent width
5. **Syntax highlighting**: Coordinate with content changes

For now, we only use the core position/selection utilities. The block component handles higher-level concerns like undo/redo through the kernel's event sourcing.
