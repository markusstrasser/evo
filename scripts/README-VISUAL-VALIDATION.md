# Visual Validation Toolkit

**Problem**: AI vision models hallucinate geometric properties. Iterating based on subjective "how similar?" ratings leads to degradation, not improvement.

**Solution**: Quantitative geometric validation before aesthetic refinement.

## Quick Start

### 1. Install Dependencies

```bash
# Using uv (recommended)
uv pip install -r requirements.txt

# Or using pip (if not using uv)
pip3 install -r requirements.txt

# Verify installation (basic tools)
python3 -c "import cv2; import skimage; print('✓ Basic tools ready')"

# Verify ML models (if using --validate-* flags)
python3 -c "import torch; import transformers; import sam2; print('✓ ML models ready')"
```

**Dependencies**:
- **Basic validation**: opencv-python, scikit-image, numpy, scipy, matplotlib
- **ML validation**: torch, torchvision, transformers, sam2, timm, scikit-learn
- **Total size**: ~2-3GB (PyTorch + models)

### 2. Basic Usage

```bash
# One-shot validation (all stages)
scripts/visual-validate reference.png implementation.png

# Iterative development (watch for changes)
scripts/visual-iterate reference.png http://localhost:8080/lab.html --geometry-only
```

## Tools Overview

### `visual-validate-geometry` - Stage 1: Geometry First

**Purpose**: Verify structural correctness before wasting time on materials/lighting.

**Checks**:
- **Circularity**: Eccentricity < 0.1 (near-perfect circle)
- **Verticality**: Edge angles < 5° from vertical
- **Aspect Ratio**: Bounding box matches expected (±0.05)

**Usage**:
```bash
scripts/visual-validate-geometry ref.png impl.png

# Output:
# ✓ PASSED → geometry is correct, proceed to structure
# ✗ FAILED → shows specific fixes (camera angle, viewport, etc.)
```

**Example Failure Output**:
```
✗ GEOMETRY VALIDATION FAILED

Fix these issues:

1. Circularity
   Current: 0.234 (threshold: 0.100)
   → Adjust camera angle or use orthographic projection

2. Verticality
   Current: 8.3° (threshold: 5.0°)
   → Reduce camera pitch angle

3. Aspect Ratio
   Expected: 1.000
   Difference: 0.152
   → Adjust canvas dimensions or viewport
```

### `visual-validate-structure` - Stage 2: Quantitative Similarity

**Purpose**: Measure structural similarity with objective metrics (not AI ratings).

**Metrics**:
- **SSIM**: Structural Similarity Index (> 0.80 = good)
- **RMSE**: Root Mean Square Error (< 15% = good)
- **Histogram Correlation**: Color/lighting distribution (> 0.85 = good)

**Usage**:
```bash
scripts/visual-validate-structure ref.png impl.png --normalize

# Output:
# ✓ PASSED → SSIM: 87%, ready for aesthetic polish
# ✗ FAILED → shows which metrics failed and why
```

**Example Success Output**:
```
✓ STRUCTURAL VALIDATION PASSED

  SSIM:                 87.3%
  RMSE:                 12.4%
  Histogram Correlation: 91.2%

Structural similarity is good. Optionally refine aesthetics with AI.
```

### `visual-validate-depth` - ML-Based Depth Validation

**Purpose**: Compare height/depth maps using Depth Anything V2 for accurate 3D structure validation.

