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

/**
 * Quotes a single argument for a Windows shell (cmd.exe) command line so that
 * paths and arguments containing spaces are not split by the shell or by the
 * launched program's C runtime argument parser.
 */
function quoteWindowsArgument(value) {
  const text = String(value);
  if (text.length > 0 && !/[\s"]/.test(text)) {
    return text;
  }
  // MSVCRT rules: backslashes are literal unless they precede a double quote.
  const escaped = text
    .replace(/(\\*)"/g, '$1$1\\"')
    .replace(/(\\+)$/, '$1$1');
  return `"${escaped}"`;
}

module.exports = {
  buildPyronautTestArgs,
  quoteWindowsArgument,
  toPyronautSelector
};
