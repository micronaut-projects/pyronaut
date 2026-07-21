const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const vscode = require('vscode');
const { isPyronautProject, testSourceDir } = require('./pyronautProject');
const { isPythonTestFile, parsePythonTests } = require('./pythonTests');
const { buildPyronautTestArgs } = require('./runner');
const { relativePath } = require('./pathUtils');
const { readEvents } = require('./testEvents');
const { readJUnitResults } = require('./junitResults');
const { applyJUnitFallback, applyResultEvents, completeUnreportedCandidates } = require('./statusMapper');

function activate(context) {
  const controller = vscode.tests.createTestController('pyronautTests', 'Pyronaut Tests');
  const itemData = new Map();
  context.subscriptions.push(controller);

  const refresh = async () => {
    controller.items.replace([]);
    itemData.clear();
    await discoverAll(controller, itemData);
  };

  controller.resolveHandler = async item => {
    if (!item) {
      await refresh();
    }
  };

  controller.createRunProfile('Run', vscode.TestRunProfileKind.Run, async (request, token) => {
    await runTests(controller, itemData, request, token);
  }, true);

  context.subscriptions.push(vscode.commands.registerCommand('pyronaut.refreshTests', refresh));
  context.subscriptions.push(vscode.commands.registerCommand('pyronaut.openTestReport', openTestReport));

  refresh();
}

function deactivate() {
}

async function discoverAll(controller, itemData) {
  const folders = vscode.workspace.workspaceFolders || [];
  for (const folder of folders) {
    const root = folder.uri.fsPath;
    if (!isPyronautProject(root)) {
      continue;
    }
    const testSource = testSourceDir(root);
    const pattern = new vscode.RelativePattern(folder, `${testSource.replace(/\\/g, '/')}/**/*.py`);
    const files = (await vscode.workspace.findFiles(pattern)).filter(uri => isPythonTestFile(uri.fsPath.replace(/\\/g, '/')));
    for (const uri of files) {
      await discoverFile(controller, itemData, folder, uri);
    }
  }
}

async function discoverFile(controller, itemData, folder, uri) {
  const root = folder.uri.fsPath;
  const rel = relativePath(root, uri.fsPath);
  const source = fs.readFileSync(uri.fsPath, 'utf8');
  const tests = parsePythonTests(rel, source);
  if (tests.length === 0) {
    return;
  }

  const fileItem = controller.createTestItem(`${folder.uri.toString()}::${rel}`, rel, uri);
  itemData.set(fileItem.id, {
    type: 'file',
    workspaceFolder: folder,
    selector: rel
  });

  for (const test of tests) {
    const label = test.className ? `${test.className}.${test.name}` : test.name;
    const testItem = controller.createTestItem(`${folder.uri.toString()}::${test.nodeId}`, label, uri);
    testItem.range = new vscode.Range(test.line, test.column, test.line, test.column + test.name.length);
    itemData.set(testItem.id, {
      type: 'test',
      workspaceFolder: folder,
      selector: test.nodeId
    });
    fileItem.children.add(testItem);
  }
  controller.items.add(fileItem);
}

async function runTests(controller, itemData, request, token) {
  const run = controller.createTestRun(request);
  const selected = selectedItems(controller, request);
  const byFolder = groupByFolder(selected, itemData);
  const allRuns = [];

  for (const [folderKey, group] of byFolder) {
    allRuns.push(runFolderTests(run, group.folder, group.items, itemData, token, request.include === undefined));
  }

  try {
    await Promise.all(allRuns);
  } finally {
    run.end();
  }
}

function selectedItems(controller, request) {
  const roots = [];
  if (request.include) {
    request.include.forEach(item => roots.push(item));
  } else {
    controller.items.forEach(item => roots.push(item));
  }
  const out = [];
  for (const root of roots) {
    collectRunnable(root, request.exclude || [], out);
  }
  return out;
}

function collectRunnable(item, excluded, out) {
  if (excluded.includes(item)) {
    return;
  }
  out.push(item);
  item.children.forEach(child => collectRunnable(child, excluded, out));
}