**Model**: [Depth Anything V2](https://github.com/DepthAnything/Depth-Anything-V2) (monocular depth estimation)

**Metrics**:
- **MAE**: Mean Absolute Error (< 0.15 = good)
- **RMSE**: Root Mean Square Error (< 0.20 = good)
- **Correlation**: Depth pattern correlation (> 0.70 = good)

**Usage**:
```bash
scripts/visual-validate-depth impl.png ref.png

# Output: Depth maps + difference heatmap
# Validates that pin heights match reference
```

**When to use**: Pin arrays, 3D structures, height-based patterns

### `visual-validate-grid` - ML-Based Grid Detection

**Purpose**: Detect and validate grid structure using SAM 2 segmentation.

**Model**: [SAM 2](https://github.com/facebookresearch/sam2) (Segment Anything Model 2)

**Metrics**:
- **Pin Count**: Detected vs expected (< 10% diff = good)
- **Spacing**: Grid spacing uniformity (< 15% diff = good)
- **Angle**: Grid alignment (< 10° diff = good)
- **Regularity**: Grid pattern consistency (< 0.15 diff = good)

**Usage**:
```bash
scripts/visual-validate-grid impl.png ref.png

# Output: Segmentation masks + grid visualization
# Validates pin array structure and spacing
```

**When to use**: Regular grids, pin arrays, tile patterns

### `visual-validate-materials` - ML-Based Material/Texture Validation

**Purpose**: Compare visual features and materials using DINOv2.

**Model**: [DINOv2](https://github.com/facebookresearch/dinov2) (self-supervised vision transformer)

**Metrics**:
- **Cosine Similarity**: Feature vector similarity (> 0.75 = good)
- **Euclidean Similarity**: Feature distance (> 0.75 = good)
- **Manhattan Similarity**: Alternative distance metric (> 0.75 = good)
- **Mean Similarity**: Average of all metrics (> 0.75 = good)

**Usage**:
```bash
scripts/visual-validate-materials impl.png ref.png

# For more detailed patch-based comparison
scripts/visual-validate-materials impl.png ref.png --use-patches

# Output: Feature similarity scores + visual comparison
# Validates materials, textures, and visual appearance
```

**When to use**: Material matching, texture similarity, visual feature comparison

### `visual-validate` - Orchestrator (Multi-Stage Pipeline)

**Purpose**: Run stages in order, stop on first failure.

**Pipeline** (basic):
1. Geometry validation → if fail, STOP
2. Structure validation → if fail, STOP
3. AI feedback (optional) → suggestions for polish

**Pipeline** (with ML):
1. Geometry validation → if fail, STOP
2. Depth validation (optional, ML) → if fail, STOP
3. Grid validation (optional, ML) → if fail, STOP
4. Materials validation (optional, ML) → if fail, STOP
5. Structure validation → if fail, STOP
6. AI feedback (optional) → suggestions for polish

**Usage**:
```bash
# Basic pipeline (geometry + structure)
scripts/visual-validate ref.png impl.png

# Full ML pipeline (all validations)
scripts/visual-validate ref.png impl.png --validate-all-ml

# Selective ML validations
scripts/visual-validate ref.png impl.png --validate-depth --validate-materials

# Geometry only (fastest iteration)
scripts/visual-validate ref.png impl.png --geometry-only

# Skip AI feedback (quantitative only)
scripts/visual-validate ref.png impl.png --skip-ai

# Custom AI prompt
scripts/visual-validate ref.png impl.png --ai-prompt "Focus on material shininess"
```

### `visual-iterate` - Watch + Validate on Change

**Purpose**: Iterative development with automatic validation after file changes.

**Workflow**:
1. Watch source files (e.g., `src/lab/`)
2. On change → wait for hot reload
3. Prompt for screenshot
4. Run validation pipeline
5. Show results and suggestions
6. Repeat

**Usage**:
```bash
# Geometry-first iteration (fastest)
scripts/visual-iterate ref.png http://localhost:8080/lab.html --geometry-only

# Full pipeline with AI
scripts/visual-iterate ref.png http://localhost:8080/lab.html

# Custom watch paths
scripts/visual-iterate ref.png http://localhost:8080 -w src/lab/,public/
```

## Validation Pipeline Architecture

### Basic Pipeline

```
┌─────────────────────────────────────────────────┐
│  Stage 1: Geometry (REQUIRED)                   │
│  ─────────────────────────────────────────────  │
│  • Circularity test (eccentricity)              │
│  • Verticality check (edge angles)              │
│  • Aspect ratio (bounding box)                  │
│                                                  │
│  EXIT: 0 = PASS, 1 = FAIL (shows fixes)         │
└─────────────────────────────────────────────────┘
                    ↓ (only if PASS)
┌─────────────────────────────────────────────────┐
│  Stage 2: Structure (QUANTITATIVE)              │
│  ─────────────────────────────────────────────  │
│  • SSIM (structural similarity)                 │
│  • RMSE (pixel difference)                      │
│  • Histogram correlation (lighting/color)       │
│                                                  │
│  EXIT: 0 = PASS, 1 = FAIL (shows metrics)       │
└─────────────────────────────────────────────────┘
                    ↓ (only if PASS)
┌─────────────────────────────────────────────────┐
│  Stage 3: AI Feedback (OPTIONAL)                │
│  ─────────────────────────────────────────────  │
│  • Material quality suggestions                 │
│  • Lighting refinement ideas                    │
│  • Perceptual polish                            │
│                                                  │
│  EXIT: Always 0 (suggestions only)              │
└─────────────────────────────────────────────────┘
```

### Extended Pipeline (with ML)

```
┌─────────────────────────────────────────────────┐
│  Stage 1: Geometry (REQUIRED)                   │
│  ─────────────────────────────────────────────  │
│  • OpenCV-based geometric validation            │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Stage 2: Depth (OPTIONAL, ML)                  │
│  ─────────────────────────────────────────────  │
│  • Depth Anything V2 - height map comparison    │
│  • MAE, RMSE, correlation metrics               │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Stage 3: Grid (OPTIONAL, ML)                   │
│  ─────────────────────────────────────────────  │
│  • SAM 2 - grid segmentation & structure        │
│  • Pin count, spacing, angle, regularity        │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Stage 4: Materials (OPTIONAL, ML)              │
│  ─────────────────────────────────────────────  │
│  • DINOv2 - visual feature comparison           │
│  • Cosine, Euclidean, Manhattan similarity      │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Stage 5: Structure (REQUIRED)                  │
│  ─────────────────────────────────────────────  │
│  • SSIM, RMSE, histogram correlation            │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│  Stage 6: AI Feedback (OPTIONAL)                │
│  ─────────────────────────────────────────────  │
│  • Gemini-based aesthetic suggestions           │
└─────────────────────────────────────────────────┘
```

**Enable ML stages**: Use `--validate-depth`, `--validate-grid`, `--validate-materials`, or `--validate-all-ml`
```

## Workflow: Matching a Visual Reference

### Step 1: Extract Reference Frame

```bash
# From video
ffmpeg -i video.mp4 -ss 00:00:05 -vframes 1 reference.png

# Or from existing image
cp ~/Downloads/target.png reference.png
```

### Step 2: Geometry-First Iteration

```bash
# Start dev server (if not running)
npm run dev

# Watch + validate geometry only (fastest iteration)
scripts/visual-iterate reference.png http://localhost:8080/lab.html --geometry-only

# Process:
# 1. Make code changes (camera angle, viewport, etc.)
# 2. Hot reload happens
# 3. Save screenshot when prompted
# 4. Geometry validation runs
# 5. If FAIL → shows specific fixes
# 6. Repeat until geometry PASSES
```

### Step 3: Structure Validation

```bash
# Once geometry passes, check structure
scripts/visual-validate reference.png latest-screenshot.png --skip-ai

# If structure fails:
# - SSIM low → layout/spacing differs
# - RMSE high → brightness/contrast off
# - Histogram low → color/lighting distribution differs
```

### Step 4: Aesthetic Polish (Optional)

```bash
# Only after geometry + structure pass
scripts/visual-validate reference.png latest-screenshot.png

# AI feedback will focus on:
# - Material properties (shininess, roughness)
# - Lighting quality (direction, intensity)
# - Color/tone adjustments
```

## Key Principles

### 1. Geometry Before Aesthetics
Wrong structure can't be polished. Always validate geometry first.

### 2. Quantitative Before Qualitative
Trust SSIM/RMSE metrics over AI "looks similar" ratings.

### 3. Stop on Failure
Don't iterate on broken geometry. Fix fundamentals first.

### 4. AI is for Polish, Not Validation
Use AI feedback only after quantitative validation passes.

### 5. Iterate with Checkpoints
Use `visual-iterate --geometry-only` for fast feedback loops.

## Common Failure Modes (Fixed by This Toolkit)

### ❌ Old Way (Broken)
```
1. Make change
2. Ask Gemini "rate similarity 1-10"
3. Gemini says "8/10, very similar!"
4. Actually geometry is wrong (ellipse not circle)
5. Iterate on materials/lighting
6. Gemini says "9/10, excellent!"
7. End with wrong geometry, wasted time
```

### ✅ New Way (This Toolkit)
```
1. Make change
2. Run geometry validation
3. ✗ FAILED: Eccentricity 0.23 (expected < 0.1)
   → Adjust camera angle
4. Fix camera angle
5. ✓ Geometry PASSED
6. Run structure validation
7. ✓ SSIM 87% PASSED
8. Optionally polish with AI
```

## Thresholds and Tuning

### Geometry (Strict)
```bash
# Default thresholds (strict)
--circularity-threshold 0.1   # Nearly perfect circle
--verticality-threshold 5.0   # Within 5° of vertical
--aspect-ratio-tolerance 0.05 # ±5% of expected

# Relaxed (for rough matching)
--circularity-threshold 0.2
--verticality-threshold 10.0
--aspect-ratio-tolerance 0.1
```

### Structure (Flexible)
```bash
# Default thresholds (good similarity)
--ssim-threshold 0.80        # 80% structural match
--rmse-threshold 15.0        # 15% pixel difference
--histogram-threshold 0.85   # 85% distribution match

# Strict (near-identical)
--ssim-threshold 0.90
--rmse-threshold 10.0
--histogram-threshold 0.90
```

## Output Files

### Geometry Validation
```
/tmp/geo-validation/
  reference_geometry.png      # Annotated reference (contour, ellipse, bbox)
  implementation_geometry.png # Annotated implementation
```

### Structure Validation
```
/tmp/structure-validation/
  ssim_diff.png              # Heatmap showing differences
  metrics.txt                # Numeric scores
```

### Full Pipeline
```
/tmp/visual-validation/
  geometry/
    reference_geometry.png
    implementation_geometry.png
  structure/
    ssim_diff.png
    metrics.txt
  comparison.png             # Side-by-side for AI
  ai-feedback.txt            # AI suggestions
```

## Integration with Existing Tools

### With `visual-compare` (Legacy)
```bash
# Old: RMSE only
scripts/visual-compare ref.png impl.png

# New: Geometry + Structure + AI
scripts/visual-validate ref.png impl.png
```

### With `visual-diff` (Legacy)
```bash
# Old: Manual side-by-side watching
scripts/visual-diff ref.png http://localhost:8080

# New: Auto-validate with checkpoints
scripts/visual-iterate ref.png http://localhost:8080 --geometry-only
```

## Troubleshooting

### "opencv not found"
```bash
pip3 install opencv-python scikit-image numpy
```

### "Could not find main object"
The image needs clear foreground/background separation. Try:
- Increase contrast
- Use solid background color
- Crop to main subject first

### Geometry validation too strict
Relax thresholds:
```bash
scripts/visual-validate-geometry ref.png impl.png \
  --circularity-threshold 0.2 \
  --verticality-threshold 10
```

### SSIM too low despite looking similar
Use `--normalize` to resize images:
```bash
scripts/visual-validate-structure ref.png impl.png --normalize
```

## Advanced Usage

### Batch Validation
```bash
# Validate multiple iterations
for i in {1..10}; do
  scripts/visual-validate ref.png iteration-$i.png --geometry-only
done
```

### Custom Validation Checkpoints
```bash
# Only check circularity (use python directly)
python3 scripts/visual-validate-geometry ref.png impl.png \
  --circularity-threshold 0.05 \
  --verticality-threshold 999 \
  --aspect-ratio-tolerance 999
```

### AI-Only Feedback (Skip Validation)
```bash
# Create comparison and ask Gemini directly
magick ref.png impl.png +append comparison.png
gemini image analyze comparison.png -p "LEFT=ref, RIGHT=impl. Material/lighting changes?"
```

## Design Philosophy

This toolkit implements the research findings documented in `.agentlog/core-problem-and-research-needs.md`:

1. **VLMs are shape-blind**: Can't reliably assess geometry (< 50% accuracy on basic shapes)
2. **Ground truth first**: Quantitative validation before subjective feedback
3. **Multi-stage pipeline**: Geometry → Structure → Aesthetics
4. **Stop on failure**: Don't polish broken geometry

See `docs/VISUAL_VALIDATION.md` for full architecture documentation.

## Future Enhancements

- [ ] Pattern/grid alignment detection (Stage 1.5)
- [ ] MCP integration for automated screenshots
- [ ] Parallel multi-model AI feedback (Gemini + Claude + GPT)
- [ ] Visual regression testing (save snapshots, compare over time)
- [ ] 3D pose estimation from 2D reference (camera angle calculator)
