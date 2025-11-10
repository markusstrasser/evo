# Word Navigation & Kill Commands - Spec

**Goal:** Add Emacs-style text editing shortcuts for word-level navigation and deletion.

**Status:** ❌ Not implemented

---

## Feature Overview

### Word Navigation (Move cursor by word)

| Shortcut | Action | Platform |
|----------|--------|----------|
| `Alt+F` | Move cursor forward by word | Linux/Win |
| `Ctrl+Shift+F` | Move cursor forward by word | Mac |
| `Alt+B` | Move cursor backward by word | Linux/Win |
| `Ctrl+Shift+B` | Move cursor backward by word | Mac |

### Kill Commands (Delete text to clipboard)

| Shortcut | Action | Platform |
|----------|--------|----------|
| `Ctrl+L` | Clear entire block content | Mac |
| `Alt+L` | Clear entire block content | Linux/Win |
| `Ctrl+U` | Kill from cursor to beginning of block | Mac |
| `Alt+U` | Kill from cursor to beginning of block | Linux/Win |
| `Alt+K` | Kill from cursor to end of block | Linux/Win only |
| `Ctrl+W` | Kill word forward | Mac |
| `Alt+D` | Kill word forward | Linux/Win |
| `Alt+W` | Kill word backward | Linux/Win only |

---

## Detailed Specification

### 1. Word Navigation

**What is a "word"?**
- Sequence of non-whitespace characters
- Delimited by spaces or newlines
- Examples:
  - `"hello world"` → 2 words
  - `"foo-bar"` → 1 word (hyphen is part of word)
  - `"hello\nworld"` → 2 words

**Forward Word (`Alt+F` / `Ctrl+Shift+F`)**

```clojure
;; Input: "hello world test"
;; Cursor at position 0: "|hello world test"

Press Alt+F
→ Cursor at position 6: "hello |world test"

Press Alt+F again
→ Cursor at position 12: "hello world |test"

Press Alt+F again
→ Cursor at position 16: "hello world test|"
```

**Behavior with consecutive spaces:**

```clojure
;; Input: "hello   world" (3 spaces)
;; Cursor at position 5: "hello|   world"

Press Alt+F
→ Cursor at position 12: "hello   |world"
;; Skips over all spaces to start of next word
```

**Backward Word (`Alt+B` / `Ctrl+Shift+B`)**

```clojure
;; Input: "hello world test"
;; Cursor at position 16: "hello world test|"

Press Alt+B
→ Cursor at position 12: "hello world |test"

Press Alt+B again
→ Cursor at position 6: "hello |world"

Press Alt+B again
→ Cursor at position 0: "|hello world"
```

---

### 2. Kill Commands

**What does "kill" mean?**
- Delete text AND place in clipboard
- Different from normal delete (not just visual deletion)
- Can be "yanked" (pasted) back

**Clear Block Content (`Ctrl+L` / `Alt+L`)**

```clojure
;; Input: "hello world" (cursor anywhere)
Press Ctrl+L
→ Block text becomes ""
→ Cursor at position 0
```

**Kill to Beginning (`Ctrl+U` / `Alt+U`)**

```clojure
;; Input: "hello world test"
;; Cursor at position 12: "hello world |test"

Press Ctrl+U
→ Text becomes "test"
→ Cursor at position 0: "|test"
→ Clipboard contains "hello world "
```

**Kill to End (`Alt+K` - Linux/Win only)**

```clojure
;; Input: "hello world test"
;; Cursor at position 6: "hello |world test"

Press Alt+K
→ Text becomes "hello "
→ Cursor stays at position 6: "hello |"
→ Clipboard contains "world test"
```

**Kill Word Forward (`Ctrl+W` / `Alt+D`)**

```clojure
;; Input: "hello world test"
;; Cursor at position 0: "|hello world test"

Press Ctrl+W
→ Text becomes " world test"
→ Cursor at position 0: "| world test"
→ Clipboard contains "hello"

Press Ctrl+W again (skips space, kills "world")
→ Text becomes " test"
→ Cursor at position 0: "| test"
→ Clipboard contains "world"
```

