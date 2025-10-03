# Visual Validation Pipeline

**Problem**: AI vision models hallucinate geometric properties. Iterating based on subjective ratings leads to degradation.

**Solution**: Multi-stage validation with quantitative metrics before AI feedback.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Stage 1: Geometry Validation (Required)        │
│  ✓ Circularity (aspect ratio, eccentricity)     │
│  ✓ Verticality (edge angles)                    │
│  ✓ Aspect ratio (bounding box)                  │
│  ✓ Pattern alignment                            │
│  → PASS/FAIL with specific corrections          │
└─────────────────────────────────────────────────┘
                    ↓ (if PASS)
┌─────────────────────────────────────────────────┐
│  Stage 2: Structural Similarity (Quantitative)  │
│  ✓ SSIM (structural similarity index)           │
│  ✓ MSE/RMSE (pixel difference)                  │
│  ✓ Histogram correlation (lighting/color)       │
│  → Numeric scores (0-100)                       │
└─────────────────────────────────────────────────┘
                    ↓ (if > threshold)
┌─────────────────────────────────────────────────┐
│  Stage 3: AI Aesthetic Feedback (Optional)      │
│  ✓ Material quality                             │
│  ✓ Lighting refinement                          │
│  ✓ Perceptual polish                            │
│  → Suggestions for refinement                   │
└─────────────────────────────────────────────────┘
```

## Tools

### 1. `scripts/visual-validate-geometry`
Checks geometric correctness using OpenCV/ImageMagick:
- Circularity test (fit ellipse, compute eccentricity < 0.1)
- Verticality check (Hough lines, angle histogram)
- Aspect ratio comparison
- Pattern grid alignment

**Output**: PASS/FAIL + specific fixes
```
✗ GEOMETRY FAILED
  - Base eccentricity: 0.23 (expected < 0.10) → Adjust camera angle
  - Pin verticality: 8° tilt (expected < 5°) → Reduce camera pitch
  - Aspect ratio: 1.15 (expected ~1.0) → Fix viewport dimensions
```

### 2. `scripts/visual-validate-structure`
Quantitative similarity metrics:
- SSIM (scikit-image or ImageMagick)
- RMSE (existing in visual-compare)
- Histogram correlation (color/lighting consistency)

**Output**: Numeric scores
```
✓ STRUCTURE PASSED (threshold: 80%)
  - SSIM: 87.3%
  - RMSE: 12.4% difference
  - Histogram correlation: 0.91
```

### 3. `scripts/visual-validate` (orchestrator)
Runs stages in order, stops on failure:
```bash
visual-validate ref.png impl.png

# Stage 1: Geometry
[RUNNING] Geometry validation...
✗ FAILED - Fix geometry first (see above)

# After fixes:
visual-validate ref.png impl.png
✓ Stage 1: Geometry PASSED
✓ Stage 2: Structure PASSED (SSIM: 87%)
→ Ready for aesthetic refinement (optional)
```

### 4. `scripts/visual-iterate` (AI-assisted workflow)
Combines validation with AI feedback:
```bash
visual-iterate ref.png http://localhost:8080/lab.html

# Watch src/, validate on change:
# 1. Screenshot implementation
# 2. Run geometry validation → if fail, show fixes
# 3. Run structure metrics → if fail, show diff
# 4. Run AI analysis (Gemini) → suggestions
# 5. Repeat
```

## Workflow

### First-time Setup
```bash
# Install OpenCV for Python
pip3 install opencv-python scikit-image numpy

# Verify tools
scripts/visual-validate --check-deps
```

### Matching a Visual Reference

**Step 1: Geometry First**
```bash
# Extract reference frame
ffmpeg -i video.mp4 -ss 00:00:05 -vframes 1 ref.png

# Take implementation screenshot
open http://localhost:8080/lab.html
# (screenshot to impl.png)

# Validate geometry
scripts/visual-validate-geometry ref.png impl.png

# Fix geometry issues (camera angle, aspect ratio)
# Repeat until geometry PASSES
```

**Step 2: Structure Validation**
```bash
# Check structural similarity
scripts/visual-validate-structure ref.png impl.png

# If SSIM < 80%, identify what's different:
# - Spacing (grid alignment)
# - Density (element count)
# - Contrast (lighting)
```

**Step 3: Aesthetic Refinement (Optional)**
```bash
# Only after geometry + structure pass
# Use AI for material/lighting polish
magick ref.png impl.png +append comparison.png
gemini image analyze comparison.png -p "LEFT=ref, RIGHT=impl. Material/lighting suggestions?"
```

### Iterative Development
```bash
# Auto-validate on file changes
scripts/visual-iterate ref.png http://localhost:8080/lab.html \
  --watch src/lab/ \
  --geometry-only  # Stop at geometry failures, don't run AI

# Or full pipeline with AI suggestions
scripts/visual-iterate ref.png http://localhost:8080/lab.html
```

## Validation Metrics

### Geometry Thresholds
- **Circularity**: eccentricity < 0.1 (near-perfect circle)
- **Verticality**: angle deviation < 5°
- **Aspect ratio**: ±0.05 of expected
- **Pattern alignment**: grid correlation > 0.9

### Structure Thresholds
- **SSIM**: > 0.80 (80% structural similarity)
- **RMSE**: < 15% pixel difference
- **Histogram correlation**: > 0.85

### AI Aesthetic Feedback (subjective)
Only consult after passing geometry + structure:
- Material properties (shininess, roughness)
- Lighting direction/intensity
- Color tone adjustments
- Perceptual polish

## Key Principles

1. **Geometry before aesthetics** - Wrong structure can't be polished
2. **Quantitative before qualitative** - Metrics before AI opinions
3. **Validate early, validate often** - Catch regressions immediately
4. **Trust numbers over ratings** - AI says "9/10" means nothing if SSIM is 45%
5. **Stop on geometry failure** - Don't iterate on broken structure

## Common Failure Modes (Fixed by This Pipeline)

### ❌ Old Way (Broken)
```
1. Make change
2. Ask AI "how similar 1-10?"
3. AI says "7/10, looks good!"
4. Actually SSIM is 45%, geometry is wrong
5. Iterate on materials, make it worse
6. AI now says "9/10!"
7. End up with wrong geometry, wasted time
```

### ✅ New Way (This Pipeline)
```
1. Make change
2. Run geometry validation
3. ✗ FAILED: base is elliptical (0.23 eccentricity)
4. Fix camera angle
5. ✓ Geometry PASSED
6. Run structure validation (SSIM: 87%)
7. ✓ Structure PASSED
8. Optionally polish with AI feedback
```

## Implementation Plan

See todo list in main session for task breakdown.

**Status**: Planning phase - designing tools before implementation
**Next**: Implement `visual-validate-geometry` with OpenCV
