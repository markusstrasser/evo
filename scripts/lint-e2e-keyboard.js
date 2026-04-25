#!/usr/bin/env node
/**
 * E2E Keyboard Event Linter
 *
 * Detects potentially problematic uses of page.keyboard.press/down/up() in E2E
 * specs that interact with contenteditable elements or app-global key routing.
 *
 * This prevents silent test failures where Playwright's keyboard API doesn't
 * properly trigger event handlers on contenteditable elements.
 *
 * Usage:
 *   node scripts/lint-e2e-keyboard.js
 *   bb lint:e2e-keyboard  # via Babashka task
 *
 * Exit codes:
 *   0: No issues found
 *   1: Found problematic keyboard usage
 */

const fs = require('node:fs');
const path = require('node:path');
const { findStaticCalls, findTestFiles } = require('./e2e-lint-utils.js');

// Keys that are known to cause issues with contenteditable when using page.keyboard.press()
const RISKY_KEYS = [
  'ArrowLeft',
  'ArrowRight',
  'ArrowUp',
  'ArrowDown',
  'Enter',
  'Backspace',
  'Delete',
  'Tab',
  'Home',
  'End',
];

// Pattern to detect imports of our safe helper
const SAFE_HELPER_IMPORT =
  /import\s+{[^}]*press(?:KeyOnContentEditable|GlobalKey|QuickSwitcherKey)[^}]*}\s+from\s+['"]\.\/helpers\/(?:keyboard|index)\.js['"]/;

function lintFile(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  const issues = [];

  // Check if file imports the safe helper
  const usesSafeHelper = SAFE_HELPER_IMPORT.test(content);

  for (const method of ['press', 'down', 'up']) {
    for (const call of findStaticCalls(content, `page.keyboard.${method}`)) {
      const key = call.firstArg;

      if (!key) continue;

      const keyParts = key.split('+');
      const hasRiskyKey = keyParts.some((part) => RISKY_KEYS.includes(part));
      const usesModifiersOption = /modifiers\s*:/.test(call.source);
      const usesHeldKeyPrimitive = method === 'down' || method === 'up';

      if (hasRiskyKey || usesModifiersOption || usesHeldKeyPrimitive) {
        issues.push({
          file: filePath,
          line: call.line,
          key,
          message: `Found raw page.keyboard.${method}('${key}') for a risky key contract`,
          suggestion: usesSafeHelper
            ? `Use pressKeyOnContentEditable, pressGlobalKey, pressQuickSwitcherKey, or a narrower helper`
            : `Import an explicit keyboard helper from './helpers/index.js'`,
        });
      }
    }
  }

  return issues;
}

function main() {
  const rootDir = path.join(__dirname, '..', 'test', 'e2e');
  const testFiles = findTestFiles(rootDir);

  console.log('🔍 Linting E2E tests for keyboard event issues...\n');

  let totalIssues = 0;

  testFiles.forEach((fullPath) => {
    const relativePath = path.relative(path.join(__dirname, '..'), fullPath);
    const issues = lintFile(fullPath);

    if (issues.length > 0) {
      console.log(`\n❌ ${relativePath}`);
      issues.forEach((issue) => {
        console.log(`   Line ${issue.line}: ${issue.message}`);
        console.log(`   💡 ${issue.suggestion}`);
      });
      totalIssues += issues.length;
    }
  });

  if (totalIssues === 0) {
    console.log('✅ No keyboard event issues found!\n');
    process.exit(0);
  } else {
    console.log(`\n❌ Found ${totalIssues} potential issue(s)\n`);
    console.log('ℹ️  See docs/TESTING.md for details\n');
    console.log("   Playwright's page.keyboard.press() does NOT reliably trigger");
    console.log('   keyboard event handlers on contenteditable elements.');
    console.log('   Use pressKeyOnContentEditable() from test/e2e/helpers/keyboard.js instead.\n');
    process.exit(1);
  }
}

main();
