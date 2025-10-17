---
name: Visual Validation Toolkit
description: Analyze and compare visual outputs from canvas/WebGL. Extract wave patterns, lighting, geometry, and get actionable fixes (e.g., "reduce lighting by 40%"). Triggers on visual, canvas, webgl, validate, compare, analyze. Requires Python3, OpenCV, numpy, pillow. No network required.
---

# Visual Validation Toolkit

## Overview

This skill packages the visual validation workflow for analyzing and debugging canvas/WebGL output. It provides two main capabilities:

1. **Reference Analysis** - Extract patterns from reference images (wave frequency, lighting, geometry)
2. **Actionable Comparison** - Compare reference vs implementation and get specific fixes

## When to Use

Use this skill when you need to:
- Debug visual output that doesn't match reference
- Extract wave patterns, spacing, frequency from reference images
- Get specific parameter adjustments (e.g., "reduce lighting by 40%")
- Analyze geometry (ring count, spacing, radius)
- Compare brightness, contrast, color distribution

## Prerequisites

**Required:**
- Python 3.7+
- OpenCV (`opencv-python`)
- NumPy
- Pillow (PIL)

**Install:**
```bash
pip3 install opencv-python numpy pillow
```

**Verify:**
```bash
./run.sh check-env
```

## Workflow

### Two-Stage Process

**Stage 1: Analyze Reference** (understand the target)
```bash
./run.sh analyze reference.png
```

Output:
```
Wave Analysis:
- Ring count: 15
- Spacing: 17.1px average
- Frequency: ~6 Hz
- Brightness: 71.2

Geometry:
- Center: (256, 256)
- Radii: [50, 67, 84, ...]
```

**Stage 2: Get Actionable Fixes** (compare and fix)
```bash
./run.sh compare reference.png implementation.png
```

Output:
```
Fixes needed:
✗ Scene 140% too bright → dir-light1: intensity *= 0.60
✗ Ring spacing 22% too wide → frequency *= 1.22
✓ Geometry matches (15 rings)
```

## Available Commands

### `analyze` - Extract Patterns from Reference

```bash
# Basic analysis
./run.sh analyze reference.png

# Specific aspect
./run.sh analyze reference.png --aspect waves
./run.sh analyze reference.png --aspect lighting
./run.sh analyze reference.png --aspect geometry

# Save results
./run.sh analyze reference.png --output analysis.json
```

**Output:**
- Wave patterns (frequency, amplitude, spacing)
- Lighting analysis (brightness, contrast, shadows)
- Geometry measurements (positions, sizes, counts)
- Color distribution

### `compare` - Get Actionable Fixes

```bash
# Full comparison
./run.sh compare reference.png implementation.png

# Specific aspect
./run.sh compare reference.png impl.png --aspect lighting
./run.sh compare reference.png impl.png --aspect geometry

# Different output formats
./run.sh compare ref.png impl.png --format json
./run.sh compare ref.png impl.png --format yaml
./run.sh compare ref.png impl.png --format markdown
```

**Output:**
- Specific parameter adjustments
- Percentage differences
- Pass/fail for each aspect
- Code-ready fixes

### `batch` - Process Multiple Images

```bash
# Analyze all references
./run.sh batch analyze references/*.png

# Compare test suite
./run.sh batch compare references/ implementations/
```

## Configuration

### Analysis Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Wave detection threshold | 0.5 | Sensitivity for wave detection |
| Min ring count | 5 | Minimum rings to detect |
| Max ring count | 50 | Maximum rings to detect |
| Brightness tolerance | 5% | Acceptable brightness variance |
| Contrast tolerance | 10% | Acceptable contrast variance |
| Position tolerance | 2.0px | Acceptable position drift |
| Size tolerance | 5% | Acceptable size variance |

### Comparison Thresholds

| Threshold | Value | Meaning |
|-----------|-------|---------|
| Fail | >15% | Significant difference, fix required |
| Warn | >5% | Moderate difference, review recommended |
| Pass | <2% | Acceptable difference |

### Output Formats

| Format | Use For | Features |
|--------|---------|----------|
| text | Human reading | Emoji, percentages, fixes |
| json | Automation, CI/CD | Parseable, metadata |
| markdown | Documentation | Summary, details, formatting |
| yaml | Config-style output | Structured, readable |

## Output Formats

### Text (Default)

```
Wave Analysis:
  Ring count: 15
  Spacing: 17.1px (±2.3)
  Frequency: 5.8 Hz

Fixes:
  ✗ Brightness +40% → multiply by 0.71
  ✓ Geometry OK
```

### JSON

```json
{
  "waves": {
    "ring_count": 15,
    "spacing": {"mean": 17.1, "std": 2.3},
    "frequency": 5.8
  },
  "fixes": [
    {"aspect": "brightness", "current": 100, "target": 71, "action": "multiply by 0.71"}
  ]
}
```

