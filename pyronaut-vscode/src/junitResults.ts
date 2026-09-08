const fs = require('fs');
const { nodeTail, stripParameters } = require('./testEvents');

const STATUS_PRIORITY = { passed: 0, skipped: 1, failed: 2 };

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

  const matched = results.filter(result => {
    // Parametrized pytest cases report as "test_x[param]"; match on the bare name.
    const name = stripParameters(String(result.name || ''));
    if (name === testName || name.endsWith(`.${testName}`)) {
      return className.length === 0 || name === `${className}.${testName}` || String(result.className || '').endsWith(className);
    }
    return tail.endsWith(`::${name}`) || tail.endsWith(`::${name.replace('.', '::')}`);
  });
  return aggregateJUnitResults(matched);
}

function aggregateJUnitResults(matched) {
  if (matched.length === 0) {
    return null;
  }
  if (matched.length === 1) {
    return matched[0];
  }
  // Any failed parameter fails the test; otherwise report the most severe status.
  let aggregate = matched[0];
  const messages = [];
  for (const result of matched) {
    if ((STATUS_PRIORITY[result.status] || 0) > (STATUS_PRIORITY[aggregate.status] || 0)) {
      aggregate = result;
    }
    if (result.status === 'failed') {
      messages.push(result.message ? `${result.name}: ${result.message}` : `${result.name}: Test failed`);
    }
  }
  return {
    name: aggregate.name,
    className: aggregate.className,
    status: aggregate.status,
    message: aggregate.status === 'failed' ? messages.join('\n') : aggregate.message
  };
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
