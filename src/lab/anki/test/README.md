# Anki Clone Tests

Test suite for the local-first Anki clone application.

## Test Structure

```
src/lab/anki/test/
├── core_test.cljc      # Unit tests (CLJS/CLJ)
├── e2e.spec.js         # End-to-end tests (Playwright)
└── README.md           # This file
```

## Running Tests

### Unit Tests (ClojureScript)

```bash
npm test
# or
npx shadow-cljs compile test && node out/tests.js
```

Tests the core business logic:
- Card parsing (QA, cloze, image occlusion)
- Card hashing (content-based identity)
- Event sourcing (creation, reduction, queries)
- Scheduling algorithm (mock SRS)

**Coverage**: `core_test.cljc` covers all functions in `src/lab/anki/core.cljc`

### End-to-End Tests (Playwright)

```bash
npx playwright test src/lab/anki/test/e2e.spec.js
```

Tests the full application in a real browser:
- **Core API**: Direct function calls via browser console
- **UI Integration**: Visual rendering, DOM structure, interactions
- **IndexedDB**: Persistence layer

**Requirements**:
- Local dev server running on `http://localhost:8000`
- Chromium browser (installed by Playwright)

## Test Philosophy

### What We Test

✅ **Unit tests (core_test.cljc)**:
- Pure functions (parsing, hashing, event application)
- Business logic correctness
- Edge cases and error handling

✅ **E2E tests (e2e.spec.js)**:
- API surface (functions callable from browser)
- UI rendering (components, styling)
- Integration (IndexedDB, File System Access API)

### What We Don't Test

❌ **File System Access API**:
- Browser security restrictions prevent automated testing
- Requires user gesture for directory picker
- Manual testing required

❌ **Review Flow**:
- Requires actual directory selection
- Full flow tested manually during development

### Test Organization

Tests are **co-located with the package** (`src/lab/anki/test/`) rather than in a global `test/` directory. This keeps tests close to the code they test and makes the anki package self-contained.

## Writing Tests

### Unit Tests (ClojureScript)

Use `clojure.test` for unit tests:

```clojure
(deftest my-test
  (testing "description"
    (is (= expected (my-function input)))))
```

### E2E Tests (Playwright)

Use Playwright's test runner:

```javascript
test('should do something', async ({ page }) => {
  await page.goto('/public/anki.html');

  const result = await page.evaluate(() => {
    return window['lab.anki.core'].some_function();
  });

  expect(result).toBe(expected);
});
```

## Continuous Integration

Tests run on every commit:
- Unit tests: Fast, run in Node.js
- E2E tests: Slower, run in Chromium via Playwright

Both must pass before merging.
