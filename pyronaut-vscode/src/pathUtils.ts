const path = require('path');

function toForwardSlash(value) {
  return value.replace(/\\/g, '/');
}

function relativePath(root, file) {
  return toForwardSlash(path.relative(root, file));
}

function normalizeNodeId(value) {
  return toForwardSlash(value || '');
}

module.exports = {
  normalizeNodeId,
  relativePath,
  toForwardSlash
};