**Kill Word Backward (`Alt+W` - Linux/Win only)**

```clojure
;; Input: "hello world test"
;; Cursor at position 11: "hello world| test"

Press Alt+W
→ Text becomes "hello  test"
→ Cursor at position 6: "hello | test"
→ Clipboard contains "world"
```

---

## Implementation

### Step 1: Word Boundary Detection

**New file:** `src/utils/text.cljc`

```clojure
(ns utils.text
  "Text manipulation utilities"
  (:require [clojure.string :as str]))

(defn find-next-word-boundary
  "Find position of start of next word.

   Args:
     text: String
     pos: Current cursor position

   Returns: Integer (position of next word start)

   Examples:
     (find-next-word-boundary \"hello world\" 0)  => 6
     (find-next-word-boundary \"hello   world\" 5) => 8
     (find-next-word-boundary \"hello\" 5)  => 5 (at end)"
  [text pos]
  (let [len (count text)
        ;; Skip current word and spaces
        skip-current (loop [p pos]
                      (if (and (< p len)
                              (not (whitespace? (nth text p))))
                        (recur (inc p))
                        p))
        ;; Skip spaces
        skip-spaces (loop [p skip-current]
                     (if (and (< p len)
                             (whitespace? (nth text p)))
                       (recur (inc p))
                       p))]
    skip-spaces))

(defn find-prev-word-boundary
  "Find position of start of previous word.

   Args:
     text: String
     pos: Current cursor position

   Returns: Integer (position of previous word start)

   Examples:
     (find-prev-word-boundary \"hello world\" 11) => 6
     (find-prev-word-boundary \"hello   world\" 8) => 0"
  [text pos]
  (when (pos? pos)
    (let [;; Move back one char if at boundary
          start-pos (if (and (< pos (count text))
                            (whitespace? (nth text (dec pos))))
                     (dec pos)
                     pos)
          ;; Skip spaces backward
          skip-spaces (loop [p (dec start-pos)]
                       (if (and (>= p 0)
                               (whitespace? (nth text p)))
                         (recur (dec p))
                         p))
          ;; Skip word backward
          skip-word (loop [p skip-spaces]
                     (if (and (>= p 0)
                             (not (whitespace? (nth text p))))
                       (recur (dec p))
                       p))]
      (inc skip-word))))

(defn whitespace? [c]
  (or (= c \space)
      (= c \newline)
      (= c \tab)))
```

### Step 2: Navigation Intents

**File:** `src/plugins/editing.cljc`

```clojure
(intent/register-intent! :move-cursor-forward-word
  {:doc "Move cursor to start of next word.

         Uses word boundary detection (stops at spaces/newlines)."

   :spec [:map
          [:type [:= :move-cursor-forward-word]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           next-pos (text/find-next-word-boundary text cursor-pos)]
       [{:op :update-node
         :id const/session-ui-id
         :props {:cursor-position next-pos}}]))})

(intent/register-intent! :move-cursor-backward-word
  {:doc "Move cursor to start of previous word."

   :spec [:map
          [:type [:= :move-cursor-backward-word]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           prev-pos (text/find-prev-word-boundary text cursor-pos)]
       (when prev-pos
         [{:op :update-node
           :id const/session-ui-id
           :props {:cursor-position prev-pos}}])))})
```

### Step 3: Kill Commands Intents

