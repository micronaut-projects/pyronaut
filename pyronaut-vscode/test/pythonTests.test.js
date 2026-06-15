const assert = require('node:assert/strict');
const test = require('node:test');

require.extensions['.ts'] = require.extensions['.js'];

const { isPythonTestFile, parsePythonTests } = require('../src/pythonTests.ts');

test('parses functions, async functions, and class methods', () => {
  const source = `
def helper():
    pass

def test_top_level():
    pass

async def test_async_case():
    pass

class TestHealth:
    def test_ok(self):
        pass

class Helper:
    def test_ignored(self):
        pass
`;

  const tests = parsePythonTests('tests/test_app.py', source);

  assert.deepEqual(tests.map(item => item.nodeId), [
    'tests/test_app.py::test_top_level',
    'tests/test_app.py::test_async_case',
    'tests/test_app.py::TestHealth::test_ok'
  ]);
});

test('matches pytest file naming conventions', () => {
  assert.equal(isPythonTestFile('tests/test_app.py'), true);
  assert.equal(isPythonTestFile('tests/app_test.py'), true);
  assert.equal(isPythonTestFile('tests/app.py'), false);
});