function groupByFolder(items, itemData) {
  const byFolder = new Map();
  for (const item of items) {
    const data = itemData.get(item.id);
    if (!data) {
      continue;
    }
    const key = data.workspaceFolder.uri.toString();
    if (!byFolder.has(key)) {
      byFolder.set(key, { folder: data.workspaceFolder, items: [] });
    }
    byFolder.get(key).items.push(item);
  }
  return byFolder;
}

async function runFolderTests(run, folder, selected, itemData, token, runAll) {
  const candidates = selected
    .filter(item => itemData.get(item.id)?.type === 'test')
    .map(item => ({ item, selector: itemData.get(item.id).selector }));
  const selectors = runAll ? [] : selectorsFor(selected, itemData);
  const config = vscode.workspace.getConfiguration('pyronaut', folder.uri);
  const executable = config.get('executable', 'pyronaut');
  const extraArgs = config.get('test.extraArgs', []);
  const args = buildPyronautTestArgs(extraArgs, selectors, testSourceDir(folder.uri.fsPath));
  const reportsDir = path.join(folder.uri.fsPath, '__pyronaut__', 'reports', 'tests');
  const eventsFile = path.join(reportsDir, 'events.ndjson');
  const junitFile = path.join(reportsDir, 'junit.xml');

  for (const candidate of candidates) {
    run.enqueued(candidate.item);
  }

  prepareReportFiles([eventsFile, junitFile]);
  const result = await spawnPyronaut(executable, args, folder.uri.fsPath, run, token);
  const events = readEvents(eventsFile);
  const { seen, started, finished } = applyResultEvents(run, candidates, events, {
    makeMessage: message => new vscode.TestMessage(message),
    toOutput: toTerminalText
  });

  const junitResults = readJUnitResults(junitFile);
  applyJUnitFallback(run, candidates, junitResults, seen, message => new vscode.TestMessage(message), finished, started);

  const detail = result.code === 0
    ? 'Check events.ndjson or junit.xml for a missing test identifier.'
    : `pyronaut test exited with code ${result.code}.`;
  completeUnreportedCandidates(run, candidates, finished, started, message => new vscode.TestMessage(message), detail);
}

function selectorsFor(items, itemData) {
  const selectors = [];
  const seen = new Set();
  for (const item of items) {
    const data = itemData.get(item.id);
    if (!data) {
      continue;
    }
    if (data.type === 'file' || data.type === 'test') {
      if (!seen.has(data.selector)) {
        seen.add(data.selector);
        selectors.push(data.selector);
      }
    }
  }
  return selectors;
}

function spawnPyronaut(executable, args, cwd, run, token) {
  return new Promise(resolve => {
    run.appendOutput(`$ ${[executable, ...args].join(' ')}\r\n`);
    const child = cp.spawn(executable, args, { cwd, shell: process.platform === 'win32' });
    const cancel = token.onCancellationRequested(() => {
      child.kill();
    });
    child.stdout.on('data', chunk => run.appendOutput(toTerminalText(chunk.toString())));
    child.stderr.on('data', chunk => run.appendOutput(toTerminalText(chunk.toString())));
    child.on('error', error => {
      cancel.dispose();
      run.appendOutput(toTerminalText(`${error.message}\n`));
      resolve({ code: 127 });
    });
    child.on('close', code => {
      cancel.dispose();
      resolve({ code: code === null ? 1 : code });
    });
  });
}

function toTerminalText(text) {
  return String(text).replace(/\r?\n/g, '\r\n');
}

function prepareReportFiles(files) {
  for (const file of files) {
    try {
      fs.unlinkSync(file);
    } catch (error) {
      if (!error || error.code !== 'ENOENT') {
        throw error;
      }
    }
  }
}

async function openTestReport() {
  const folders = vscode.workspace.workspaceFolders || [];
  for (const folder of folders) {
    const report = path.join(folder.uri.fsPath, '__pyronaut__', 'reports', 'tests', 'index.html');
    if (fs.existsSync(report)) {
      await vscode.env.openExternal(vscode.Uri.file(report));
      return;
    }
  }
  vscode.window.showWarningMessage('No Pyronaut test report was found.');
}

module.exports = {
  activate,
  deactivate
};
