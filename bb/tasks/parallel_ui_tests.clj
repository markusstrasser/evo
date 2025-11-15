#!/usr/bin/env bb
;; Parallel Playwright test runner - run 10+ tests concurrently

(ns parallel-ui-tests
  "Run UI tests in parallel for 10x speedup

  Usage: bb parallel-ui-tests

  Instead of:
    Test 1 (2s) → Test 2 (2s) → Test 3 (2s) = 6s total

  Does:
    Test 1 (2s) ┐
    Test 2 (2s) ├─ All parallel = 2s total
    Test 3 (2s) ┘"
  (:require [clojure.string :as str]
            [babashka.process :as p]))

(def test-scenarios
  "All UI test scenarios - each runs in separate browser context"
  [{:name "duplicate-events"
    :description "Enter key should produce exactly 1 operation"
    :code "
      await page.goto('http://localhost:8080');
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('Enter');

      const ops = await page.evaluate(() =>
        window.DEBUG?.getOperations() || []
      );
      const enterOps = ops.filter(op => op.type === 'context-aware-enter');

      if (enterOps.length !== 1) {
        throw new Error(`Expected 1 enter op, got ${enterOps.length}`);
      }
    "}

   {:name "cursor-preservation"
    :description "Typing abc should produce 'abc' not 'cba'"
    :code "
      await page.goto('http://localhost:8080');
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('Enter');
      await page.keyboard.press('End');

      await page.keyboard.press('a');
      await page.keyboard.press('b');
      await page.keyboard.press('c');

      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]').textContent
      );

      if (!text.endsWith('abc')) {
        throw new Error(`Expected 'abc', got '${text}'`);
      }
    "}

   {:name "empty-block-no-stale-text"
    :description "Empty blocks should show empty text, not stale closure"
    :code "
      await page.goto('http://localhost:8080');
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('Enter');
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');

      const state = await page.evaluate(() => {
        const ed = document.querySelector('[contenteditable]');
        return {
          dom: ed?.textContent || '',
          isEmpty: ed?.textContent === ''
        };
      });

      if (!state.isEmpty) {
        throw new Error(`Empty block has text: '${state.dom}'`);
      }
    "}

   {:name "focus-after-enter"
    :description "After Enter, should be able to type without clicking"
    :code "
      await page.goto('http://localhost:8080');
      await page.getByText('Tech Stack').click();
      await page.keyboard.press('Enter');
      await page.keyboard.press('End');
      await page.keyboard.press('Enter');

      const focused = await page.evaluate(() => {
        const ed = document.querySelector('[contenteditable]');
        return {
          focused: document.activeElement === ed,
          activeTag: document.activeElement.tagName
        };
      });

      if (!focused.focused) {
        throw new Error(`Not focused! Active: ${focused.activeTag}`);
      }

      await page.keyboard.press('x');
      const text = await page.evaluate(() =>
        document.querySelector('[contenteditable]').textContent
      );

      if (text !== 'x') {
        throw new Error(`Typing failed: got '${text}'`);
      }
    "}

   {:name "edge-case-empty-string"
    :description "Empty string edge case"
    :test-text ""}

   {:name "edge-case-single-char"
    :description "Single character edge case"
    :test-text "a"}

   {:name "edge-case-unicode"
    :description "Unicode emoji edge case"
    :test-text "🔥🚀"}

   {:name "edge-case-rtl"
    :description "RTL text edge case"
    :test-text "‏العربية‏"}

   {:name "edge-case-very-long"
    :description "Very long text edge case"
    :test-text (apply str (repeat 500 "x"))}])

(defn generate-playwright-test
  "Generate Playwright test file for scenario"
  [scenario]
  (if (:test-text scenario)
    ;; Edge case test
    (format "
import { test, expect } from '@playwright/test';

test('%s', async ({ page }) => {
  await page.goto('http://localhost:8080');
  await page.getByText('Tech Stack').click();
  await page.keyboard.press('Enter');

  // Set text
  await page.evaluate((text) => {
    document.querySelector('[contenteditable]').textContent = text;
  }, `%s`);

  // Try typing
  await page.keyboard.press('x');

  // Should not crash
  const text = await page.evaluate(() =>
    document.querySelector('[contenteditable]').textContent
  );
  expect(text).toBeTruthy();
});
" (:name scenario) (:test-text scenario))

    ;; Custom code test
    (format "
import { test, expect } from '@playwright/test';

test('%s', async ({ page }) => {
  %s
});
" (:description scenario) (:code scenario))))

(defn write-test-files!
  "Write each scenario to separate test file"
  []
  (doseq [scenario test-scenarios]
    (let [filename (str "test/e2e/" (:name scenario) ".spec.ts")
          content (generate-playwright-test scenario)]
      (spit filename content)
      (println "✓" filename))))

(defn run-parallel-tests
  "Run all tests in parallel using Playwright"
  []
  (println "\n🚀 Running UI tests in parallel...\n")

  (let [start (System/currentTimeMillis)
        process (p/process ["npx" "playwright" "test"
                            "--workers=10"  ; Run 10 in parallel
                            "test/e2e/"]
                           {:inherit true})]
    @process
    (let [duration (/ (- (System/currentTimeMillis) start) 1000.0)]
      (println (format "\n⏱️  Completed in %.1fs" duration))
      (println (format "   (Serial would take ~%ds)"
                       (* 2 (count test-scenarios)))))))

(defn -main []
  (println "📝 Generating test files...")
  (write-test-files!)

  (println "\n🏃 Running tests in parallel...")
  (run-parallel-tests))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
