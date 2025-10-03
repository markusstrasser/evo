"""
Structural Validation - Quantitative similarity metrics

Metrics:
- SSIM (Structural Similarity Index) - perceptual similarity
- MSE/RMSE - pixel-level difference
- Histogram correlation - lighting/color consistency

Usage:
  visual-validate-structure <reference> <implementation> [options]

Options:
  --ssim-threshold <n>      Min SSIM score 0-1 (default: 0.80)
  --rmse-threshold <n>      Max RMSE percentage (default: 15.0)
  --histogram-threshold <n> Min histogram correlation 0-1 (default: 0.85)
  --output <dir>            Save analysis results (default: /tmp/structure-validation/)
  --normalize               Resize both to same dimensions before comparing
  -v, --verbose             Show detailed metrics
  -h, --help                Show this help

Output:
  Exit code 0 = PASSED all checks
  Exit code 1 = FAILED structural validation (prints specific metrics)
"""

import cv2
import numpy as np
import sys
import os
from pathlib import Path
import argparse
from skimage.metrics import structural_similarity as ssim


def parse_args():
    parser = argparse.ArgumentParser(
        description='Structural validation using quantitative metrics',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('reference', help='Reference image path')
    parser.add_argument('implementation', help='Implementation image path')
    parser.add_argument('--ssim-threshold', type=float, default=0.80,
                        help='Min SSIM score (default: 0.80)')
    parser.add_argument('--rmse-threshold', type=float, default=15.0,
                        help='Max RMSE percentage (default: 15.0)')
    parser.add_argument('--histogram-threshold', type=float, default=0.85,
                        help='Min histogram correlation (default: 0.85)')
    parser.add_argument('--output', default='/tmp/structure-validation/',
                        help='Output directory for analysis results')
    parser.add_argument('--normalize', action='store_true',
                        help='Resize both to same dimensions')
    parser.add_argument('-v', '--verbose', action='store_true',
                        help='Show detailed metrics')
    return parser.parse_args()


def load_image(path):
    """Load image in color and grayscale."""
    img = cv2.imread(path)
    if img is None:
        print(f"✗ Error: Cannot read image: {path}")
        sys.exit(2)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return img, gray


def normalize_images(img1, img2):
    """Resize images to same dimensions (smallest common size)."""
    h1, w1 = img1.shape[:2]
    h2, w2 = img2.shape[:2]

    # Use smallest dimensions
    target_h = min(h1, h2)
    target_w = min(w1, w2)

    img1_resized = cv2.resize(img1, (target_w, target_h))
    img2_resized = cv2.resize(img2, (target_w, target_h))

    return img1_resized, img2_resized


def compute_ssim(img1_gray, img2_gray):
    """
    Compute Structural Similarity Index (SSIM).

    Range: -1 to 1 (1 = identical)
    Measures: luminance, contrast, structure

    Returns: (ssim_score, ssim_image)
    """
    score, diff = ssim(img1_gray, img2_gray, full=True)
    diff = (diff * 255).astype("uint8")
    return score, diff


def compute_rmse(img1_gray, img2_gray):
    """
    Compute Root Mean Square Error.

    Returns: (rmse_value, rmse_percentage)
    """
    mse = np.mean((img1_gray.astype(float) - img2_gray.astype(float)) ** 2)
    rmse = np.sqrt(mse)

    # Convert to percentage (0-100)
    # Max possible difference is 255 (white vs black)
    rmse_pct = (rmse / 255.0) * 100.0

    return rmse, rmse_pct


def compute_histogram_correlation(img1, img2):
    """
    Compute histogram correlation for each channel.

    Returns: (mean_correlation, [r_corr, g_corr, b_corr])
    """
    correlations = []

    for i in range(3):  # B, G, R channels
        hist1 = cv2.calcHist([img1], [i], None, [256], [0, 256])
        hist2 = cv2.calcHist([img2], [i], None, [256], [0, 256])

        # Normalize histograms
        hist1 = cv2.normalize(hist1, hist1).flatten()
        hist2 = cv2.normalize(hist2, hist2).flatten()

        # Compute correlation
        corr = cv2.compareHist(hist1, hist2, cv2.HISTCMP_CORREL)
        correlations.append(corr)

    mean_corr = np.mean(correlations)
    return mean_corr, correlations


def save_diff_visualization(ref_gray, impl_gray, ssim_diff, output_dir):
    """Save SSIM difference heatmap."""
    Path(output_dir).mkdir(parents=True, exist_ok=True)

    # Create heatmap of differences
    heatmap = cv2.applyColorMap(255 - ssim_diff, cv2.COLORMAP_JET)

    # Side-by-side: reference, implementation, diff heatmap
    ref_color = cv2.cvtColor(ref_gray, cv2.COLOR_GRAY2BGR)
    impl_color = cv2.cvtColor(impl_gray, cv2.COLOR_GRAY2BGR)

    combined = np.hstack([ref_color, impl_color, heatmap])

    output_path = os.path.join(output_dir, 'ssim_diff.png')
    cv2.imwrite(output_path, combined)

    return output_path


def main():
    args = parse_args()

    print("╔═══════════════════════════════════════════════════════╗")
    print("║      Structural Validation - Quantitative Metrics    ║")
    print("╚═══════════════════════════════════════════════════════╝")
    print()
    print(f"Reference:      {args.reference}")
    print(f"Implementation: {args.implementation}")
    print()

    # Load images
    ref_img, ref_gray = load_image(args.reference)
    impl_img, impl_gray = load_image(args.implementation)

    # Normalize if requested
    if args.normalize:
        print("Normalizing images to same dimensions...")
        ref_gray, impl_gray = normalize_images(ref_gray, impl_gray)
        ref_img, impl_img = normalize_images(ref_img, impl_img)
        print(f"  Resized to: {ref_gray.shape[1]}x{ref_gray.shape[0]}")
        print()

    # Check dimensions match
    if ref_gray.shape != impl_gray.shape:
        print("✗ Error: Image dimensions don't match")
        print(f"  Reference: {ref_gray.shape[1]}x{ref_gray.shape[0]}")
        print(f"  Implementation: {impl_gray.shape[1]}x{impl_gray.shape[0]}")
        print()
        print("Use --normalize to auto-resize, or match dimensions manually")
        sys.exit(2)

    failures = []

    # 1. SSIM
    print("[1/3] Computing SSIM (Structural Similarity)...")
    ssim_score, ssim_diff = compute_ssim(ref_gray, impl_gray)

    if args.verbose:
        print(f"      SSIM score: {ssim_score:.4f}")
        print(f"      Threshold: {args.ssim_threshold}")

    if ssim_score < args.ssim_threshold:
        failures.append({
            'metric': 'SSIM',
            'value': ssim_score,
            'threshold': args.ssim_threshold,
            'interpretation': 'Structural layout differs significantly'
        })
    else:
        print(f"      ✓ PASSED ({ssim_score:.2%})")

    # 2. RMSE
    print("[2/3] Computing RMSE (pixel difference)...")
    rmse_val, rmse_pct = compute_rmse(ref_gray, impl_gray)

    if args.verbose:
        print(f"      RMSE: {rmse_val:.2f}")
        print(f"      Percentage: {rmse_pct:.2f}%")
        print(f"      Threshold: {args.rmse_threshold}%")

    if rmse_pct > args.rmse_threshold:
        failures.append({
            'metric': 'RMSE',
            'value': rmse_pct,
            'threshold': args.rmse_threshold,
            'interpretation': 'Pixel values differ too much (brightness/contrast)'
        })
    else:
        print(f"      ✓ PASSED ({rmse_pct:.2f}% difference)")

    # 3. Histogram Correlation
    print("[3/3] Computing histogram correlation...")
    hist_corr, channel_corrs = compute_histogram_correlation(ref_img, impl_img)

    if args.verbose:
        print(f"      Mean correlation: {hist_corr:.4f}")
        print(f"      By channel (B,G,R): {[f'{c:.3f}' for c in channel_corrs]}")
        print(f"      Threshold: {args.histogram_threshold}")

    if hist_corr < args.histogram_threshold:
        failures.append({
            'metric': 'Histogram Correlation',
            'value': hist_corr,
            'threshold': args.histogram_threshold,
            'interpretation': 'Color/lighting distribution differs'
        })
    else:
        print(f"      ✓ PASSED ({hist_corr:.2%})")

    # Save visualizations
    if args.output:
        diff_path = save_diff_visualization(ref_gray, impl_gray, ssim_diff, args.output)
        if args.verbose:
            print()
            print(f"SSIM diff visualization: {diff_path}")

    # Save metrics to file
    if args.output:
        Path(args.output).mkdir(parents=True, exist_ok=True)
        metrics_path = os.path.join(args.output, 'metrics.txt')
        with open(metrics_path, 'w') as f:
            f.write(f"SSIM: {ssim_score:.4f} (threshold: {args.ssim_threshold})\n")
            f.write(f"RMSE: {rmse_pct:.2f}% (threshold: {args.rmse_threshold}%)\n")
            f.write(f"Histogram Correlation: {hist_corr:.4f} (threshold: {args.histogram_threshold})\n")
            f.write(f"\nPassed: {'YES' if not failures else 'NO'}\n")

    # Report results
    print()
    print("═══════════════════════════════════════════════════════")

    if not failures:
        print("✓ STRUCTURAL VALIDATION PASSED")
        print()
        print(f"  SSIM:                 {ssim_score:.2%}")
        print(f"  RMSE:                 {rmse_pct:.2f}%")
        print(f"  Histogram Correlation: {hist_corr:.2%}")
        print()
        print("Structural similarity is good. Optionally refine aesthetics with AI.")
        sys.exit(0)
    else:
        print("✗ STRUCTURAL VALIDATION FAILED")
        print()
        print("Metrics below threshold:")
        print()
        for i, failure in enumerate(failures, 1):
            print(f"{i}. {failure['metric']}")
            print(f"   Score: {failure['value']:.4f} (threshold: {failure['threshold']:.4f})")
            print(f"   → {failure['interpretation']}")
            print()

        print("Fix structural issues before aesthetic refinement.")
        sys.exit(1)


if __name__ == '__main__':
    main()
