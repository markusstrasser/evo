# Visual Validation Skill

Analyze and compare canvas/WebGL visual outputs - extract patterns, get actionable fixes.

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

## See Also

- Full docs: `SKILL.md`
- Scripts: `../../scripts/visual-*`
- Project docs: `../../CLAUDE.md#visual-validation`