```clojure
(intent/register-intent! :clear-block-content
  {:doc "Clear entire block content (Ctrl+L / Alt+L).

         Sets text to empty string, cursor to position 0."

   :spec [:map
          [:type [:= :clear-block-content]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     [{:op :update-node :id block-id :props {:text ""}}
      {:op :update-node
       :id const/session-ui-id
       :props {:cursor-position 0}}])})

(intent/register-intent! :kill-to-beginning
  {:doc "Kill from cursor to beginning of block (Ctrl+U / Alt+U).

         Deletes text before cursor, places in clipboard."

   :spec [:map
          [:type [:= :kill-to-beginning]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           killed-text (subs text 0 cursor-pos)
           new-text (subs text cursor-pos)]
       ;; Copy to clipboard
       (clipboard/copy! killed-text)
       [{:op :update-node :id block-id :props {:text new-text}}
        {:op :update-node
         :id const/session-ui-id
         :props {:cursor-position 0}}]))})

(intent/register-intent! :kill-to-end
  {:doc "Kill from cursor to end of block (Alt+K)."

   :spec [:map
          [:type [:= :kill-to-end]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           killed-text (subs text cursor-pos)
           new-text (subs text 0 cursor-pos)]
       (clipboard/copy! killed-text)
       [{:op :update-node :id block-id :props {:text new-text}}]))})

(intent/register-intent! :kill-word-forward
  {:doc "Kill next word (Ctrl+W / Alt+D)."

   :spec [:map
          [:type [:= :kill-word-forward]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           next-pos (text/find-next-word-boundary text cursor-pos)
           killed-text (subs text cursor-pos next-pos)
           new-text (str (subs text 0 cursor-pos)
                        (subs text next-pos))]
       (clipboard/copy! killed-text)
       [{:op :update-node :id block-id :props {:text new-text}}]))})

(intent/register-intent! :kill-word-backward
  {:doc "Kill previous word (Alt+W)."

   :spec [:map
          [:type [:= :kill-word-backward]]
          [:block-id :string]]

   :handler
   (fn [db {:keys [block-id]}]
     (let [text (get-block-text db block-id)
           cursor-pos (q/cursor-position db)
           prev-pos (text/find-prev-word-boundary text cursor-pos)
           killed-text (subs text prev-pos cursor-pos)
           new-text (str (subs text 0 prev-pos)
                        (subs text cursor-pos))]
       (clipboard/copy! killed-text)
       [{:op :update-node :id block-id :props {:text new-text}}
        {:op :update-node
         :id const/session-ui-id
         :props {:cursor-position prev-pos}}]))})
```

### Step 4: Keyboard Bindings

**File:** `src/keymap/bindings_data.cljc`

```clojure
{;; ... existing bindings ...

 ;; Word navigation
 :editing/forward-word
 {:context :editing
  :key "alt+f"
  :key-mac "ctrl+shift+f"
  :intent {:type :move-cursor-forward-word
           :block-id :editing-block-id}}

 :editing/backward-word
 {:context :editing
  :key "alt+b"
  :key-mac "ctrl+shift+b"
  :intent {:type :move-cursor-backward-word
           :block-id :editing-block-id}}

 ;; Kill commands
 :editing/clear-block
 {:context :editing
  :key "alt+l"
  :key-mac "ctrl+l"
  :intent {:type :clear-block-content
           :block-id :editing-block-id}}

 :editing/kill-to-beginning
 {:context :editing
  :key "alt+u"
  :key-mac "ctrl+u"
  :intent {:type :kill-to-beginning
           :block-id :editing-block-id}}

 :editing/kill-to-end
 {:context :editing
  :key "alt+k"
  :intent {:type :kill-to-end
           :block-id :editing-block-id}}

 :editing/kill-word-forward
 {:context :editing
  :key "alt+d"
  :key-mac "ctrl+w"
  :intent {:type :kill-word-forward
           :block-id :editing-block-id}}

 :editing/kill-word-backward
 {:context :editing
  :key "alt+w"
  :intent {:type :kill-word-backward
           :block-id :editing-block-id}}}
```

---

## Testing Strategy

### Unit Tests (Data Layer)

