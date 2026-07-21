const fs = require('fs');
const { nodeTail } = require('./testEvents');

function readJUnitResults(reportFile) {
  if (!fs.existsSync(reportFile)) {
    return [];
  }
  const xml = fs.readFileSync(reportFile, 'utf8');
  const results = [];
  const testcasePattern = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g;
  let match;
  while ((match = testcasePattern.exec(xml)) !== null) {
    const attributes = parseAttributes(match[1] || '');
    const body = match[2] || '';
    const name = attributes.name || '';
    if (!name) {
      continue;
    }
    const status = body.includes('<skipped') ? 'skipped'
      : body.includes('<failure') || body.includes('<error') ? 'failed'
        : 'passed';
    results.push({
      name,
      className: attributes.classname || '',
      status,
      message: failureMessage(body) || (status === 'failed' ? 'Test failed' : '')
    });
  }
  return results;
}

function matchJUnitResult(candidate, results) {
  const tail = nodeTail(candidate.selector);
  const selectorParts = tail.split('::');
  const testName = selectorParts[selectorParts.length - 1];
  const className = selectorParts.length > 2 ? selectorParts[selectorParts.length - 2] : '';

  return results.find(result => {
    if (result.name === testName || result.name.endsWith(`.${testName}`)) {
      return className.length === 0 || result.name === `${className}.${testName}` || result.className.endsWith(className);
    }
    return tail.endsWith(`::${result.name}`) || tail.endsWith(`::${result.name.replace('.', '::')}`);
  }) || null;
}

function parseAttributes(text) {
  const attributes = {};
  const pattern = /([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*"([^"]*)"/g;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    attributes[match[1]] = decodeXml(match[2]);
  }
  return attributes;
}

function failureMessage(body) {
  const match = body.match(/<(?:failure|error)\b[^>]*message="([^"]*)"/);
  if (match) {
    return decodeXml(match[1]);
  }
  const text = body.match(/<(?:failure|error)\b[^>]*>([\s\S]*?)<\/(?:failure|error)>/);
  return text ? decodeXml(text[1].trim()) : '';
}

function decodeXml(text) {
  return String(text)
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

module.exports = {
  matchJUnitResult,
  readJUnitResults
};
