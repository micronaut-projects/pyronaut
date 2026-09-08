const assert = require('node:assert/strict');
const test = require('node:test');

require.extensions['.ts'] = require.extensions['.js'];

const { buildPyronautTestArgs, toPyronautSelector } = require('../src/runner.ts');

test('builds run all command arguments', () => {
  assert.deepEqual(buildPyronautTestArgs([], []), ['test']);
});

test('builds selector command arguments with extra args first', () => {
  assert.deepEqual(
    buildPyronautTestArgs(['--debug-vm'], ['tests/test_app.py', 'tests/test_app.py::test_index'], 'tests'),
    ['test', '--debug-vm', '--tests', 'test_app.py', '--tests', 'test_app.py::test_index']
  );
});

test('leaves selectors outside the configured test source unchanged', () => {
  assert.equal(toPyronautSelector('custom/test_app.py::test_index', 'tests'), 'custom/test_app.py::test_index');
});

test('normalizes windows-style selectors for pyronaut', () => {
  assert.equal(toPyronautSelector('tests\\test_app.py::test_index', 'tests'), 'test_app.py::test_index');
});

test('quotes windows shell arguments containing spaces or quotes', () => {
  const { quoteWindowsArgument } = require('../src/runner.ts');

  assert.equal(quoteWindowsArgument('test'), 'test');
  assert.equal(quoteWindowsArgument('C:\\Users\\John Smith\\pyronaut.cmd'), '"C:\\Users\\John Smith\\pyronaut.cmd"');
  assert.equal(quoteWindowsArgument('C:\\path with space\\'), '"C:\\path with space\\\\"');
  assert.equal(quoteWindowsArgument('say "hi"'), '"say \\"hi\\""');
  assert.equal(quoteWindowsArgument(''), '""');
});
