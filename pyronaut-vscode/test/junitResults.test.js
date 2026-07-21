const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

require.extensions['.ts'] = require.extensions['.js'];

const { matchJUnitResult, readJUnitResults } = require('../src/junitResults.ts');

test('reads passed and failed junit testcase results', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pyronaut-vscode-'));
  const report = path.join(dir, 'junit.xml');
  fs.writeFileSync(report, `
<testsuites>
  <testsuite tests="2">
    <testcase classname="__pyronaut__.test-sources.test_simple_python" name="test_index" />
    <testcase classname="__pyronaut__.test-sources.test_simple_python" name="test_failure">
      <failure message="assert 1 == 2">details</failure>
    </testcase>
  </testsuite>
</testsuites>
`);

  const results = readJUnitResults(report);

  assert.equal(results.length, 2);
  assert.equal(results[0].status, 'passed');
  assert.equal(results[1].status, 'failed');
  assert.equal(results[1].message, 'assert 1 == 2');
});

test('matches processed junit testcase names to project selectors', () => {
  const result = matchJUnitResult(
    { selector: 'tests/test_simple_python.py::test_index' },
    [{ className: '__pyronaut__.test-sources.test_simple_python', name: 'test_index', status: 'passed' }]
  );

  assert.equal(result.status, 'passed');
});

test('matches class method testcase names to project selectors', () => {
  const result = matchJUnitResult(
    { selector: 'tests/test_health.py::TestHealth::test_ok' },
    [{ className: '__pyronaut__.test-sources.test_health.TestHealth', name: 'TestHealth.test_ok', status: 'passed' }]
  );

  assert.equal(result.status, 'passed');
});
