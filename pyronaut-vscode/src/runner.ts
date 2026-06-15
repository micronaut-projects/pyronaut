function buildPyronautTestArgs(extraArgs, selectors, testSource) {
  const args = ['test', ...sanitizeArray(extraArgs)];
  for (const selector of sanitizeArray(selectors)) {
    args.push('--tests', toPyronautSelector(selector, testSource));
  }
  return args;
}

function sanitizeArray(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter(item => typeof item === 'string' && item.length > 0);
}

function toPyronautSelector(selector, testSource) {
  const normalizedSelector = selector.replace(/\\/g, '/');
  const normalizedSource = String(testSource || '').replace(/\\/g, '/').replace(/^\/+|\/+$/g, '');
  if (normalizedSource.length === 0) {
    return normalizedSelector;
  }
  if (normalizedSelector === normalizedSource) {
    return normalizedSelector;
  }
  const prefix = `${normalizedSource}/`;
  return normalizedSelector.startsWith(prefix) ? normalizedSelector.substring(prefix.length) : normalizedSelector;
}

module.exports = {
  buildPyronautTestArgs,
  toPyronautSelector
};
