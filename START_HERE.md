# 🚀 Quick Start

## Development (ALWAYS use this)

```bash
npm start
```

This will:
- ✅ Clean all caches (no stale errors)
- ✅ Start shadow-cljs watch (hot reload)
- ✅ Start Tailwind CSS watch
- ✅ Auto-compile on file changes

**Wait for:** `[CLJS] Build completed` before opening browser.

## Open the App

```bash
http://localhost:8080/blocks.html
```

## Why No "Stale Output" Errors?

The `npm start` script:
1. Cleans cache first
2. Runs **watch mode** (not compile)
3. Shadow-cljs assigns a unique build ID
4. Browser JS matches the running watch process ✅

## If You Still See Errors

**Hard refresh:** <kbd>Cmd</kbd> + <kbd>Shift</kbd> + <kbd>R</kbd>

## Other Commands

```bash
npm run dev:fast    # Skip clean (faster restart)
npm run clean       # Manual cache clear
npm run build       # Production build
```

## ⚠️ NEVER Do This

```bash
npx shadow-cljs compile blocks-ui  # ❌ Creates stale output!
```

Always use `npm start` or `npm run dev:fast` for development.
