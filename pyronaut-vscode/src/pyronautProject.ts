const fs = require('fs');
const path = require('path');

function pyprojectPath(workspaceFolder) {
  return path.join(workspaceFolder, 'pyproject.toml');
}

function readPyproject(workspaceFolder) {
  const file = pyprojectPath(workspaceFolder);
  if (!fs.existsSync(file)) {
    return null;
  }
  return fs.readFileSync(file, 'utf8');
}

function isPyronautProject(workspaceFolder) {
  const content = readPyproject(workspaceFolder);
  return content !== null && /\[tool\.pyronaut(?:\.|\])/.test(content);
}

function configuredTestSource(content) {
  if (!content) {
    return 'tests';
  }
  const lines = content.split(/\r?\n/);
  let inSources = false;
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('[')) {
      inSources = trimmed === '[tool.pyronaut.sources]';
      continue;
    }
    if (inSources) {
      const match = trimmed.match(/^python-test\s*=\s*["']([^"']+)["']/);
      if (match) {
        return match[1];
      }
    }
  }
  return 'tests';
}

function testSourceDir(workspaceFolder) {
  const content = readPyproject(workspaceFolder);
  return configuredTestSource(content);
}

module.exports = {
  configuredTestSource,
  isPyronautProject,
  pyprojectPath,
  readPyproject,
  testSourceDir
};
