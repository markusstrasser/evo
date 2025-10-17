# Gemini Media Tool Fixes - 2025-10-02

## Issues Found & Fixed

### 1. HTTP Client Wrapper Bug (`dev/scripts/utils/http.clj`)
**Problem:** `babashka.http-client/request` takes a single map, not `url` + `opts`
**Fix:** Changed from `(http/request url opts)` to `(http/request (assoc opts :uri url))`
**Line:** 39-41

### 2. Restricted Header Error
**Problem:** babashka HTTP client doesn't allow manual `Content-Length` header
**Fix:** Removed `Content-Length` from headers (auto-set by client)
**File:** `dev/scripts/research/gemini_media.clj:71`

### 3. Binary File Upload Bug
**Problem:** Used `slurp` (string) for binary video files, corrupting data (287KB → 518KB)
**Fix:** Added `read-bytes` function to `dev/scripts/utils/files.clj:23-27`
**Changed:** `gemini_media.clj:67` from `files/read-file` to `files/read-bytes`

### 4. Missing Namespace Alias
**Problem:** `http.clj` had `[json]` instead of `[scripts.utils.json :as json]`
**Fix:** Added proper namespace alias
**Line:** `http.clj:4`

## Test Results

**Working command:**
```bash
set -a && source .env && set +a && \
bb dev/scripts/research/gemini_media.clj video analyze artvid.mp4 \
-p "Describe this video"
```

**Output:** Successfully analyzed 3.6s video showing pin art kinetic sculpture

## Key Learnings

1. **Babashka HTTP client API:** Single-map argument, auto-sets Content-Length
2. **Binary data handling:** Must use byte arrays, not strings
3. **Google File Upload API:** Requires exact size match (declared vs actual bytes)
4. **Error handling:** Use `:throw false` to capture HTTP error responses

## Future Agent Notes

- Always use `files/read-bytes` for binary uploads (video/images)
- Never manually set `Content-Length` in babashka HTTP requests
- Check HTTP wrapper compatibility when refactoring bash → clj
