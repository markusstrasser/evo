#!/usr/bin/env node
/**
 * E2E Keyboard Event Linter
 *
 * Detects potentially problematic uses of page.keyboard.press() in E2E tests
 * that interact with contenteditable elements.
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

const fs = require('fs');
const path = require('path');

// Keys that are known to cause issues with contenteditable when using page.keyboard.press()
const RISKY_KEYS = [
  'ArrowLeft',
  'ArrowRight',
  'ArrowUp',
  'ArrowDown',
  'Enter',
  'Backspace',
  'Delete',
  'Tab'
];

// Pattern to detect page.keyboard.press() calls
const KEYBOARD_PRESS_PATTERN = /page\.keyboard\.press\(['"](\w+)['"]\)/g;

// Pattern to detect imports of our safe helper
const SAFE_HELPER_IMPORT = /import\s+{[^}]*pressKeyOnContentEditable[^}]*}\s+from\s+['"]\.\/helpers\/keyboard\.js['"]/;

function lintFile(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  const issues = [];

  // Check if file imports the safe helper
  const usesSafeHelper = SAFE_HELPER_IMPORT.test(content);

  // Find all page.keyboard.press() calls
  let match;
  const keyPressPattern = new RegExp(KEYBOARD_PRESS_PATTERN.source, 'g');

  while ((match = keyPressPattern.exec(content)) !== null) {
    const key = match[1];
    const line = content.substring(0, match.index).split('\n').length;

    // Check if this is a risky key
    if (RISKY_KEYS.some(riskyKey => key.includes(riskyKey))) {
      issues.push({
        file: filePath,
        line,
        key,
        message: `Found page.keyboard.press('${key}') which may not trigger handlers on contenteditable elements`,
        suggestion: usesSafeHelper
          ? `Use pressKeyOnContentEditable(page, '${key}') instead`
          : `Import and use pressKeyOnContentEditable from './helpers/keyboard.js'`
      });
    }
  }

  return issues;
}

function findTestFiles(dir, files = []) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });

  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);

    if (entry.isDirectory()) {
      findTestFiles(fullPath, files);
    } else if (entry.isFile() && entry.name.endsWith('.spec.js')) {
      files.push(fullPath);
    }
  }

  return files;
}

function main() {
  const rootDir = path.join(__dirname, '..', 'test', 'e2e');
  const testFiles = findTestFiles(rootDir);

  console.log('🔍 Linting E2E tests for keyboard event issues...\n');

  let totalIssues = 0;

  testFiles.forEach(fullPath => {
    const relativePath = path.relative(path.join(__dirname, '..'), fullPath);
    const issues = lintFile(fullPath);

    if (issues.length > 0) {
      console.log(`\n❌ ${relativePath}`);
      issues.forEach(issue => {
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
    console.log('ℹ️  See docs/PLAYWRIGHT_MCP_TESTING.md for details\n');
    console.log('   Playwright\'s page.keyboard.press() does NOT reliably trigger');
    console.log('   keyboard event handlers on contenteditable elements.');
    console.log('   Use pressKeyOnContentEditable() from test/e2e/helpers/keyboard.js instead.\n');
    process.exit(1);
  }
}

main();
