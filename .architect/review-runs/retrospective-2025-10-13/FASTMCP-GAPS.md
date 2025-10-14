# FastMCP Features We're Not Using

## ✅ UPDATE: High-Value Features Implemented (2025-10-13)

**Completed in ~20 minutes** (as predicted):

1. **Logging & Observability** ✅
   - Added `ctx.info()` calls throughout all tools
   - Added `ctx.debug()` for detailed diagnostics  
   - Added `ctx.error()` for provider failures
   - Example: `await ctx.info("🚀 Starting proposal generation")`

2. **Progress Reporting** ✅
   - Tournament progress: "⚖️  Running tournament evaluation..."
   - Winner announcement: "🏆 Winner: {winner_id} (confidence: 87%)"
   - Stage tracking: "📋 Stage 1/3: Generating proposals"
   - Quality metrics: "✅ Validation: 4/4 checks passed"

3. **ResourceError** ✅
   - Replaced `ValueError` with `ResourceError` in all resource handlers
   - MCP-compliant error messages for clients
   - Example: `raise ResourceError(f"Run not found: {run_id}")`

**Impact**:
- ✓ Users can see progress during 2+ minute tournament evaluations
- ✓ Debug logs show provider failures without crashing
- ✓ Stage transitions are visible (proposal → ranking → decision)
- ✓ MCP protocol compliance for error handling

**Files modified**: `mcp/review/server.py`

**Result**: The MCP is now fully observable - users get real-time feedback on what's happening during long operations.

---

## Current Implementation

**What we have**:
- ✅ Tools (5 total)
- ✅ Resources (4 URIs)
- ✅ Prompts (2 templates)
- ✅ Server composition (mounts eval-MCP)
- ✅ Context injection
- ✅ Async throughout
- ✅ Flat file storage

**What we're NOT using**:

---

## 1. Logging & Observability ⚠️ HIGH VALUE

### Missing
```python
# We don't log anything currently
await propose(description)  # Silent, no visibility
```

### Should Add
```python
@mcp.tool
async def propose(description: str, ctx: Context = None):
    if ctx:
        await ctx.info("🚀 Starting proposal generation")
        await ctx.info(f"📝 Querying 3 providers: gemini, codex, grok")

    # ... generate proposals ...

    if ctx:
        await ctx.info(f"✅ Generated {len(proposals)} proposals")
```

**Value**:
- ✓ Visibility during long operations
- ✓ Debug without changing code
- ✓ Client sees progress in real-time

**Effort**: 5-10 minutes (add ctx.info() calls)

---

## 2. Progress Reporting 🔥 HIGH VALUE

### Missing
```python
# Tournament takes 2+ minutes, no feedback
ranking = await rank_proposals(run_id)  # Black box
```

### Should Add
```python
@mcp.tool
async def rank_proposals(run_id: str, ctx: Context = None):
    if ctx:
        await ctx.info("⚖️  Starting tournament evaluation...")

    # Call eval-MCP (which takes 2+ min)
    ranking_result = await eval_mcp.compare_multiple(...)

    if ctx:
        await ctx.info(f"🏆 Winner: {winner_id} (confidence: {confidence:.1%})")
```

**Value**:
- ✓ User knows it's working (not frozen)
- ✓ Can see intermediate progress
- ✓ Better UX during slow operations

**Effort**: 5 minutes (add progress messages)

---

## 3. Error Handling (ResourceError) ⚠️ MEDIUM VALUE

### Missing
```python
# Generic errors, not MCP-aware
if not run_data:
    raise ValueError(f"Run not found: {run_id}")
```

### Should Add
```python
from fastmcp.exceptions import ResourceError

@mcp.resource("review://runs/{run_id}")
async def get_run_state(run_id: str, ctx: Context = None):
    run_data = load_run(run_id)
    if not run_data:
        # ResourceError = always sent to client (no masking)
        raise ResourceError(f"Run not found: {run_id}")
    return run_data
```

**Value**:
- ✓ Better error messages to client
- ✓ MCP protocol compliance
- ✓ Distinguishes user errors from server errors

**Effort**: 10 minutes (replace ValueError with ResourceError)

---

## 4. Middleware ❌ LOW VALUE (for solo dev)

### What's Available
- `ErrorHandlingMiddleware` - Catch/log all errors
- `LoggingMiddleware` - Log all requests/responses
- `TimingMiddleware` - Measure request duration
- `RateLimitingMiddleware` - Throttle requests
- `RetryMiddleware` - Auto-retry transient failures

