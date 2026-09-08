const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

require.extensions['.ts'] = require.extensions['.js'];

const { eventSummary, matchNodeId, readEvents } = require('../src/testEvents.ts');

test('reads event reports and summarizes test outcomes', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pyronaut-vscode-'));
  const report = path.join(dir, 'events.ndjson');
  fs.writeFileSync(report, [
    JSON.stringify({ eventType: 'test_started', testId: 'tests/test_app.py::test_index' }),
    JSON.stringify({ eventType: 'test_output', testId: 'tests/test_app.py::test_index', payload: { text: 'hello' } }),
    JSON.stringify({ eventType: 'test_finished', testId: 'tests/test_app.py::test_index', status: 'SUCCESSFUL' })
  ].join('\n'));

  const events = readEvents(report);
  const summary = eventSummary(events);

  assert.equal(events.length, 3);
  assert.equal(summary.started.has('tests/test_app.py::test_index'), true);
  assert.equal(summary.finished.get('tests/test_app.py::test_index').status, 'SUCCESSFUL');
  assert.equal(summary.output.length, 1);
});

test('matches absolute event node ids to relative selectors', () => {
  const candidate = matchNodeId('/tmp/project/tests/test_app.py::test_index', [
    { selector: 'tests/test_app.py::test_index', value: 1 }
  ]);

  assert.equal(candidate.value, 1);
});

test('matches processed test-source node ids to project selectors', () => {
  const candidate = matchNodeId('__pyronaut__/test-sources/test_app.py::test_index', [
    { selector: 'tests/test_app.py::test_index', value: 1 }
  ]);

  assert.equal(candidate.value, 1);
});

test('matches parametrized pytest node ids to the unparametrized selector', () => {
  const candidates = [{ selector: 'tests/test_app.py::test_values', value: 1 }];

  assert.equal(matchNodeId('tests/test_app.py::test_values[1]', candidates).value, 1);
  assert.equal(matchNodeId('/tmp/project/tests/test_app.py::test_values[a-b]', candidates).value, 1);
  assert.equal(matchNodeId('tests/test_app.py::TestThing::test_values[x[0]]', [
    { selector: 'tests/test_app.py::TestThing::test_values', value: 2 }
  ]).value, 2);
});

test('does not match node ids whose file name merely ends with the selector tail', () => {
  const candidates = [{ selector: 'tests/app_test.py::test_x', value: 1 }];

  assert.equal(matchNodeId('tests/webapp_test.py::test_x', candidates), null);
  assert.equal(matchNodeId('other/tests/app_test.py::test_x', candidates).value, 1);
  assert.equal(matchNodeId(undefined, candidates), null);
});

test('skips unparsable event lines instead of failing the whole report', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pyronaut-vscode-'));
  const report = path.join(dir, 'events.ndjson');
  fs.writeFileSync(report, [
    JSON.stringify({ eventType: 'test_started', testId: 'tests/test_app.py::test_index' }),
    JSON.stringify({ eventType: 'test_finished', testId: 'tests/test_app.py::test_index', status: 'SUCCESSFUL' }),
    '{"eventType": "test_finished", "testId": "tests/test_app.py::test_ot'
  ].join('\n'));

  const events = readEvents(report);

  assert.equal(events.length, 2);
  assert.equal(events[1].status, 'SUCCESSFUL');
});
