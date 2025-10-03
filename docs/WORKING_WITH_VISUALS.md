# Working with Visuals - Best Practices

## Core Principle

**Use your own eyes first.** Vision models are helpful but lossy. Always compare side-by-side yourself before relying on AI analysis.

## Iterative Refinement Process

### 1. Initial Analysis
- Extract reference frame: `ffmpeg -i video.mp4 -vframes 1 -f image2pipe -vcodec png - > /tmp/reference.png`
- Ask focused questions to vision model about SPECIFIC aspects (not general comparison)
- Get baseline understanding of key visual elements

### 2. Side-by-Side Comparison
```bash
magick reference.png implementation.png +append comparison.png
```
- **Look at it yourself** - don't just rely on model analysis
- Identify obvious differences (spacing, color, contrast, size)
- Trust your intuition about what looks wrong

### 3. Parallel Focused Queries
When stuck, ask 5+ Gemini instances in parallel with:
- The side-by-side comparison image
- Current code parameters
- Different focus areas per query (spacing, lighting, materials, etc.)

```bash
# Example: 5 parallel analyses with different focus areas
bb -m scripts.research.gemini-media image analyze comparison.png \
  -p "PARAMS + COMPARISON. Focus on SPACING" -o q1.txt &
bb -m scripts.research.gemini-media image analyze comparison.png \
  -p "PARAMS + COMPARISON. Focus on LIGHTING" -o q2.txt &
# ... etc
wait
```

### 4. Handling Contradictions
- **Don't dismiss outliers** - they might be right
- Test extreme versions of contradictory suggestions
- Compare results visually yourself
- Iterate toward balance

Example: Got suggestions for pin-radius from 0.04 to 0.20. Tested both extremes - 0.20 was correct.

## Key Learnings

### What Works
- **Bigger pins with clear form** > tiny dense pins
  - Each pin should be visually distinct with shading
  - Sculptural quality requires substantial geometry

- **Strong directional lighting** > flat ambient
  - Creates depth and contrast
  - Shows 3D cylindrical form
  - Don't over-soften trying to match smoothness

- **Side-by-side visual inspection** > AI ratings
  - Your eyes see things models miss
  - Models give inconsistent ratings (same image rated 6/10, then 10/10)

### What Doesn't Work
- Relying solely on vision model "similarity ratings"
- Making pins smaller/tighter to increase density (creates thin needles, not solid forms)
- Over-softening lighting to reduce contrast (washes out depth)
- Stopping when model says "good enough" without visual check

## Common Failure Modes

### 1. Premature Optimization Stop
**Symptom:** Stopping at "7/10 similarity" without side-by-side comparison
**Fix:** Always do final visual check yourself

### 2. Wrong Parameter Direction
**Symptom:** Making pins smaller when they should be bigger
**Fix:** Test both extremes when uncertain

### 3. Over-reliance on Single AI Opinion
**Symptom:** Following one model's advice that contradicts visual reality
**Fix:** Use parallel queries, compare recommendations, trust your eyes

### 4. Averaging Contradictions
**Symptom:** Taking middle ground between 0.04 and 0.20 pin radius
**Fix:** Test extremes first to find actual range

## Aesthetic Properties Checklist

When matching a reference visual:

- [ ] **Tightness** - spacing between elements
- [ ] **Contrast** - light/dark dramatic difference
- [ ] **Depth** - 3D form visible through shading
- [ ] **Density** - how packed elements are
- [ ] **Material quality** - specular highlights, shininess
- [ ] **Sculptural presence** - feels like physical object
- [ ] **Lighting direction** - where shadows fall
- [ ] **Color brightness** - not just hue but luminosity

## Tools & Techniques

### Quick Visual Diff
```bash
# Create triple reference for context
magick ref.png ref.png ref.png +append ref_triple.png

# Side-by-side with labels
magick ref.png impl.png +append comparison.png
```

### Gemini Analysis with Code
Include current parameters in prompt:
```
Current: pin-radius 0.065, spacing 0.14, color 0xcccccc
LEFT=REF, RIGHT=IMPL. What specific numeric changes to make RIGHT match LEFT?
Focus on: [specific aspect]
```

### Iteration Log
Track what worked:
- Initial params → visual result → what was wrong
- Changed X from A to B → got closer/worse
- Final params that worked

## Summary

**The meta-lesson:** Vision models are helpful for focused analysis but have inconsistent judgment. Your own side-by-side visual comparison is the ground truth. Use models to get specific feedback on aspects you identify as wrong, not to tell you if it's "done."

## Session 2 Learnings: The AI Rating Trap

### Critical Failure Mode: Trusting AI Ratings Over Your Eyes

**What happened:** Got Gemini ratings of 7/10, then 9/10 while actual visual quality was ~5/10. AI said "nearly identical" when pins were thin needles vs thick cylinders.

**Why it failed:**
- Vision models are trained to be helpful/positive, biasing ratings upward
- Cropping issues caused black regions → AI couldn't see implementation properly
- AI focused on parameters matching vs actual visual similarity
- No ground truth validation (me looking at the comparison)

### New Tricks That Work