### Why NOT Add
- Solo dev = no rate limiting needed
- No DB connections = no retry logic needed
- Can just restart on errors
- Adds complexity to debug

**Value**: ❌ Overkill for "can barely keep track" use case

**Effort**: 30+ minutes (learning + setup)

---

## 5. Sampling (ctx.sample) ⚠️ MAYBE

### Missing
```python
# We call LLMs via subprocess
result = await call_gemini(description, ctx)  # Shell out
```

### Could Use
```python
@mcp.tool
async def propose(description: str, ctx: Context = None):
    # Ask client's LLM directly (no subprocess)
    response = await ctx.sample(
        messages=f"Generate proposal for: {description}",
        system_prompt="You are a pragmatic architect...",
        temperature=0.7
    )
    return response.text
```

**Pros**:
- ✓ No subprocess overhead
- ✓ Uses client's LLM (respects user's provider choice)
- ✓ Cleaner code

**Cons**:
- ✗ Requires client to support sampling
- ✗ Loss of multi-provider diversity (gemini+codex+grok)
- ✗ Current approach works fine

**Value**: ⚠️ Neutral tradeoff

**Effort**: 20 minutes (refactor providers.py)

---

## 6. Lifespan Management ❌ LOW VALUE

### What's Available
```python
@asynccontextmanager
async def lifespan(mcp: FastMCP):
    # Startup: connect to DB, load caches, etc.
    print("Starting up...")
    yield
    # Shutdown: close connections, cleanup
    print("Shutting down...")

mcp = FastMCP("review-flow", lifespan=lifespan)
```

### Why NOT Add
- No DB connections to manage
- No caches to warm up
- Files = already persistent
- Just adds complexity

**Value**: ❌ Not needed for flat files

**Effort**: 15 minutes (boilerplate)

---

## 7. Tag-Based Filtering ❌ LOW VALUE

### What's Available
```python
@mcp.tool(tags={"public", "safe"})
def propose(...): ...

mcp = FastMCP(include_tags={"public"})  # Only expose public tools
```

### Why NOT Add
- Small server (5 tools total)
- Solo dev = no multi-tenant needs
- All tools are "public" to the user

**Value**: ❌ Overkill for 5 tools

**Effort**: 5 minutes (add tags)

---

## 8. Authentication ❌ LOW VALUE

### What's Available
```python
from fastmcp.auth import BearerTokenProvider

mcp = FastMCP("review-flow")
mcp.auth = BearerTokenProvider({"secret-token": "user"})
```

### Why NOT Add
- Local-only server
- Solo dev = no multi-user
- No sensitive data (just proposals)

**Value**: ❌ Not needed for local MCP

**Effort**: 10 minutes (setup)

---

## 9. Caching ❌ LOW VALUE

### What's Available
```python
from functools import lru_cache

@lru_cache(maxsize=100)
def load_run(run_id: str):
    # Cache loaded runs
    ...
```

### Why NOT Add
- Runs are one-time (not frequently re-read)
- Files are already fast
- Adds memory pressure

**Value**: ❌ Premature optimization

**Effort**: 5 minutes (add decorator)

---

## Summary: What to Add?

### 🔥 High Value (Do These)

1. **Logging** (5-10 min)
   ```python
   await ctx.info("Starting...")
   await ctx.debug(f"Details: {data}")
   await ctx.error(f"Failed: {error}")
   ```

2. **Progress reporting** (5 min)
   ```python
   await ctx.info("⚖️  Running tournament...")
   await ctx.info(f"🏆 Winner: {winner}")
   ```

3. **ResourceError** (10 min)
   ```python
   from fastmcp.exceptions import ResourceError
   raise ResourceError("Run not found")
   ```

**Total effort**: 20-25 minutes
**Total value**: HIGH (observability++)

---

### ⚠️ Maybe Later

4. **ctx.sample()** (20 min) - Wait until subprocess becomes a problem
5. **Middleware** (30+ min) - Wait until we have real multi-user needs

---

### ❌ Don't Add

6. Lifespan management
7. Tag-based filtering
8. Authentication
9. Caching
10. Rate limiting
11. Retry middleware

**Reason**: Solo dev + flat files = YAGNI

---

## Recommendation

**Add logging + progress (25 minutes)**:
- Makes the MCP observable during long operations
- Helps debug when things go wrong
- No complexity cost (just ctx.info() calls)

**Skip everything else**:
- Solo dev doesn't need auth/rate-limiting/caching
- Current approach (subprocess + flat files) works fine
- "Can barely keep track" = minimize moving parts

**The 80/20**: Logging gives 80% of the observability value for 20% of the effort.
