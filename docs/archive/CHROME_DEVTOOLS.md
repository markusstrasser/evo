# Chrome DevTools MCP Gotchas

> **Note:** For browser-based E2E testing, prefer **Playwright MCP** over Chrome DevTools MCP. See [PLAYWRIGHT_MCP_TESTING.md](./PLAYWRIGHT_MCP_TESTING.md) for comprehensive testing guide.
>
> Playwright MCP advantages:
> - Standard E2E testing framework (better community support)
> - Multi-browser support (Chromium, Firefox, WebKit)
> - Accessibility-first snapshots (more stable than UIDs)
> - No stale snapshot issues (built-in auto-waiting)
> - Better for automated testing and CI/CD
>
> Use Chrome DevTools MCP for:
> - Manual browser exploration
> - One-off debugging tasks
> - Quick verification of specific behaviors

## Stale Snapshot UIDs

**Problem:** After page updates (DOM changes, navigation, etc), element UIDs become stale.

**Symptom:**
```
Error: This uid is coming from a stale snapshot. Call take_snapshot to get a fresh snapshot.
```

**Solution:** Always call `take_snapshot` before interacting with elements after page changes:

```javascript
// After any DOM-modifying action
mcp__chrome-devtools__fill(uid, value)
// Page updates...
mcp__chrome-devtools__take_snapshot()   // ← Required!
mcp__chrome-devtools__fill(new_uid, value)  // Use fresh UIDs
```

**When to re-snapshot:**
- After form submissions
- After slider/input changes that trigger re-renders
- After navigation
- After any JavaScript that modifies DOM

**Pattern:**
```
1. take_snapshot
2. interact (click/fill/etc)
3. take_snapshot again
4. interact with new UIDs
```

## Screenshot Saving

**Problem:** `filePath` parameter doesn't save to disk (Chrome DevTools limitation)

**Solution:** Screenshots are returned as base64 image data in the response, not saved to filesystem.

**Workaround for Gemini analysis:**
Use Gemini's image upload API directly with the base64 data or save screenshot manually first.