```clojure
;; test/utils/text_test.cljc
(deftest find-next-word-boundary-test
  (testing "Simple case"
    (is (= 6 (text/find-next-word-boundary "hello world" 0))))

  (testing "Multiple spaces"
    (is (= 8 (text/find-next-word-boundary "hello   world" 5))))

  (testing "At end"
    (is (= 5 (text/find-next-word-boundary "hello" 5))))

  (testing "With newlines"
    (is (= 6 (text/find-next-word-boundary "hello\nworld" 0)))))

(deftest find-prev-word-boundary-test
  (testing "Simple case"
    (is (= 6 (text/find-prev-word-boundary "hello world test" 11))))

  (testing "Multiple spaces"
    (is (= 0 (text/find-prev-word-boundary "hello   world" 8))))

  (testing "At start"
    (is (nil? (text/find-prev-word-boundary "hello" 0)))))

;; test/plugins/editing_test.cljc
(deftest move-cursor-forward-word-test
  (testing "Moves to next word"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello world")
                 (tx/apply-intent {:type :enter-edit
                                  :block-id "a"
                                  :cursor-at 0}))
          new-db (tx/apply-intent db {:type :move-cursor-forward-word
                                     :block-id "a"})]
      (is (= 6 (q/cursor-position new-db))))))

(deftest kill-to-beginning-test
  (testing "Kills text before cursor"
    (let [db (-> (sample-db)
                 (assoc-in [:nodes "a" :props :text] "hello world test")
                 (tx/apply-intent {:type :enter-edit
                                  :block-id "a"
                                  :cursor-at 12}))
          new-db (tx/apply-intent db {:type :kill-to-beginning
                                     :block-id "a"})]
      (is (= "test" (get-in new-db [:nodes "a" :props :text])))
      (is (= 0 (q/cursor-position new-db))))))
```

### Browser Tests

```javascript
// e2e/word-navigation.spec.js
test.describe('Word Navigation', () => {
  test('Alt+F moves cursor forward by word', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['hello world test']);

    const block = page.locator('.content-edit').first();
    await block.click();
    await setCursorPosition(page, 0);

    // Press Alt+F
    await page.keyboard.press('Alt+KeyF');

    expect(await getCursorPosition(page)).toBe(6); // After "hello "

    await page.keyboard.press('Alt+KeyF');

    expect(await getCursorPosition(page)).toBe(12); // After "world "
  });

  test('Ctrl+U kills to beginning', async ({ page }) => {
    await page.goto('/');
    await createBlocks(page, ['hello world test']);

    const block = page.locator('.content-edit').first();
    await block.click();
    await setCursorPosition(page, 12);

    await page.keyboard.press('Control+KeyU');

    expect(await block.textContent()).toBe('test');
    expect(await getCursorPosition(page)).toBe(0);
  });
});
```

### Manual Testing Checklist

**Word Navigation:**
- [ ] `Alt+F` on "hello world" moves from start → "hello |world"
- [ ] `Alt+F` with multiple spaces skips all spaces
- [ ] `Alt+F` at end of block does nothing
- [ ] `Alt+B` moves backward by word
- [ ] `Alt+B` at start of block does nothing
- [ ] Works across newlines

**Kill Commands:**
- [ ] `Ctrl+L` clears entire block
- [ ] `Ctrl+U` kills to beginning, cursor at start
- [ ] `Alt+K` kills to end, cursor stays
- [ ] `Ctrl+W` kills word forward
- [ ] `Alt+W` kills word backward
- [ ] Killed text is in clipboard (can paste)

---

## Files to Create/Modify

### New Files
1. `src/utils/text.cljc` - Word boundary detection

### Modified Files
1. `src/plugins/editing.cljc` - Add intents
2. `src/keymap/bindings_data.cljc` - Add bindings

### Test Files
1. `test/utils/text_test.cljc` - Word boundary tests
2. `test/plugins/editing_test.cljc` - Kill command tests
3. `e2e/word-navigation.spec.js` - Browser integration tests

---

## Success Criteria

✅ `Alt+F` / `Ctrl+Shift+F` moves cursor forward by word
✅ `Alt+B` / `Ctrl+Shift+B` moves cursor backward by word
✅ `Ctrl+L` / `Alt+L` clears block content
✅ `Ctrl+U` / `Alt+U` kills to beginning
✅ `Alt+K` kills to end
✅ `Ctrl+W` / `Alt+D` kills word forward
✅ `Alt+W` kills word backward
✅ Killed text placed in clipboard
✅ Works with spaces, newlines, empty blocks

---

## References

**Logseq Source:**
- `src/main/frontend/handler/editor.cljs:3472-3494` - Word navigation and kill commands
- `src/main/frontend/util/cursor.cljs:149-182` - Word boundary detection
