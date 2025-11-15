# Development Scripts - Improved & Robust

## Quick Reference

```bash
npm start           # Clean + watch (recommended)
npm run dev         # Same as start (auto-cleans)
npm run dev:fast    # Skip clean (when you're sure cache is good)
npm run clean       # Manual cache clear
npm run build       # Production build (auto-cleans)
```

## What Changed

### 1. Auto-Clean on Dev Start ✨

**Before:**
```bash
npm run dev  # Could have stale output
```

**After:**
```bash
npm run dev  # Auto-cleans shadow-cljs + bb caches first!
```

### 2. Better Console Output 📊

Now using concurrently with:
- **Named processes**: `[CLJS]` and `[CSS]` prefixes
- **Color-coded**: Blue for ClojureScript, Green for CSS
- **Auto-kill on fail**: If one process dies, kill the other

### 3. Fast Mode Option ⚡

If you're **sure** your cache is fine and want to skip the clean:

```bash
npm run dev:fast
```

### 4. Watching Both Builds 👀

Now watches **both** builds:
- `anki` - Main app
- `blocks-ui` - Blocks editor

### 5. Manual Clean Command 🧹

```bash
npm run clean
```

Runs:
1. `npx shadow-cljs clean` - Clears `.shadow-cljs/` cache
2. `bb clean` - Clears semantic search index + orphans

## When to Use Each Command

### Daily Development

```bash
npm start  # or npm run dev
```

**Use this 99% of the time.** It:
- Clears all caches
- Starts shadow-cljs watch
- Starts Tailwind CSS watch
- Color-coded output
- Auto-restarts on code changes

### Quick Iteration (No Cache Issues)

```bash
npm run dev:fast
```

**Use when:**
- You just restarted and cache is fresh
- You're only changing code, not deps
- You know there are no stale issues

### Production Build

```bash
npm run build
```

**Creates optimized build:**
- Auto-cleans first
- Releases `anki` build
- Minifies CSS
- Ready for deployment

### Manual Cache Clear

```bash
npm run clean
```

**Use when:**
- You see "stale output" warnings
- After changing dependencies
- After pulling new code
- When things feel "weird"

## Troubleshooting

### "Output is stale" Warning

**Solution 1 (Automatic):**
```bash
npm run dev  # Already auto-cleans!
```

**Solution 2 (Manual):**
```bash
npm run clean
npm run dev:fast
```

### Build Feels Slow

Shadow-cljs caches aggressively. If builds feel slow or stuck:

```bash
# Nuclear option
rm -rf .shadow-cljs/ public/js/
npm run dev
```

### Changes Not Appearing

1. **Check the console** - Is shadow-cljs recompiling?
2. **Hard refresh browser** - Cmd+Shift+R (Chrome/Firefox)
3. **Clean and restart**:
   ```bash
   npm run clean
   npm start
   ```

### Process Won't Die

If `Ctrl+C` doesn't work:

```bash
# Kill all node processes
pkill -f "shadow-cljs"
pkill -f "tailwindcss"

# Then restart
npm start
```

## What Gets Cleaned

### `npx shadow-cljs clean`
- `.shadow-cljs/` directory (compile cache)
- `public/js/anki/` output
- `public/js/blocks-ui/` output

### `bb clean`
- `.ck/` semantic search index
- Orphaned cache entries
- Stale data files

## Build Output Locations

```
public/
├── js/
│   ├── anki/           # Main app build
│   └── blocks-ui/      # Blocks editor build
└── output.css          # Tailwind CSS output
```

## Tips

1. **Always use `npm start`** - It's the safest option
2. **Use `dev:fast` carefully** - Only when you're 100% sure cache is good
3. **Run `npm run clean` after pulling** - Prevents stale cache issues
4. **Check the console colors** - Easy to see which process is logging
5. **Kill processes cleanly** - Use `Ctrl+C`, not force-kill

## Color-Coded Console Output

```
[CLJS] Compiling ...          # Blue
[CSS] Rebuilding...           # Green
```

Makes it easy to see what's happening at a glance!
