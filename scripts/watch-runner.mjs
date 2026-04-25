#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const args = new Set(process.argv.slice(2));
const forceClean = args.has('--clean');
const skipClean = args.has('--fast') || args.has('--no-clean');
const useColor =
  !process.env.NO_COLOR &&
  process.env.TERM !== 'dumb' &&
  (process.stdout.isTTY || process.stderr.isTTY || process.env.FORCE_COLOR);

const style = {
  dim: '\u001b[2m',
  red: '\u001b[31m',
  green: '\u001b[32m',
  yellow: '\u001b[33m',
  cyan: '\u001b[36m',
  reset: '\u001b[0m',
};

const color = (name, text) => (useColor ? `${style[name]}${text}${style.reset}` : text);
const label = color('dim', '[watch-runner]');
const log = (symbol, colorName, message, stream = console.log) => {
  stream(`${label} ${color(colorName, symbol)} ${message}`);
};

const info = (message) => log('→', 'cyan', message);
const ok = (message) => log('✓', 'green', message);
const warn = (message) => log('!', 'yellow', message, console.warn);
const fail = (message) => log('×', 'red', message, console.error);

if (forceClean && skipClean) {
  fail('Use either --clean or --fast, not both.');
  process.exit(1);
}

const commandLineForPid = (pid) => {
  const result = spawnSync('ps', ['-p', String(pid), '-o', 'command='], {
    encoding: 'utf8',
  });
  return result.status === 0 ? result.stdout.trim() : '';
};

const summarizeCommandLine = (commandLine, command) => {
  const shadowCli = commandLine.match(/clojure\.main -m shadow\.cljs\.devtools\.cli\s+(.+)$/);
  if (shadowCli) {
    return `java shadow-cljs ${shadowCli[1]}`;
  }

  if (commandLine.length > 180) {
    return `${commandLine.slice(0, 177)}...`;
  }

  return commandLine || command;
};

const listenersOnPort = (port) => {
  const result = spawnSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });

  if (result.error?.code === 'ENOENT' || result.status === 1) {
    return [];
  }

  if (result.status !== 0) {
    warn(`Could not inspect port ${port}; continuing without that preflight.`);
    return [];
  }

  const listeners = new Map();
  for (const line of result.stdout.trim().split(/\r?\n/).slice(1)) {
    const fields = line.trim().split(/\s+/);
    const [command, pid] = fields;
    if (!pid || listeners.has(pid)) {
      continue;
    }
    listeners.set(pid, {
      command,
      pid,
      commandLine: commandLineForPid(pid),
    });
  }

  return [...listeners.values()];
};

const printListeners = (listeners) => {
  for (const listener of listeners) {
    const commandLine = summarizeCommandLine(listener.commandLine, listener.command);
    console.error(`    ${color('dim', 'PID')} ${listener.pid}  ${commandLine}`);
  }
};

const devHttpListeners = listenersOnPort(8080);
if (devHttpListeners.length > 0) {
  fail('Port 8080 is already in use; shadow-cljs dev-http cannot start.');
  printListeners(devHttpListeners);
  info(
    `Stop it with Ctrl-C in its terminal, or run: kill ${devHttpListeners
      .map((listener) => listener.pid)
      .join(' ')}`
  );
  info('Then rerun `npm start` or `npm run dev`.');
  process.exit(1);
}
ok('Port 8080 available for dev-http.');

const shadowUiListeners = listenersOnPort(9630);
if (shadowUiListeners.length > 0) {
  warn('Port 9630 is already in use; shadow-cljs may choose another admin port.');
  printListeners(shadowUiListeners);
} else {
  ok('Port 9630 available for shadow admin UI.');
}

const needsClean =
  !skipClean &&
  (forceClean ||
    !existsSync('.shadow-cljs/builds/blocks-ui/manifest.edn') ||
    !existsSync('public/js/blocks-ui/main.js'));

const runSync = (cmd, args) => {
  const result = spawnSync(cmd, args, { stdio: 'inherit' });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
};

if (needsClean) {
  info('Cache missing or clean requested; running npm run clean.');
  runSync('npm', ['run', 'clean']);
} else {
  ok('Cache healthy or fast mode requested; skipping clean.');
}

info('Starting shadow-cljs watch for :blocks-ui.');
const child = spawn('npx', ['shadow-cljs', 'watch', 'blocks-ui'], {
  stdio: 'inherit',
});
child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
  } else {
    process.exit(code ?? 0);
  }
});
