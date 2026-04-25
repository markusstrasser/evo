const fs = require('node:fs');
const path = require('node:path');

function lineNumber(content, index) {
  return content.substring(0, index).split('\n').length;
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

function skipLineComment(content, index) {
  const next = content.indexOf('\n', index + 2);
  return next === -1 ? content.length : next;
}

function skipBlockComment(content, index) {
  const next = content.indexOf('*/', index + 2);
  return next === -1 ? content.length : next + 2;
}

function readStringLiteral(content, index) {
  const quote = content[index];
  if (!["'", '"', '`'].includes(quote)) return null;

  let value = '';
  let dynamic = false;

  for (let i = index + 1; i < content.length; i += 1) {
    const ch = content[i];

    if (ch === '\\') {
      if (i + 1 < content.length) {
        value += content[i + 1];
        i += 1;
      }
      continue;
    }

    if (quote === '`' && ch === '$' && content[i + 1] === '{') {
      dynamic = true;
    }

    if (ch === quote) {
      return { value, dynamic, end: i + 1 };
    }

    value += ch;
  }

  return null;
}

function skipStringLiteral(content, index) {
  const literal = readStringLiteral(content, index);
  return literal ? literal.end : content.length;
}

function skipWhitespace(content, index) {
  let i = index;
  while (i < content.length && /\s/.test(content[i])) i += 1;
  return i;
}

function isIdentifierChar(ch) {
  return Boolean(ch) && /[$\w]/.test(ch);
}

function isCalleeBoundary(content, index, callee) {
  const before = content[index - 1];
  const after = content[index + callee.length];
  return !isIdentifierChar(before) && !isIdentifierChar(after);
}

function findMatchingParen(content, openIndex) {
  let depth = 0;

  for (let i = openIndex; i < content.length; i += 1) {
    const ch = content[i];
    const next = content[i + 1];

    if (ch === '/' && next === '/') {
      i = skipLineComment(content, i) - 1;
      continue;
    }

    if (ch === '/' && next === '*') {
      i = skipBlockComment(content, i) - 1;
      continue;
    }

    if (["'", '"', '`'].includes(ch)) {
      i = skipStringLiteral(content, i) - 1;
      continue;
    }

    if (ch === '(') depth += 1;
    if (ch === ')') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }

  return -1;
}

function findStaticCalls(content, callee) {
  const calls = [];

  for (let i = 0; i < content.length; i += 1) {
    const ch = content[i];
    const next = content[i + 1];

    if (ch === '/' && next === '/') {
      i = skipLineComment(content, i) - 1;
      continue;
    }

    if (ch === '/' && next === '*') {
      i = skipBlockComment(content, i) - 1;
      continue;
    }

    if (["'", '"', '`'].includes(ch)) {
      i = skipStringLiteral(content, i) - 1;
      continue;
    }

    if (!content.startsWith(callee, i) || !isCalleeBoundary(content, i, callee)) {
      continue;
    }

    const afterCallee = skipWhitespace(content, i + callee.length);
    if (content[afterCallee] !== '(') continue;

    const firstArgStart = skipWhitespace(content, afterCallee + 1);
    const firstArg = readStringLiteral(content, firstArgStart);
    const closeIndex = findMatchingParen(content, afterCallee);
    const source = closeIndex === -1 ? content.slice(i) : content.slice(i, closeIndex + 1);

    calls.push({
      index: i,
      line: lineNumber(content, i),
      callee,
      firstArg: firstArg && !firstArg.dynamic ? firstArg.value : null,
      source,
    });
  }

  return calls;
}

module.exports = {
  findStaticCalls,
  findTestFiles,
  lineNumber,
};
