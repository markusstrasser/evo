# Autocomplete System - Spec

**Goal:** Implement page reference, block reference, and slash command autocomplete exactly as in Logseq.

**Status:** ❌ Not implemented

---

## Overview

Evo needs THREE autocomplete systems:

1. **Page Reference `[[`** - Autocomplete page names
2. **Block Reference `((`** - Full-text block search
3. **Slash Commands `/`** - Command menu

---

## 1. Page Reference Autocomplete `[[`

### Trigger Detection

**When:** User types `[[`

**Detection logic:**
```clojure
(defn detect-page-ref-trigger [text cursor-pos]
  "Returns {:trigger-pos N} if cursor is after [["
  (when (>= cursor-pos 2)
    (let [prev-two-chars (subs text (- cursor-pos 2) cursor-pos)]
      (when (= prev-two-chars "[[")
        {:trigger-pos (- cursor-pos 2)}))))
```

### Autocomplete State

**Stored in session UI node:**
```clojure
{:autocomplete {:type :page-search
               :trigger-pos 42    ; Position of "[["
               :search-text ""    ; User's search query
               :results [...]}    ; Matching pages
 :editing-block-id "block-id"}
```

### Search & Filtering

**Initial state (no search text):**
- Show ALL pages
- Sort by: Recently edited, then alphabetically

**As user types:**
```clojure
;; User types: [[pro
;; Show: "Projects", "Progress", "Prometheus", etc.

(defn filter-pages [query all-pages]
  "Fuzzy match pages by name"
  (let [query-lower (str/lower-case query)]
    (->> all-pages
         (filter (fn [page]
                  (str/includes? (str/lower-case (:title page))
                               query-lower)))
         (sort-by :title))))
```

### UI Rendering

**Position:** Below cursor (at trigger position)

**Component:**
```clojure
(defn AutocompletePageSearch [{:keys [db results search-text on-select on-close]}]
  [:div.autocomplete-menu
   {:style {:position "absolute"
           :top "..." ;; Calculated from cursor position
           :left "..."}}
   [:div.autocomplete-header
    "Search pages: " search-text]
   [:div.autocomplete-results
    (for [[idx page] (map-indexed vector results)]
      [:div.autocomplete-item
       {:class (when (= idx selected-index) "selected")
        :on-click #(on-select page)}
       (:title page)])]])
```

### Keyboard Navigation

| Key | Action |
|-----|--------|
| `↑` or `Ctrl+P` | Previous item |
| `↓` or `Ctrl+N` | Next item |
| `Enter` | Select current item |
| `Esc` | Close autocomplete |
| `Backspace` | Delete char, update search |
| Any printable char | Add to search query |

### Selection Behavior

**User presses Enter:**
```clojure
;; Before: "See [[pro|]]" (cursor at |)
;; User selects "Projects"
;; After: "See [[Projects]]|" (cursor after ]])

(defn on-page-select [db page-name]
  (let [{:keys [trigger-pos editing-block-id]} (q/autocomplete db)
        text (get-block-text db editing-block-id)
        cursor-pos (q/cursor-position db)
        ;; Replace "[[" + search-text with "[[page-name]]"
        before (subs text 0 trigger-pos)
        after (subs text cursor-pos)
        new-text (str before "[[" page-name "]]" after)
        new-cursor-pos (+ trigger-pos 2 (count page-name) 2)]
    [{:op :update-node :id editing-block-id :props {:text new-text}}
     {:op :update-node
      :id const/session-ui-id
      :props {:autocomplete nil
             :cursor-position new-cursor-pos}}]))
```

### Create New Page

**If page doesn't exist:**
```
Search: "New Page Name"
Results: [ "New Page Name (create new)" ]

User selects "create new"
→ Creates page with that name
→ Inserts [[New Page Name]]
```

---

## 2. Block Reference Autocomplete `((`

### Trigger Detection

**When:** User types `((`