1. **Bbox-guided cropping** - Ask Gemini for bounding boxes, then crop precisely to avoid black regions
2. **Save comparisons to .agentlog/** - Let human (me) verify what AI claims
3. **Multiple contradictory queries** - Ask "should pins be bigger?" AND "rate pin thickness 1-10" to catch bias
4. **Detail extraction** - Crop to single pin or small region to focus AI on form, not composition
5. **Honest prompts** - "Be honest about X" or "What's actually different?" vs "Rate similarity"

### Updated Best Practices

**Before trusting any AI rating:**
1. Save comparison to .agentlog/ for human inspection
2. Ask AI for bounding boxes to verify crops are correct
3. Create detail crops (single pin or small region) for focused feedback
4. Run 2-3 queries with different phrasings to detect bias
5. **Most important: Look at it yourself BEFORE considering changes done**

**Iteration protocol:**
1. Make change → screenshot → side-by-side
2. Save to .agentlog/comparison-vN.png
3. Ask yourself: "Does this look ~same?"
4. If no, identify WHAT'S different (size? form? lighting?)
5. THEN query AI with focused question about that specific aspect
6. Apply changes and repeat

### What Actually Worked (Session 2)

**V5 → V6 breakthrough:**
- Stopped trusting "9/10" rating
- User feedback: "I'd rate it 5/10... pin radius way too small"
- Looked at comparison myself: pins were needles, not cylinders
- Increased radius 0.07 → 0.12, spacing 0.20 → 0.28
- Camera angle more top-down (y: 20→25, z: 8→3)
- Result: Actual visible cylindrical pins with form

**Key insight:** User's eyes > AI ratings. AI said "perfect" when pins were 1/3 the correct size.

## Session 2 Continued: Dimension Matching

### Critical: Match Canvas Dimensions FIRST

**Problem:** Created side-by-side comparisons with mismatched sizes:
- Reference: 600x600 (square)
- Implementation: 2400x1800 (high DPI, wrong aspect ratio)
- Result: Comparison looked terrible, impossible to judge

**Solution workflow:**
1. **Check reference dimensions:** `identify reference.png` → 600x600
2. **Set canvas to match:** Update canvas width/height to exact match
3. **Handle high DPI:** Screenshots may be 4x (2400x1800 from 600x600 canvas)
4. **Normalize for comparison:** `magick ref.png -resize 600x600 ref-norm.png`
5. **Create proper side-by-side:** Both at same size, equal 50:50 split

### Programmatic Comparison Tools

**Researched approaches** (via exa-code):
1. **pixelmatch** - Pixel-level diff with threshold, generates diff image
2. **resemble.js** - Mismatch percentage + visual diff
3. **odiff** - Fast Rust-based comparison
4. **ImageMagick compare** - Built-in: `magick compare -metric RMSE img1 img2 diff.png`

**Simple workflow that works:**
```bash
# Normalize both to same size
magick ref.png -resize 600x600 ref-norm.png
magick impl.png -resize 600x600 impl-norm.png

# Side-by-side
magick ref-norm.png impl-norm.png +append comparison.png

# Quantitative diff
magick compare -metric RMSE ref-norm.png impl-norm.png diff.png 2>&1
```

### Updated Best Practices

**Before ANY iteration:**
1. Match canvas dimensions to reference EXACTLY
2. Verify with `identify` command on both images
3. Normalize if needed (high DPI, different sizes)
4. Create 50:50 side-by-side at same resolution
5. THEN look with your eyes
6. THEN query AI with properly formatted comparison

**Framing analysis workflow:**
1. Ask Gemini for bounding box + padding % of both images
2. Compare: "Array occupies 71% width" vs "60% width" = too zoomed out
3. Adjust camera FOV or zoom to match
4. Re-check until framing matches (within ~5%)

### Critical: DPI-Aware Screenshot Cropping

**Problem:** Chrome DevTools screenshots at high DPI (2x or 4x) but reports logical pixels.
- Canvas at 600x600 logical, position (20, 20)
- Screenshot 1600x1600 = 2x DPI → canvas at (40, 40) physical, 1200x1200 size
- Screenshot 2400x1800 = 4x DPI → canvas at (80, 80) physical, 2400x2400 size

**Solution - Detect DPI ratio:**
```bash
# Get canvas logical position/size via DevTools
CANVAS_INFO=$(evaluate_script to get canvas.getBoundingClientRect())
LOGICAL_X=20, LOGICAL_Y=20, LOGICAL_W=600, LOGICAL_H=600

# Get screenshot actual size
SCREENSHOT_SIZE=$(identify screenshot.png | awk '{print $3}')  # e.g., 1600x1600

# Calculate DPI ratio
DPI_RATIO = SCREENSHOT_WIDTH / WINDOW_WIDTH  # e.g., 1600/800 = 2x

# Crop with physical coordinates
PHYS_X=$((LOGICAL_X * DPI_RATIO))
PHYS_Y=$((LOGICAL_Y * DPI_RATIO))
PHYS_W=$((LOGICAL_W * DPI_RATIO))
PHYS_H=$((LOGICAL_H * DPI_RATIO))

magick screenshot.png -crop ${PHYS_W}x${PHYS_H}+${PHYS_X}+${PHYS_Y} +repage canvas.png
```

**Or: Use full-page screenshot and auto-trim:**
```bash
# Take screenshot, auto-detect content
magick screenshot.png -fuzz 10% -trim +repage trimmed.png
# Resize to standard size
magick trimmed.png -resize 600x600! final.png
```
