const fs = require('fs');
const { normalizeNodeId } = require('./pathUtils');

function readEvents(reportFile) {
  if (!fs.existsSync(reportFile)) {
    return [];
  }
  return fs.readFileSync(reportFile, 'utf8')
    .split(/\r?\n/)
    .filter(line => line.trim().length > 0)
    .map(parseEventLine)
    .filter(event => event !== null && typeof event === 'object');
}

function parseEventLine(line) {
  try {
    return JSON.parse(line);
  } catch (error) {
    // A cancelled or crashed run can leave a half-written trailing line; skip it
    // rather than discarding every other event in the report.
    return null;
  }
}

function matchNodeId(testId, candidates) {
  if (typeof testId !== 'string' || testId.length === 0) {
    return null;
  }
  const normalized = stripParameters(normalizeNodeId(testId));
  for (const candidate of candidates) {
    const selector = normalizeNodeId(candidate.selector);
    if (matchesSelector(normalized, selector) || matchesSelector(normalized, nodeTail(selector))) {
      return candidate;
    }
  }
  return null;
}

function matchesSelector(nodeId, selector) {
  return nodeId === selector || nodeId.endsWith(`/${selector}`);
}

function stripParameters(nodeId) {
  if (!nodeId.endsWith(']')) {
    return nodeId;
  }
  const nodeIndex = nodeId.lastIndexOf('::');
  const bracket = nodeId.indexOf('[', nodeIndex < 0 ? 0 : nodeIndex);
  return bracket > 0 ? nodeId.substring(0, bracket) : nodeId;
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
  readEvents,
  stripParameters
};