```clojure
(defn detect-block-ref-trigger [text cursor-pos]
  (when (>= cursor-pos 2)
    (let [prev-two-chars (subs text (- cursor-pos 2) cursor-pos)]
      (when (= prev-two-chars "((")
        {:trigger-pos (- cursor-pos 2)}))))
```

### Full-Text Search

**Search ALL blocks:**
```clojure
(defn search-blocks [query db]
  "Full-text search across all block text"
  (let [query-lower (str/lower-case query)]
    (->> (q/all-blocks db)
         (filter (fn [block]
                  (str/includes?
                   (str/lower-case (get-in block [:props :text]))
                   query-lower)))
         (take 20)  ; Limit results
         (map (fn [block]
                {:id (:id block)
                 :uuid (get-in block [:props :uuid])
                 :text (get-in block [:props :text])
                 :preview (take 100 (:text block))})))))
```

### UI Rendering

**Shows block preview:**
```clojure
(defn AutocompleteBlockSearch [{:keys [results on-select]}]
  [:div.autocomplete-menu
   [:div.autocomplete-header "Search blocks"]
   [:div.autocomplete-results
    (for [block results]
      [:div.autocomplete-item
       [:div.block-preview
        (truncate (:text block) 80)]
       [:div.block-meta
        "from page: " (:page-name block)]])]])
```

### Selection Behavior

**User selects block:**
```clojure
;; Before: "See ((hello|" (cursor at |, search="hello")
;; User selects block with UUID abc-123
;; After: "See ((abc-123))|" (cursor after )))

(defn on-block-select [db block-uuid]
  (let [{:keys [trigger-pos editing-block-id]} (q/autocomplete db)
        text (get-block-text db editing-block-id)
        cursor-pos (q/cursor-position db)
        before (subs text 0 trigger-pos)
        after (subs text cursor-pos)
        new-text (str before "((" block-uuid "))" after)
        new-cursor-pos (+ trigger-pos 2 (count block-uuid) 2)]
    [{:op :update-node :id editing-block-id :props {:text new-text}}
     {:op :update-node
      :id const/session-ui-id
      :props {:autocomplete nil
             :cursor-position new-cursor-pos}}]))
```

---

## 3. Slash Commands Autocomplete `/`

### Trigger Detection

**When:** User types `/` at:
- Beginning of line: `^/`
- After whitespace: `\s/`

```clojure
(defn detect-slash-trigger [text cursor-pos]
  (when (and (pos? cursor-pos)
            (= (nth text (dec cursor-pos)) \/))
    (let [before-slash (subs text 0 (dec cursor-pos))
          at-start? (empty? before-slash)
          after-space? (and (pos? (count before-slash))
                           (= (last before-slash) \space))]
      (when (or at-start? after-space?)
        {:trigger-pos (dec cursor-pos)}))))
```

### Command List

**Minimal set (Logseq-compatible):**

```clojure
(def slash-commands
  [{:name "TODO"
    :doc "Mark block as TODO"
    :action (fn [db block-id]
              (prepend-marker db block-id "TODO "))}

   {:name "DOING"
    :doc "Mark block as DOING"
    :action (fn [db block-id]
              (prepend-marker db block-id "DOING "))}

   {:name "DONE"
    :doc "Mark block as DONE"
    :action (fn [db block-id]
              (prepend-marker db block-id "DONE "))}

   {:name "LATER"
    :doc "Mark block as LATER"
    :action (fn [db block-id]
              (prepend-marker db block-id "LATER "))}

   {:name "NOW"
    :doc "Mark block as NOW"
    :action (fn [db block-id]
              (prepend-marker db block-id "NOW "))}

   {:name "bold"
    :doc "Wrap selection with bold formatting"
    :action (fn [db block-id]
              (wrap-selection db block-id "**"))}

   {:name "italic"
    :doc "Wrap selection with italic formatting"
    :action (fn [db block-id]
              (wrap-selection db block-id "__"))}

   {:name "highlight"
    :doc "Wrap selection with highlight formatting"
    :action (fn [db block-id]
              (wrap-selection db block-id "^^"))}

   {:name "strikethrough"
    :doc "Wrap selection with strikethrough"
    :action (fn [db block-id]
              (wrap-selection db block-id "~~"))}

   {:name "query"
    :doc "Insert query block"
    :action (fn [db block-id]
              (insert-text db block-id "{{query }}"))
              :cursor-offset -2}  ; Place cursor before }}

   {:name "embed"
    :doc "Embed page or block"
    :action (fn [db block-id]
              ;; Two-step: insert [[]], then trigger page search
              (insert-text db block-id "[[]]")
              (trigger-autocomplete db :page-search block-id ...))}])
```

