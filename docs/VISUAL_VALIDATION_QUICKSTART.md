# Visual Validation - Quick Start

**Problem**: AI vision models hallucinate. Metrics like SSIM don't tell you what to fix.

**Solution**: Extract characteristics from reference, compare with implementation, get actionable code fixes.

## Two-Step Workflow

### 1. Analyze Reference (What to Implement)

```bash
scripts/visual-analyze-reference reference.png
```

**Output:**
```
📊 WAVE PATTERN: 15 rings, spacing 17.1px, frequency ~6
💡 LIGHTING: brightness 71.2/255 (dark), contrast 11.41 (dramatic)
🎨 COLOR: grayscale, neutral temperature
📐 GEOMETRY: eccentricity 0.810 (3/4 view)

🎯 IMPLEMENTATION GUIDANCE:
   Wave: (sin(dist * π * 6))
   Lighting: LOW ambient + directional
```

### 2. Compare and Fix

```bash
scripts/visual-compare-actionable reference.png implementation.png
```

**Output:**
```
🎯 ACTIONABLE FIXES:

1. Scene 140% too bright
   → Reduce lighting intensity by 140%
   Code: dir-light1: intensity *= -0.40

2. Too few rings: 4 vs 15
   → Increase wave frequency to ~22.5
   Code: (sin(dist * π * 22.5))
```

**Plus visualization:**
- Side-by-side images
- Radial wave pattern overlay (blue vs red)
- Difference heatmap
- Metrics table

## When to Use

- **Reference analysis**: Before implementing (understand target)
- **Actionable comparison**: During iteration (get specific fixes)
- **NOT for**: Geometry validation (use basic tools first)

## Files

- `scripts/visual-analyze-reference` - Reference extraction
- `scripts/visual-compare-actionable` - Actionable comparison
- `scripts/README-VISUAL-VALIDATION.md` - Full docs (basic + ML stages)
- `docs/VISUAL_VALIDATION.md` - Architecture

## ML Stages (Experimental)

Optional, require model downloads:
- `--validate-depth` - Depth Anything V2 (height maps)
- `--validate-grid` - SAM 2 (grid detection)
- `--validate-materials` - DINOv2 (texture similarity)

**Not recommended** - basic tools are faster and more actionable.
