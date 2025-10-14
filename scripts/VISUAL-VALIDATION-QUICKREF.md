# Visual Validation Quick Reference

## TL;DR

```bash
# Fast iteration (geometry only)
scripts/visual-iterate ref.png http://localhost:8080 --geometry-only

# Full validation
scripts/visual-validate ref.png impl.png

# Get specific fixes
scripts/visual-compare-actionable ref.png impl.png
```

## Script Organization

### Orchestrators (Babashka)
High-level workflows that call validators:
- `visual-validate` - Multi-stage pipeline (geometry → structure → AI)
- `visual-iterate` - Watch + validate on file changes
- `visual-compare` - Legacy RMSE comparison
- `visual-diff` - Legacy side-by-side watcher

### Validators (Python + bash wrappers)
Individual validation stages:
- `visual-validate-geometry` - Circularity, verticality, aspect ratio
- `visual-validate-structure` - SSIM, RMSE, histogram
- `visual-validate-depth` - ML depth map comparison (Depth Anything V2)
- `visual-validate-grid` - ML grid detection (SAM 2)
- `visual-validate-materials` - ML material comparison (DINOv2)

### Analyzers (Python + bash wrappers)
Extract features and provide fixes:
- `visual-analyze-reference` - Extract wave patterns, lighting, geometry
- `visual-compare-actionable` - Get specific fixes (e.g., "reduce lighting by 40%")

## Architecture

```
┌─────────────────────────────────────────┐
│ Orchestrators (Babashka)                │
│  visual-validate, visual-iterate        │
│  ↓ calls                                 │
│                                          │
│ Bash Wrappers (venv activation)         │
│  visual-validate-geometry, etc.         │
│  ↓ exec python                           │
│                                          │
│ Python Scripts (actual logic)           │
│  visual-validate-geometry.py, etc.      │
└─────────────────────────────────────────┘
```

## Why 3 Layers?

1. **Babashka orchestrators** - Handle workflow logic, file watching, prompts
2. **Bash wrappers** - Activate Python venv automatically
3. **Python scripts** - OpenCV/ML validation logic

This separation allows:
- Call Python scripts directly (if venv already active)
- Use bash wrappers for convenience (auto-activates venv)
- Babashka orchestrators for complex workflows

## Common Workflows

### 1. Geometry-First Iteration (Fastest)
```bash
# Start dev server
npm run dev

# Watch and validate geometry on changes
scripts/visual-iterate ref.png http://localhost:8080 --geometry-only

# Process:
# 1. Make code changes
# 2. Save screenshot when prompted
# 3. See geometry validation results
# 4. Repeat until PASS
```

### 2. Full Validation Pipeline
```bash
# After geometry passes, run full validation
scripts/visual-validate ref.png latest.png

# Stages run in order:
# 1. Geometry (REQUIRED)
# 2. Structure (REQUIRED)
# 3. AI Feedback (OPTIONAL)
```

### 3. Get Specific Fixes
```bash
# Instead of vague "looks different", get:
# "Scene 140% too bright → reduce dir-light1 intensity by -0.40"
scripts/visual-compare-actionable ref.png impl.png
```

### 4. ML-Enhanced Validation
```bash
# Add depth validation
scripts/visual-validate ref.png impl.png --validate-depth

# Add grid detection
scripts/visual-validate ref.png impl.png --validate-grid

# Add material comparison
scripts/visual-validate ref.png impl.png --validate-materials

# Run all ML validators
scripts/visual-validate ref.png impl.png --validate-all-ml
```

## Exit Codes

All validators use standard exit codes:
- `0` - PASS (validation successful)
- `1` - FAIL (validation failed, shows specific issues)

This allows chaining:
```bash
scripts/visual-validate-geometry ref.png impl.png && \
scripts/visual-validate-structure ref.png impl.png && \
echo "All validations passed!"
```

## Direct Python Usage

If you have the venv activated:
```bash
source .venv/bin/activate

# Call Python scripts directly
python scripts/visual-validate-geometry.py ref.png impl.png
python scripts/visual-validate-depth.py ref.png impl.png
```

## Thresholds

### Geometry (Strict - can't be polished)
```
--circularity-threshold 0.1    # Nearly perfect circle
--verticality-threshold 5.0    # Within 5° vertical
--aspect-ratio-tolerance 0.05  # ±5% of expected
```

### Structure (Flexible - can iterate)
```
--ssim-threshold 0.80         # 80% structural match
--rmse-threshold 15.0         # 15% pixel difference
--histogram-threshold 0.85    # 85% distribution match
```

## Dependencies

### Basic Validation (required)
```bash
pip install opencv-python scikit-image numpy scipy matplotlib
```

### ML Validation (optional, for --validate-* flags)
```bash
pip install torch torchvision transformers sam2 timm scikit-learn
# ~2-3GB download (PyTorch + models)
```

See `requirements.txt` for full list.

## When to Use What

| Task | Tool | Speed | Accuracy |
|------|------|-------|----------|
| Fast iteration | `visual-iterate --geometry-only` | ⚡️ Fastest | Good |
| Full validation | `visual-validate` | 🏃 Fast | Best |
| Specific fixes | `visual-compare-actionable` | 🏃 Fast | Best |
| Depth matching | `visual-validate --validate-depth` | 🐢 Slow | Best |
| Grid detection | `visual-validate --validate-grid` | 🐢 Slow | Best |

## Troubleshooting

### "opencv not found"
```bash
pip install opencv-python
```

### "Could not find main object"
Image needs clear foreground/background. Try:
- Increase contrast
- Use solid background
- Crop to main subject

### "No module named 'torch'"
```bash
# Only needed for ML validators (--validate-depth, etc.)
pip install torch torchvision transformers
```

### Bash wrapper not finding venv
```bash
# Create venv if missing
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## See Also

- `README-VISUAL-VALIDATION.md` - Full documentation
- `docs/VISUAL_VALIDATION.md` - Architecture and design
- `.agentlog/core-problem-and-research-needs.md` - Research findings
