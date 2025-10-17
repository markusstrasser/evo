# Visual Validation Skill

Analyze and compare canvas/WebGL visual outputs - extract patterns, get actionable fixes.

## Full Documentation

See **[SKILL.md](SKILL.md)** for complete documentation including:
- Two-stage workflow (analyze → compare)
- Configuration (thresholds, tolerances, output formats)
- Examples (waves, lighting, geometry)
- Tips and troubleshooting
- CI/CD integration

## Quick Start

```bash
# Check environment
./run.sh check-env

# Analyze reference
./run.sh analyze reference.png

# Compare and get fixes
./run.sh compare reference.png implementation.png

# Focus on specific aspect
./run.sh compare ref.png impl.png --aspect lighting

# Batch process
./run.sh batch analyze tests/*.png
```

## Commands

- **analyze** - Extract wave patterns, lighting, geometry from reference
- **compare** - Compare reference vs implementation, get specific fixes
- **batch** - Process multiple images
- **check-env** - Verify Python dependencies

## Requirements

```bash
pip3 install opencv-python numpy pillow
```

## Example Output

```
Comparison Results:
  Mean difference: 15.2
  Max difference: 89.0

Fixes:
  ✗ Scene 40% too bright → multiply intensity by 0.71
  ✓ Brightness OK (within 10%)
  ✓ Overall match good
```