### Markdown

```markdown
## Visual Analysis

### Wave Patterns
- **Ring count**: 15
- **Spacing**: 17.1px (±2.3)
- **Frequency**: 5.8 Hz

### Fixes Required
- ❌ **Brightness**: Scene 40% too bright → `dir-light1.intensity *= 0.71`
- ✅ **Geometry**: Matches reference
```

## Examples

### Example 1: Debug Wave Rendering

```bash
# 1. Analyze reference to understand target
./run.sh analyze reference-waves.png

# Output shows: 20 rings, 12.5px spacing, 8Hz frequency

# 2. Compare your implementation
./run.sh compare reference-waves.png my-waves.png

# Output: "Ring spacing 30% too wide → frequency *= 1.30"

# 3. Apply fix in code
# Before: frequency: 8
# After:  frequency: 8 * 1.30 = 10.4
```

### Example 2: Lighting Adjustment

```bash
./run.sh compare reference-lit.png my-scene.png --aspect lighting

# Output: "Scene 140% too bright → dir-light1: intensity *= 0.42"

# Apply in code:
# Before: dir-light1.intensity = 2.0
# After:  dir-light1.intensity = 2.0 * 0.42 = 0.84
```

### Example 3: Full Scene Validation

```bash
./run.sh compare reference.png implementation.png --format markdown > validation.md

# Generates markdown report with all aspects
# Use in CI/CD or commit to repo
```

### Example 4: Batch Test Suite

```bash
# Test all scenes
./run.sh batch compare tests/references/ tests/outputs/

# Summary at end:
# Passed: 15/20
# Failed: 5/20 (lighting issues)
```

## Tips & Best Practices

1. **Start with reference analysis**
   - Understand the target before comparing
   - Save analysis results for documentation

2. **Use specific aspects for focused debugging**
   - `--aspect lighting` when only debugging lights
   - `--aspect geometry` for positioning issues
   - Faster and more targeted fixes

3. **Adjust tolerances for your use case**
   - Tighter tolerances for precise graphics
   - Looser for stylized/approximate rendering

4. **Use batch mode for regression testing**
   - Catch visual regressions early
   - Track fixes across commits
   - Generate comparison reports

5. **Save output as JSON for automation**
   - Parse in CI/CD scripts
   - Track metrics over time
   - Feed into dashboards

## Common Pitfalls

- **Python not installed**: Check with `./run.sh check-env`
- **Images different sizes**: Will resize automatically but may affect measurements
- **No clear patterns**: Increase threshold setting
- **False positives**: Use specific `--aspect` or tighten tolerances

## Troubleshooting

**"OpenCV not found"**
```bash
pip3 install opencv-python
```

**"Analysis produces no results"**
- Check image is not completely black/white
- Try lowering threshold
- Ensure image has detectable patterns

**"Fixes are wildly off"**
- Verify reference and implementation are same resolution
- Check units match (pixels vs relative)
- Adjust tolerances

**"Batch mode fails midway"**
- Check all images readable
- Ensure sufficient disk space for cache
- Run with `--verbose` for details

## Integration with Dev Workflow

### Pre-commit Hook

```bash
# .git/hooks/pre-commit
#!/bin/bash
./skills/visual/run.sh batch compare tests/references/ tests/outputs/
```

### CI/CD

```yaml
# .github/workflows/visual-tests.yml
- name: Visual Validation
  run: |
    ./skills/visual/run.sh batch compare \
      tests/references/ \
      tests/outputs/ \
      --format json > visual-report.json
    # Fail if any test failed
    jq -e '.passed == .total' visual-report.json
```

### Quick Check During Development

```bash
# Watch for changes
watch -n 5 './skills/visual/run.sh compare reference.png output/latest.png'
```

## Scripts Wrapped

The skill orchestrates these existing scripts:
- `scripts/visual-analyze-reference` - OpenCV pattern extraction
- `scripts/visual-compare-actionable` - Diff + fixes generation
- Python analysis scripts in `scripts/` (basic + ML stages)

## Resources (Level 3)

- `run.sh` - Main CLI wrapper with analyze, compare, batch commands
- `data/test-references/` - Example reference images
- `data/test-outputs/` - Example outputs for testing
- `data/calibration/` - Calibration images for tolerance tuning
- `scripts/visual-analyze-reference` - Reference analysis script
- `scripts/visual-compare-actionable` - Comparison script

## See Also

- Project docs: `../../CLAUDE.md#visual-validation`
- Scripts: `../../scripts/visual-analyze-reference`, `../../scripts/visual-compare-actionable`
- Full toolkit docs: `../../scripts/README-VISUAL-VALIDATION.md`
- Architecture: `../../docs/VISUAL_VALIDATION.md`
