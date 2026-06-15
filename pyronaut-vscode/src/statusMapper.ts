const { matchJUnitResult } = require('./junitResults');
const { matchNodeId } = require('./testEvents');

function applyResultEvents(run, candidates, events, options) {
  const seen = new Set();
  const started = new Set();
  const finished = new Set();
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
      finishEventItem(run, candidate.item, event, makeMessage);
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

function finishEventItem(run, item, event, makeMessage) {
  const payload = event.payload || {};
  if (event.status === 'SUCCESSFUL') {
    run.passed(item);
  } else if (event.status === 'ABORTED') {
    run.skipped(item);
  } else {
    run.failed(item, makeMessage(payload.failure || 'Test failed'));
  }
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
