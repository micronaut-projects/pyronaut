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