### Filtering

```clojure
(defn filter-commands [query commands]
  "Fuzzy filter slash commands"
  (let [query-lower (str/lower-case query)]
    (->> commands
         (filter (fn [{:keys [name]}]
                  (str/starts-with? (str/lower-case name) query-lower)))
         (take 10))))
```

### UI Rendering

**Show with icons & descriptions:**
```clojure
(defn AutocompleteSlashCommands [{:keys [commands selected-idx on-select]}]
  [:div.autocomplete-menu.slash-commands
   (for [[idx cmd] (map-indexed vector commands)]
     [:div.autocomplete-item
      {:class (when (= idx selected-idx) "selected")
       :on-click #(on-select cmd)}
      [:div.command-icon (icon-for cmd)]
      [:div.command-name (:name cmd)]
      [:div.command-doc (:doc cmd)]])])
```

### Selection Behavior

**User selects command:**
```clojure
;; Before: "/to|" (cursor at |, search="to")
;; User selects "TODO"
;; After: "TODO |" (cursor after space)

(defn on-command-select [db command]
  (let [{:keys [trigger-pos editing-block-id]} (q/autocomplete db)
        text (get-block-text db editing-block-id)
        cursor-pos (q/cursor-position db)
        ;; Remove "/" + search text
        before (subs text 0 trigger-pos)
        after (subs text cursor-pos)
        ;; Execute command action
        result ((:action command) db editing-block-id)]
    ;; Command may return ops or new text
    (if (map? result)
      (:ops result)
      result)))
```

---

## Implementation Architecture

### New Plugin: `plugins/autocomplete.cljc`

