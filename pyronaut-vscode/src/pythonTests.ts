const { toForwardSlash } = require('./pathUtils');

function parsePythonTests(relativeFile, source) {
  const normalizedFile = toForwardSlash(relativeFile);
  const tests = [];
  const lines = source.split(/\r?\n/);
  let currentClass = null;
  let classIndent = -1;
  let ignoredClassIndent = -1;

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const indent = leadingSpaces(line);
    const trimmed = line.trim();

    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    if (currentClass && indent <= classIndent && !trimmed.startsWith('@')) {
      currentClass = null;
      classIndent = -1;
    }
    if (ignoredClassIndent >= 0 && indent <= ignoredClassIndent && !trimmed.startsWith('@')) {
      ignoredClassIndent = -1;
    }

    const classMatch = line.match(/^(\s*)class\s+(Test[A-Za-z0-9_]*)\b/);
    if (classMatch) {
      currentClass = classMatch[2];
      classIndent = classMatch[1].length;
      ignoredClassIndent = -1;
      continue;
    }
    const ignoredClassMatch = line.match(/^(\s*)class\s+[A-Za-z_][A-Za-z0-9_]*\b/);
    if (ignoredClassMatch) {
      currentClass = null;
      classIndent = -1;
      ignoredClassIndent = ignoredClassMatch[1].length;
      continue;
    }

    const functionMatch = line.match(/^(\s*)(?:async\s+def|def)\s+(test_[A-Za-z0-9_]*)\s*\(/);
    if (!functionMatch) {
      continue;
    }

    const name = functionMatch[2];
    const column = functionMatch[1].length;
    if (ignoredClassIndent >= 0 && indent > ignoredClassIndent) {
      continue;
    }
    const nodeId = currentClass && indent > classIndent
      ? `${normalizedFile}::${currentClass}::${name}`
      : `${normalizedFile}::${name}`;

    tests.push({
      name,
      className: currentClass && indent > classIndent ? currentClass : null,
      nodeId,
      line: index,
      column
    });
  }

  return tests;
}

function leadingSpaces(line) {
  const match = line.match(/^\s*/);
  return match ? match[0].length : 0;
}

function isPythonTestFile(fileName) {
  return /(^|\/)test_[^/]*\.py$/.test(fileName) || /(^|\/)[^/]*_test\.py$/.test(fileName);
}

module.exports = {
  isPythonTestFile,
  parsePythonTests
};
