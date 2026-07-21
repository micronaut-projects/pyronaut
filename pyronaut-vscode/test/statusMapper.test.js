const assert = require('node:assert/strict');
const test = require('node:test');

require.extensions['.ts'] = require.extensions['.js'];

const { applyJUnitFallback, applyResultEvents, completeUnreportedCandidates } = require('../src/statusMapper.ts');

test('applies passing event status to the matched TestItem', () => {
  const item = { id: 'test-index' };
  const run = recordingRun();
  const result = applyResultEvents(
    run,
    [{ item, selector: 'tests/test_simple_python.py::test_index' }],
    [
      {
        eventType: 'test_started',
        testId: '__pyronaut__/test-sources/test_simple_python.py::test_index'
      },
      {
        eventType: 'test_finished',
        testId: '__pyronaut__/test-sources/test_simple_python.py::test_index',
        status: 'SUCCESSFUL'
      }
    ],
    { makeMessage: text => ({ text }) }
  );

  assert.deepEqual(run.calls, [
    ['started', item],
    ['passed', item]
  ]);
  assert.equal(result.finished.has(item.id), true);
});

test('applies failing event status to the matched TestItem', () => {
  const item = { id: 'test-failure' };
  const run = recordingRun();

  applyResultEvents(
    run,
    [{ item, selector: 'tests/test_simple_python.py::test_failure' }],
    [
      {
        eventType: 'test_finished',
        testId: '__pyronaut__/test-sources/test_simple_python.py::test_failure',
        status: 'FAILED',
        payload: { failure: 'assert 1 == 2' }
      }
    ],
    { makeMessage: text => ({ text }) }
  );

  assert.deepEqual(run.calls, [
    ['started', item],
    ['failed', item, { text: 'assert 1 == 2' }]
  ]);
});

test('applies processed-source event status to test_application_starts', () => {
  const item = { id: 'test-application-starts' };
  const run = recordingRun();
  const result = applyResultEvents(
    run,
    [{ item, selector: 'tests/test_simple_python.py::test_application_starts' }],
    [
      {
        eventType: 'test_finished',
        testId: '__pyronaut__/test-sources/test_simple_python.py::test_application_starts',
        status: 'SUCCESSFUL'
      }
    ],
    { makeMessage: text => ({ text }) }
  );

  assert.deepEqual(run.calls, [
    ['started', item],
    ['passed', item]
  ]);
  assert.equal(result.started.has(item.id), true);
  assert.equal(result.finished.has(item.id), true);
});

test('applies JUnit fallback status when no finished event matched', () => {
  const passed = { id: 'test-index' };
  const failed = { id: 'test-failure' };
  const run = recordingRun();
  const seen = new Set();

  applyJUnitFallback(
    run,
    [
      { item: passed, selector: 'tests/test_simple_python.py::test_index' },
      { item: failed, selector: 'tests/test_simple_python.py::test_failure' }
    ],
    [
      { className: '__pyronaut__.test-sources.test_simple_python', name: 'test_index', status: 'passed' },
      { className: '__pyronaut__.test-sources.test_simple_python', name: 'test_failure', status: 'failed', message: 'boom' }
    ],
    seen,
    text => ({ text })
  );

  assert.deepEqual(run.calls, [
    ['started', passed],
    ['passed', passed],
    ['started', failed],
    ['failed', failed, { text: 'boom' }]
  ]);
  assert.equal(seen.has(passed.id), true);
  assert.equal(seen.has(failed.id), true);
});

test('applies JUnit fallback status to test_application_starts', () => {
  const item = { id: 'test-application-starts' };
  const run = recordingRun();
  const seen = new Set();
  const finished = new Set();
  const started = new Set();

  applyJUnitFallback(
    run,
    [{ item, selector: 'tests/test_simple_python.py::test_application_starts' }],
    [
      {
        className: '__pyronaut__.test-sources.test_simple_python',
        name: 'test_application_starts',
        status: 'passed'
      }
    ],
    seen,
    text => ({ text }),
    finished,
    started
  );

  assert.deepEqual(run.calls, [
    ['started', item],
    ['passed', item]
  ]);
  assert.equal(seen.has(item.id), true);
  assert.equal(started.has(item.id), true);
  assert.equal(finished.has(item.id), true);
});

test('does not overwrite status from a matched finished event', () => {
  const passed = { id: 'test-index' };
  const missing = { id: 'test-other' };
  const run = recordingRun();
  const seen = new Set([passed.id]);
  const finished = new Set([passed.id]);

  applyJUnitFallback(
    run,
    [
      { item: passed, selector: 'tests/test_simple_python.py::test_index' },
      { item: missing, selector: 'tests/test_simple_python.py::test_other' }
    ],
    [
      { className: '__pyronaut__.test-sources.test_simple_python', name: 'test_index', status: 'failed', message: 'should not apply' },
      { className: '__pyronaut__.test-sources.test_simple_python', name: 'test_other', status: 'passed' }
    ],
    seen,
    text => ({ text }),
    finished
  );

  assert.deepEqual(run.calls, [
    ['started', missing],
    ['passed', missing]
  ]);
});

test('marks unreported included items as errored', () => {
  const reported = { id: 'test-index' };
  const missing = { id: 'test-application-starts' };
  const run = recordingRun();
  const finished = new Set([reported.id]);
  const started = new Set();

  completeUnreportedCandidates(
    run,
    [
      { item: reported, selector: 'tests/test_simple_python.py::test_index' },
      { item: missing, selector: 'tests/test_simple_python.py::test_application_starts' }
    ],
    finished,
    started,
    text => ({ text }),
    'Check events.ndjson or junit.xml for a missing test identifier.'
  );

  assert.deepEqual(run.calls, [
    ['started', missing],
    ['errored', missing, { text: 'Pyronaut did not report a result for tests/test_simple_python.py::test_application_starts. Check events.ndjson or junit.xml for a missing test identifier.' }]
  ]);
  assert.equal(finished.has(missing.id), true);
});

function recordingRun() {
  const calls = [];
  return {
    calls,
    appendOutput: (...args) => calls.push(['appendOutput', ...args]),
    failed: (...args) => calls.push(['failed', ...args]),
    errored: (...args) => calls.push(['errored', ...args]),
    passed: (...args) => calls.push(['passed', ...args]),
    skipped: (...args) => calls.push(['skipped', ...args]),
    started: (...args) => calls.push(['started', ...args])
  };
}