```clojure
(ns plugins.autocomplete
  (:require [kernel.intent :as intent]
            [kernel.query :as q]
            [clojure.string :as str]))

;; ── State Management ──────────────────────────────────────

(defn autocomplete-active? [db]
  (some? (get-in db [:nodes const/session-ui-id :props :autocomplete])))

(defn autocomplete-type [db]
  (get-in db [:nodes const/session-ui-id :props :autocomplete :type]))

;; ── Trigger Detection ─────────────────────────────────────

(intent/register-intent! :check-autocomplete-trigger
  {:doc "Check if last keystroke triggered autocomplete.

         Called after every character insertion."

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-block-text db block-id)]
       (cond
         ;; Check page ref [[
         (detect-page-ref-trigger text cursor-pos)
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete {:type :page-search
                                 :trigger-pos (- cursor-pos 2)
                                 :search-text ""
                                 :block-id block-id}}}]

         ;; Check block ref ((
         (detect-block-ref-trigger text cursor-pos)
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete {:type :block-search
                                 :trigger-pos (- cursor-pos 2)
                                 :search-text ""
                                 :block-id block-id}}}]

         ;; Check slash /
         (detect-slash-trigger text cursor-pos)
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete {:type :slash-commands
                                 :trigger-pos (dec cursor-pos)
                                 :search-text ""
                                 :block-id block-id}}}]

         :else nil)))})

;; ── Search Update ─────────────────────────────────────────

(intent/register-intent! :update-autocomplete-search
  {:doc "Update autocomplete search query.

         Called on every keystroke while autocomplete is active."

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (when (autocomplete-active? db)
       (let [{:keys [trigger-pos type]} (q/autocomplete db)
             text (get-block-text db block-id)
             search-text (subs text (+ trigger-pos 2) cursor-pos)
             results (case type
                      :page-search (search-pages db search-text)
                      :block-search (search-blocks db search-text)
                      :slash-commands (filter-commands search-text slash-commands))]
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete (merge (q/autocomplete db)
                                       {:search-text search-text
                                        :results results
                                        :selected-idx 0})}}])))})

;; ── Navigation ────────────────────────────────────────────

(intent/register-intent! :autocomplete-next
  {:doc "Select next autocomplete item (Down / Ctrl+N)"
   :handler
   (fn [db _]
     (when-let [ac (q/autocomplete db)]
       (let [idx (:selected-idx ac)
             count (count (:results ac))
             next-idx (mod (inc idx) count)]
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete (assoc ac :selected-idx next-idx)}}])))})

(intent/register-intent! :autocomplete-prev
  {:doc "Select previous autocomplete item (Up / Ctrl+P)"
   :handler
   (fn [db _]
     (when-let [ac (q/autocomplete db)]
       (let [idx (:selected-idx ac)
             count (count (:results ac))
             prev-idx (mod (dec idx) count)]
         [{:op :update-node
           :id const/session-ui-id
           :props {:autocomplete (assoc ac :selected-idx prev-idx)}}])))})

;; ── Selection ─────────────────────────────────────────────

(intent/register-intent! :autocomplete-select
  {:doc "Select current autocomplete item (Enter)"
   :handler
   (fn [db _]
     (when-let [ac (q/autocomplete db)]
       (let [{:keys [type results selected-idx]} ac
             selected-item (nth results selected-idx)]
         (case type
           :page-search (on-page-select db (:name selected-item))
           :block-search (on-block-select db (:uuid selected-item))
           :slash-commands (on-command-select db selected-item)))))})

;; ── Close ─────────────────────────────────────────────────

(intent/register-intent! :autocomplete-close
  {:doc "Close autocomplete (Esc)"
   :handler
   (fn [db _]
     [{:op :update-node
       :id const/session-ui-id
       :props {:autocomplete nil}}])})
```

### UI Component: `components/autocomplete.cljs`

```clojure
(defn Autocomplete [{:keys [db on-intent]}]
  (when-let [ac (q/autocomplete db)]
    (let [{:keys [type results selected-idx trigger-pos]} ac
          ;; Calculate position from cursor
          position (calculate-popup-position trigger-pos)]
      [:div.autocomplete-popup
       {:style {:position "absolute"
               :top (:top position)
               :left (:left position)}}
       (case type
         :page-search
         (AutocompletePageSearch {:results results
                                 :selected-idx selected-idx
                                 :on-select #(on-intent {:type :autocomplete-select})})

         :block-search
         (AutocompleteBlockSearch {:results results
                                  :selected-idx selected-idx
                                  :on-select #(on-intent {:type :autocomplete-select})})

         :slash-commands
         (AutocompleteSlashCommands {:commands results
                                    :selected-idx selected-idx
                                    :on-select #(on-intent {:type :autocomplete-select})}))])))
```

### Event Handling in Block Component

