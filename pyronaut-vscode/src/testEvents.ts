const fs = require('fs');
const { normalizeNodeId } = require('./pathUtils');

function readEvents(reportFile) {
  if (!fs.existsSync(reportFile)) {
    return [];
  }
  return fs.readFileSync(reportFile, 'utf8')
    .split(/\r?\n/)
    .filter(line => line.trim().length > 0)
    .map(line => JSON.parse(line));
}

function matchNodeId(testId, candidates) {
  const normalized = normalizeNodeId(testId);
  for (const candidate of candidates) {
    const selector = normalizeNodeId(candidate.selector);
    if (normalized === selector || normalized.endsWith(selector) || normalized.endsWith(nodeTail(selector))) {
      return candidate;
    }
  }
  return null;
}

function nodeTail(selector) {
  const nodeIndex = selector.indexOf('::');
  if (nodeIndex < 0) {
    const parts = selector.split('/');
    return parts[parts.length - 1];
  }
  const file = selector.substring(0, nodeIndex);
  const node = selector.substring(nodeIndex);
  const parts = file.split('/');
  return parts[parts.length - 1] + node;
}

function eventSummary(events) {
  const summary = {
    started: new Set(),
    finished: new Map(),
    output: []
  };
  for (const event of events) {
    if (event.eventType === 'test_started' && event.testId) {
      summary.started.add(normalizeNodeId(event.testId));
    } else if (event.eventType === 'test_finished' && event.testId) {
      summary.finished.set(normalizeNodeId(event.testId), event);
    } else if (event.eventType === 'test_output' && event.testId) {
      summary.output.push(event);
    }
  }
  return summary;
}

module.exports = {
  eventSummary,
  matchNodeId,
  nodeTail,
  readEvents
};
