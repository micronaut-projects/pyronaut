const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const srcDir = path.join(root, 'src');
const outDir = path.join(root, 'out');

fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });

for (const entry of fs.readdirSync(srcDir)) {
  if (!entry.endsWith('.ts')) {
    continue;
  }
  const source = path.join(srcDir, entry);
  const target = path.join(outDir, entry.replace(/\.ts$/, '.js'));
  fs.copyFileSync(source, target);
}