```clojure
;; components/block.cljs - Enhanced input handler
(defn handle-input [e db block-id on-intent]
  (let [new-text (.-textContent (.-target e))
        cursor-pos (get-cursor-position)]
    ;; Update block text
    (on-intent {:type :update-content
               :block-id block-id
               :text new-text})

    ;; Check for autocomplete trigger
    (on-intent {:type :check-autocomplete-trigger
               :block-id block-id
               :cursor-pos cursor-pos})

    ;; If autocomplete active, update search
    (when (q/autocomplete-active? db)
      (on-intent {:type :update-autocomplete-search
                 :block-id block-id
                 :cursor-pos cursor-pos}))))

;; Keyboard handler - route to autocomplete when active
(defn handle-keydown [e db on-intent]
  (if (q/autocomplete-active? db)
    ;; Autocomplete is open - route keys to it
    (case (.-key e)
      "ArrowDown" (do (.preventDefault e)
                     (on-intent {:type :autocomplete-next}))
      "ArrowUp" (do (.preventDefault e)
                   (on-intent {:type :autocomplete-prev}))
      "Enter" (do (.preventDefault e)
                 (on-intent {:type :autocomplete-select}))
      "Escape" (do (.preventDefault e)
                  (on-intent {:type :autocomplete-close}))
      nil)  ; Let other keys pass through

    ;; Autocomplete not open - normal keyboard handling
    (handle-normal-keydown e db on-intent)))
```

---

## Testing Strategy

### Unit Tests

```clojure
(deftest page-search-test
  (testing "Finds pages by partial match"
    (let [db (make-db-with-pages ["Projects" "Progress" "Personal"])
          results (search-pages db "pro")]
      (is (= 2 (count results)))
      (is (contains? (set (map :title results)) "Projects"))
      (is (contains? (set (map :title results)) "Progress")))))

(deftest trigger-detection-test
  (testing "Detects [[ trigger"
    (is (= 5 (:trigger-pos (detect-page-ref-trigger "hello [[" 7)))))

  (testing "Detects (( trigger"
    (is (= 0 (:trigger-pos (detect-block-ref-trigger "((" 2)))))

  (testing "Detects / trigger at start"
    (is (= 0 (:trigger-pos (detect-slash-trigger "/" 1)))))

  (testing "Detects / trigger after space"
    (is (= 6 (:trigger-pos (detect-slash-trigger "hello /" 7))))))
```

### Browser Tests

```javascript
test('Page autocomplete appears on [[', async ({ page }) => {
  await page.goto('/');
  await createBlocks(page, ['']);

  const block = page.locator('.content-edit').first();
  await block.click();
  await page.keyboard.type('[[');

  // Autocomplete should appear
  const autocomplete = page.locator('.autocomplete-popup');
  await expect(autocomplete).toBeVisible();

  // Should show pages
  const items = page.locator('.autocomplete-item');
  expect(await items.count()).toBeGreaterThan(0);
});

test('Slash command menu appears on /', async ({ page }) => {
  await page.goto('/');
  await createBlocks(page, ['']);

  const block = page.locator('.content-edit').first();
  await block.click();
  await page.keyboard.type('/');

  // Menu should appear
  const menu = page.locator('.autocomplete-popup.slash-commands');
  await expect(menu).toBeVisible();

  // Should show commands
  expect(await page.locator('.command-name').count()).toBeGreaterThan(0);
});
```

---

## Success Criteria

✅ Typing `[[` opens page autocomplete
✅ Typing `((` opens block search
✅ Typing `/` opens slash commands
✅ Arrow keys navigate results
✅ Enter selects item
✅ Esc closes autocomplete
✅ Fuzzy search filters results
✅ Popup positioned correctly at cursor

---

## Files to Create/Modify

**New Files:**
1. `src/plugins/autocomplete.cljc` - Core logic
2. `src/components/autocomplete.cljs` - UI rendering
3. `test/plugins/autocomplete_test.cljc` - Unit tests
4. `e2e/autocomplete.spec.js` - Browser tests

**Modified Files:**
1. `src/components/block.cljs` - Input & keyboard handling
2. `src/kernel/query.cljc` - Add autocomplete queries

---

## References

**Logseq Source:**
- `src/main/frontend/handler/editor.cljs:1682-1698` - Trigger detection
- `src/main/frontend/commands.cljs` - Slash commands
- `src/main/frontend/components/editor.cljs:115-134` - Page autocomplete UI
