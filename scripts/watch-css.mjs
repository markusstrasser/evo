import { watch, copyFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = resolve(__dirname, '..');
const src = resolve(root, 'input.css');
const dest = resolve(root, 'public/output.css');

function copy() {
  copyFileSync(src, dest);
  console.log(`[CSS] ${new Date().toLocaleTimeString()} → copied input.css`);
}

// Initial copy
copy();

// Watch for changes
watch(src, (eventType) => {
  if (eventType === 'change') {
    copy();
  }
});

console.log('[CSS] Watching input.css...');
