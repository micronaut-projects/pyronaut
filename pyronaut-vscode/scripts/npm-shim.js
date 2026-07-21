const cp = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);

if (args[0] === 'ci') {
  fs.mkdirSync(path.join(root, 'node_modules'), { recursive: true });
  process.exit(0);
}

if (args[0] === 'test') {
  run([
    '--test',
    'test/junitResults.test.js',
    'test/pyronautProject.test.js',
    'test/pythonTests.test.js',
    'test/runner.test.js',
    'test/statusMapper.test.js',
    'test/testEvents.test.js'
  ]);
}

if (args[0] === 'run') {
  if (args[1] === 'compile') {
    run(['scripts/compile.js']);
  }
  if (args[1] === 'package:vsix') {
    run(['scripts/package-vsix.js']);
  }
}

console.error(`Unsupported npm shim command: ${args.join(' ')}`);
process.exit(1);

function run(nodeArgs) {
  cp.execFileSync(process.execPath, nodeArgs, {
    cwd: root,
    stdio: 'inherit'
  });
  process.exit(0);
}
