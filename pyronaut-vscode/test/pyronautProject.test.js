const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

require.extensions['.ts'] = require.extensions['.js'];

const { configuredTestSource, isPyronautProject, testSourceDir } = require('../src/pyronautProject.ts');

test('detects Pyronaut projects', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pyronaut-vscode-'));
  fs.writeFileSync(path.join(dir, 'pyproject.toml'), '[tool.pyronaut]\n');

  assert.equal(isPyronautProject(dir), true);
});

test('resolves configured python test source', () => {
  const toml = `
[tool.pyronaut.sources]
python-test = "specs"
`;

  assert.equal(configuredTestSource(toml), 'specs');
});

test('defaults python test source to tests', () => {
  assert.equal(configuredTestSource('[tool.pyronaut]\n'), 'tests');
});

test('reads test source from project file', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pyronaut-vscode-'));
  fs.writeFileSync(path.join(dir, 'pyproject.toml'), '[tool.pyronaut.sources]\npython-test = "checks"\n');

  assert.equal(testSourceDir(dir), 'checks');
});
