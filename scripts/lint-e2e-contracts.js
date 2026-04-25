#!/usr/bin/env node
/**
 * E2E Contract Linter
 *
 * Guards against low-signal E2E patterns that let tests go green without
 * proving user-visible behavior.
 */

const fs = require('node:fs');
const path = require('node:path');
const { findStaticCalls, findTestFiles } = require('./e2e-lint-utils.js');

function lintFile(filePath) {
  const content = fs.readFileSync(filePath, 'utf8');
  const issues = [];

  for (const call of findStaticCalls(content, 'window.DEBUG.state')) {
    issues.push({
      file: filePath,
      line: call.line,
      message: 'Avoid broad DEBUG DB snapshots in E2E specs',
      suggestion: 'Assert user-visible DOM, roles, focus, or use a narrow TEST_HELPERS contract.',
    });
  }

  for (const call of findStaticCalls(content, 'page.keyboard.press')) {
    if (/modifiers\s*:/.test(call.source)) {
      issues.push({
        file: filePath,
        line: call.line,
        message: 'Playwright keyboard.press modifiers option is ignored for these tests',
        suggestion: "Use '+' combo syntax inside a named helper.",
      });
    }
  }

  for (const call of [
    ...findStaticCalls(content, 'test.skip'),
    ...findStaticCalls(content, 'describe.skip'),
  ]) {
    issues.push({
      file: filePath,
      line: call.line,
      message: 'Skipped E2E tests are dead code',
      suggestion: 'Delete stale skipped tests, or make the behavior real and run the test.',
    });
  }

  for (const call of findStaticCalls(content, 'page.goto')) {
    if (
      call.firstArg === '/index.html?test=true' &&
      !/waitUntil\s*:\s*['"]domcontentloaded['"]/.test(call.source)
    ) {
      issues.push({
        file: filePath,
        line: call.line,
        message: 'Test-mode navigation must not wait for the default load event',
        suggestion: "Use page.goto('/index.html?test=true', { waitUntil: 'domcontentloaded' }).",
      });
    }
  }

  return issues;
}

function main() {
  const repoRoot = path.join(__dirname, '..');
  const rootDir = path.join(repoRoot, 'test', 'e2e');
  const testFiles = findTestFiles(rootDir);

  console.log('Linting E2E tests for contract anti-patterns...\n');

  let totalIssues = 0;

  for (const fullPath of testFiles) {
    const relativePath = path.relative(repoRoot, fullPath);
    const issues = lintFile(fullPath);

    if (issues.length > 0) {
      console.log(`\n${relativePath}`);
      for (const issue of issues) {
        console.log(`   Line ${issue.line}: ${issue.message}`);
        console.log(`   Suggestion: ${issue.suggestion}`);
      }
      totalIssues += issues.length;
    }
  }

  if (totalIssues > 0) {
    console.log(`\nFound ${totalIssues} E2E contract issue(s)\n`);
    process.exit(1);
  }

  console.log('No E2E contract issues found.\n');
}

main();
