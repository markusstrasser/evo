#!/usr/bin/env node

import {existsSync} from 'node:fs';
import {spawn, spawnSync} from 'node:child_process';

const needsClean = process.argv.includes('--clean') ||
  !existsSync('.shadow-cljs/builds/blocks-ui/manifest.edn') ||
  !existsSync('public/js/blocks-ui/main.js');

const runSync = (cmd, args) => {
  const result = spawnSync(cmd, args, {stdio: 'inherit'});
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
};

if (needsClean) {
  console.log('[watch-runner] Cache missing → running npm run clean');
  runSync('npm', ['run', 'clean']);
} else {
  console.log('[watch-runner] Cache healthy → skipping clean');
}

const child = spawn('npm', ['run', 'dev:fast'], {stdio: 'inherit'});
child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
  } else {
    process.exit(code ?? 0);
  }
});
