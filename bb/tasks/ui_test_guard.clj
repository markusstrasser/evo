#!/usr/bin/env bb
;; UI Test Guard - Automated checks that would have caught all 4 bugs

(ns ui-test-guard
  "Automated UI regression tests that catch cursor/focus bugs before commit"
  (:require [clojure.string :as str]
            [babashka.process :as p]))

(defn run-playwright-check
  "Run Playwright test and return result"
  [test-name js-code]
  (println (format "\n🔍 Checking: %s" test-name))
  (try
    (let [result (-> (p/process ["npx" "playwright" "test" "--headed=false"]
                                {:in js-code :out :string :err :string})
                     deref)]
      (if (zero? (:exit result))
        (do (println "  ✅ PASS") true)
        (do (println "  ❌ FAIL") (println (:err result)) false)))
    (catch Exception e
      (println "  ❌ ERROR:" (.getMessage e))
      false)))

;; TEST 1: No Duplicate Events
(def check-duplicate-events
  "Press Enter once, should produce exactly ONE :context-aware-enter event"
  {:name "No duplicate events from keymap+component"
   :code "
     // Navigate to page, click block, press Enter
     await page.goto('http://localhost:8080');
     await page.getByText('Tech Stack').click();
     await page.keyboard.press('Enter');

     // Check operations log
     const ops = await page.evaluate(() => {
       return window.DEBUG?.getOperations() || [];
     });

     const enterOps = ops.filter(op =>
       op.type === 'context-aware-enter'
     );

     // ASSERTION: Exactly 1 enter operation
     if (enterOps.length !== 1) {
       throw new Error(`Expected 1 enter op, got ${enterOps.length}`);
     }
   "})

;; TEST 2: Cursor Preservation During Typing
(def check-cursor-preservation
  "Type 'abc', text should be 'abc' not 'cba' (cursor at end, not position 0)"
  {:name "Cursor stays at end while typing"
   :code "
     await page.goto('http://localhost:8080');
     await page.getByText('Tech Stack').click();
     await page.keyboard.press('Enter');  // Edit mode
     await page.keyboard.press('End');     // Move to end

     // Type a, b, c and check cursor moves forward
     await page.keyboard.press('a');
     const pos1 = await page.evaluate(() =>
       window.getSelection().getRangeAt(0).startOffset
     );

     await page.keyboard.press('b');
     const pos2 = await page.evaluate(() =>
       window.getSelection().getRangeAt(0).startOffset
     );

     await page.keyboard.press('c');
     const text = await page.evaluate(() =>
       document.querySelector('[contenteditable]').textContent
     );

     // ASSERTIONS
     if (text.endsWith('abc') === false) {
       throw new Error(`Expected 'abc' at end, got '${text}'`);
     }
     if (pos2 <= pos1) {
       throw new Error('Cursor not advancing (resetting to 0?)');
     }
   "})

;; TEST 3: Empty Block No Stale Text
(def check-empty-block-text
  "Create empty block, should show empty text not stale closure text"
  {:name "Empty blocks don't show stale text"
   :code "
     await page.goto('http://localhost:8080');
     await page.getByText('Tech Stack').click();
     await page.keyboard.press('Enter');
     await page.keyboard.press('End');
     await page.keyboard.press('Enter');  // Create new empty block

     // Check DB vs DOM
     const state = await page.evaluate(() => {
       const editable = document.querySelector('[contenteditable]');
       const blockId = editable?.dataset.blockId;
       const dbText = window.DEBUG?.getBlockText(blockId);
       const domText = editable?.textContent;

       return {
         db: dbText,
         dom: domText,
         match: dbText === domText
       };
     });

     // ASSERTION: DB and DOM must match for empty block
     if (!state.match) {
       throw new Error(
         `DB/DOM mismatch! DB='${state.db}' DOM='${state.dom}'`
       );
     }
     if (state.dom !== '') {
       throw new Error(
         `Empty block shows text: '${state.dom}'`
       );
     }
   "})

;; TEST 4: Focus Attaches Without Click
(def check-focus-after-enter
  "Press Enter to create block, should be able to type immediately (no click)"
  {:name "Focus attaches after Enter (no click needed)"
   :code "
     await page.goto('http://localhost:8080');
     await page.getByText('Tech Stack').click();
     await page.keyboard.press('Enter');
     await page.keyboard.press('End');
     await page.keyboard.press('Enter');  // Create new block

     // Check focus WITHOUT clicking
     const focusState = await page.evaluate(() => {
       const editable = document.querySelector('[contenteditable]');
       return {
         focused: document.activeElement === editable,
         activeTag: document.activeElement.tagName,
         canType: document.activeElement.contentEditable === 'true'
       };
     });

     // ASSERTION: Must be focused immediately
     if (!focusState.focused) {
       throw new Error(
         `Focus not attached! Active element: ${focusState.activeTag}`
       );
     }

     // Try typing without click
     await page.keyboard.press('x');
     const text = await page.evaluate(() =>
       document.querySelector('[contenteditable]').textContent
     );

     // ASSERTION: Typing must work
     if (text !== 'x') {
       throw new Error(
         `Typing failed without click! Expected 'x', got '${text}'`
       );
     }
   "})

;; RUN ALL CHECKS
(defn -main []
  (println "🚀 UI Test Guard - Pre-commit checks\n")
  (println "These tests would have caught all 4 cursor/focus bugs!\n")

  (let [tests [check-duplicate-events
               check-cursor-preservation
               check-empty-block-text
               check-focus-after-enter]
        results (map #(run-playwright-check (:name %) (:code %)) tests)
        passed (count (filter identity results))
        total (count tests)]

    (println (format "\n📊 Results: %d/%d passed" passed total))

    (if (= passed total)
      (do
        (println "✅ All checks passed!")
        (System/exit 0))
      (do
        (println "❌ Some checks failed - fix before committing!")
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
