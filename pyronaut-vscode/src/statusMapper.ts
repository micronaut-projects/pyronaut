const { matchJUnitResult } = require('./junitResults');
const { matchNodeId, stripParameters } = require('./testEvents');
const { normalizeNodeId } = require('./pathUtils');

// Mirrors VS Code's own terminal state ordering: a later, less severe state
// never replaces an earlier more severe one for the same item.
const EVENT_STATUS_PRIORITY = { SUCCESSFUL: 0, ABORTED: 1, FAILED: 2 };

function applyResultEvents(run, candidates, events, options) {
  const seen = new Set();
  const started = options.started || new Set();
  const finished = options.finished || new Set();
  const reported = new Map();
  const makeMessage = options.makeMessage;
  const toOutput = options.toOutput || (text => text);

  for (const event of events) {
    const candidate = matchNodeId(event.testId, candidates);
    if (!candidate) {
      continue;
    }
    if (event.eventType === 'test_started') {
      startItem(run, candidate.item, started);
      seen.add(candidate.item.id);
    } else if (event.eventType === 'test_output') {
      const payload = event.payload || {};
      run.appendOutput(toOutput(payload.text || ''), undefined, candidate.item);
    } else if (event.eventType === 'test_finished') {
      startItem(run, candidate.item, started);
      // Parametrized tests emit one finished event per parameter set for the
      // same TestItem: any failure fails the item, otherwise keep the most severe.
      const priority = eventStatusPriority(event.status);
      const previous = reported.get(candidate.item.id);
      if (previous === undefined || priority >= previous) {
        finishEventItem(run, candidate.item, event, makeMessage, parameterLabel(event.testId));
        reported.set(candidate.item.id, priority);
      }
      seen.add(candidate.item.id);
      finished.add(candidate.item.id);
    }
  }

  return {
    seen,
    started,
    finished
  };
}

function applyJUnitFallback(run, candidates, junitResults, seen, makeMessage, finished, started) {
  const completed = finished || new Set();
  const startedItems = started || new Set();
  for (const candidate of candidates) {
    if (completed.has(candidate.item.id)) {
      continue;
    }
    const junitResult = matchJUnitResult(candidate, junitResults);
    if (!junitResult) {
      continue;
    }
    startItem(run, candidate.item, startedItems);
    finishJUnitItem(run, candidate.item, junitResult, makeMessage);
    seen.add(candidate.item.id);
    completed.add(candidate.item.id);
  }
}

function completeUnreportedCandidates(run, candidates, finished, started, makeMessage, detail) {
  for (const candidate of candidates) {
    if (finished.has(candidate.item.id)) {
      continue;
    }
    startItem(run, candidate.item, started);
    run.errored(candidate.item, makeMessage(`Pyronaut did not report a result for ${candidate.selector}. ${detail}`));
    finished.add(candidate.item.id);
  }
}

function startItem(run, item, started) {
  if (!started.has(item.id)) {
    run.started(item);
    started.add(item.id);
  }
}

function finishEventItem(run, item, event, makeMessage, label) {
  const payload = event.payload || {};
  if (event.status === 'SUCCESSFUL') {
    run.passed(item);
  } else if (event.status === 'ABORTED') {
    run.skipped(item);
  } else {
    const failure = payload.failure || 'Test failed';
    run.failed(item, makeMessage(label ? `${label}: ${failure}` : failure));
  }
}

function eventStatusPriority(status) {
  const priority = EVENT_STATUS_PRIORITY[status];
  return priority === undefined ? EVENT_STATUS_PRIORITY.FAILED : priority;
}

function parameterLabel(testId) {
  if (typeof testId !== 'string') {
    return '';
  }
  const normalized = normalizeNodeId(testId);
  const stripped = stripParameters(normalized);
  return stripped === normalized ? '' : normalized.substring(stripped.length);
}

function finishJUnitItem(run, item, result, makeMessage) {
  if (result.status === 'passed') {
    run.passed(item);
  } else if (result.status === 'skipped') {
    run.skipped(item);
  } else {
    run.failed(item, makeMessage(result.message || 'Test failed'));
  }
}

module.exports = {
  applyJUnitFallback,
  applyResultEvents,
  completeUnreportedCandidates,
  finishEventItem,
  finishJUnitItem,
  startItem
};
