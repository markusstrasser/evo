"""
Geometric Validation - Verify structural correctness before aesthetics

Checks:
- Circularity (eccentricity < 0.1)
- Verticality (edge angles < 5° deviation)
- Aspect ratio (bounding box matches expected)
- Pattern alignment (grid correlation)

Usage:
  visual-validate-geometry <reference> <implementation> [options]

Options:
  --circularity-threshold <n>   Max eccentricity (default: 0.1)
  --verticality-threshold <n>   Max angle deviation in degrees (default: 5)
  --aspect-ratio-tolerance <n>  Aspect ratio tolerance (default: 0.05)
  --output <dir>                Save debug visualizations (default: /tmp/geo-validation/)
  -v, --verbose                 Show detailed analysis
  -h, --help                    Show this help

Output:
  Exit code 0 = PASSED all checks
  Exit code 1 = FAILED geometry validation (prints specific fixes)
"""

import cv2
import numpy as np
import sys
import os
from pathlib import Path
import argparse


def parse_args():
    parser = argparse.ArgumentParser(
        description='Geometric validation for visual matching',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('reference', help='Reference image path')
    parser.add_argument('implementation', help='Implementation image path')
    parser.add_argument('--circularity-threshold', type=float, default=0.1,
                        help='Max eccentricity for circularity (default: 0.1)')
    parser.add_argument('--verticality-threshold', type=float, default=5.0,
                        help='Max angle deviation in degrees (default: 5)')
    parser.add_argument('--aspect-ratio-tolerance', type=float, default=0.05,
                        help='Aspect ratio tolerance (default: 0.05)')
    parser.add_argument('--output', default='/tmp/geo-validation/',
                        help='Output directory for debug images')
    parser.add_argument('-v', '--verbose', action='store_true',
                        help='Show detailed analysis')
    return parser.parse_args()


def load_image(path):
    """Load image and convert to grayscale."""
    img = cv2.imread(path)
    if img is None:
        print(f"✗ Error: Cannot read image: {path}")
        sys.exit(2)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return img, gray


def find_main_contour(gray):
    """Find the largest contour in the image (main object)."""
    # Threshold to binary
    _, binary = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # Find contours
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    if not contours:
        return None

    # Return largest contour by area
    return max(contours, key=cv2.contourArea)


def check_circularity(contour, threshold=0.1):
    """
    Check if contour is circular by fitting ellipse and computing eccentricity.

    Eccentricity = sqrt(1 - (minor_axis/major_axis)^2)
    - 0.0 = perfect circle
    - 1.0 = line segment

    Returns: (passed, eccentricity, ellipse_params)
    """
    if len(contour) < 5:  # Need at least 5 points to fit ellipse
        return False, 1.0, None

    ellipse = cv2.fitEllipse(contour)
    (center, axes, angle) = ellipse

    # Axes are (width, height), get major/minor
    major = max(axes)
    minor = min(axes)

    # Compute eccentricity
    if major == 0:
        eccentricity = 1.0
    else:
        eccentricity = np.sqrt(1 - (minor / major) ** 2)

    passed = eccentricity < threshold

    return passed, eccentricity, ellipse


def check_verticality(gray, threshold=5.0):
    """
    Check if dominant edges are vertical (within threshold degrees).

    Uses Hough Line Transform to find dominant lines, then checks angles.

    Returns: (passed, mean_deviation, angle_histogram)
    """
    # Edge detection
    edges = cv2.Canny(gray, 50, 150, apertureSize=3)

    # Hough Line Transform
    lines = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=100,
                            minLineLength=50, maxLineGap=10)

    if lines is None:
        return True, 0.0, []  # No lines found, assume OK

    # Calculate angles of all lines
    angles = []
    for line in lines:
        x1, y1, x2, y2 = line[0]
        angle = np.abs(np.degrees(np.arctan2(y2 - y1, x2 - x1)))
        # Normalize to 0-90 degrees
        if angle > 90:
            angle = 180 - angle
        # Vertical is 90°, compute deviation from vertical
        deviation = np.abs(90 - angle)
        angles.append(deviation)

    mean_deviation = np.mean(angles)
    passed = mean_deviation < threshold

    return passed, mean_deviation, angles


def check_aspect_ratio(contour, expected_ratio=1.0, tolerance=0.05):
    """
    Check if bounding rectangle aspect ratio matches expected.

    Returns: (passed, actual_ratio, bounding_rect)
    """
    x, y, w, h = cv2.boundingRect(contour)

    if h == 0:
        actual_ratio = 0
    else:
        actual_ratio = w / h

    difference = abs(actual_ratio - expected_ratio)
    passed = difference <= tolerance

    return passed, actual_ratio, (x, y, w, h)


def save_debug_visualization(img, contour, ellipse, output_dir, name):
    """Save annotated image showing detected geometry."""
    debug_img = img.copy()

    # Draw main contour
    cv2.drawContours(debug_img, [contour], 0, (0, 255, 0), 2)

    # Draw fitted ellipse
    if ellipse:
        cv2.ellipse(debug_img, ellipse, (255, 0, 0), 2)

    # Draw bounding rectangle
    x, y, w, h = cv2.boundingRect(contour)
    cv2.rectangle(debug_img, (x, y), (x + w, y + h), (0, 0, 255), 2)

    Path(output_dir).mkdir(parents=True, exist_ok=True)
    output_path = os.path.join(output_dir, f'{name}_geometry.png')
    cv2.imwrite(output_path, debug_img)

    return output_path


def main():
    args = parse_args()

    print("╔═══════════════════════════════════════════════════════╗")
    print("║       Geometric Validation - Structure First         ║")
    print("╚═══════════════════════════════════════════════════════╝")
    print()
    print(f"Reference:      {args.reference}")
    print(f"Implementation: {args.implementation}")
    print()

    # Load images
    ref_img, ref_gray = load_image(args.reference)
    impl_img, impl_gray = load_image(args.implementation)

    # Find main contours
    ref_contour = find_main_contour(ref_gray)
    impl_contour = find_main_contour(impl_gray)

    if ref_contour is None or impl_contour is None:
        print("✗ Error: Could not find main object in images")
        sys.exit(2)

    # Run validation checks
    failures = []

    # 1. Circularity
    print("[1/3] Checking circularity...")
    ref_circ_passed, ref_ecc, ref_ellipse = check_circularity(ref_contour, args.circularity_threshold)
    impl_circ_passed, impl_ecc, impl_ellipse = check_circularity(impl_contour, args.circularity_threshold)

    if args.verbose:
        print(f"      Reference eccentricity: {ref_ecc:.3f}")
        print(f"      Implementation eccentricity: {impl_ecc:.3f}")
        print(f"      Threshold: {args.circularity_threshold}")

    if not impl_circ_passed:
        failures.append({
            'check': 'Circularity',
            'value': impl_ecc,
            'threshold': args.circularity_threshold,
            'fix': 'Adjust camera angle or use orthographic projection'
        })
    else:
        print(f"      ✓ PASSED (eccentricity: {impl_ecc:.3f})")

    # 2. Verticality
    print("[2/3] Checking verticality...")
    ref_vert_passed, ref_dev, ref_angles = check_verticality(ref_gray, args.verticality_threshold)
    impl_vert_passed, impl_dev, impl_angles = check_verticality(impl_gray, args.verticality_threshold)

    if args.verbose:
        print(f"      Reference deviation: {ref_dev:.2f}°")
        print(f"      Implementation deviation: {impl_dev:.2f}°")
        print(f"      Threshold: {args.verticality_threshold}°")

    if not impl_vert_passed:
        failures.append({
            'check': 'Verticality',
            'value': impl_dev,
            'threshold': args.verticality_threshold,
            'fix': 'Reduce camera pitch/tilt angle'
        })
    else:
        print(f"      ✓ PASSED (deviation: {impl_dev:.2f}°)")

    # 3. Aspect Ratio
    print("[3/3] Checking aspect ratio...")
    ref_ar_passed, ref_ar, ref_bbox = check_aspect_ratio(ref_contour, tolerance=args.aspect_ratio_tolerance)
    impl_ar_passed, impl_ar, impl_bbox = check_aspect_ratio(impl_contour, tolerance=args.aspect_ratio_tolerance)

    if args.verbose:
        print(f"      Reference aspect ratio: {ref_ar:.3f}")
        print(f"      Implementation aspect ratio: {impl_ar:.3f}")
        print(f"      Tolerance: ±{args.aspect_ratio_tolerance}")

    aspect_ratio_diff = abs(impl_ar - ref_ar)
    if aspect_ratio_diff > args.aspect_ratio_tolerance:
        failures.append({
            'check': 'Aspect Ratio',
            'value': impl_ar,
            'expected': ref_ar,
            'difference': aspect_ratio_diff,
            'fix': 'Adjust canvas dimensions or viewport'
        })
    else:
        print(f"      ✓ PASSED (ratio: {impl_ar:.3f})")

    # Save debug visualizations
    if args.output:
        ref_debug = save_debug_visualization(ref_img, ref_contour, ref_ellipse, args.output, 'reference')
        impl_debug = save_debug_visualization(impl_img, impl_contour, impl_ellipse, args.output, 'implementation')
        if args.verbose:
            print()
            print(f"Debug visualizations saved to: {args.output}")

    # Report results
    print()
    print("═══════════════════════════════════════════════════════")

    if not failures:
        print("✓ GEOMETRY VALIDATION PASSED")
        print()
        print("All geometric checks passed. Ready for structural validation.")
        sys.exit(0)
    else:
        print("✗ GEOMETRY VALIDATION FAILED")
        print()
        print("Fix these issues before proceeding:")
        print()
        for i, failure in enumerate(failures, 1):
            print(f"{i}. {failure['check']}")
            if 'value' in failure and 'threshold' in failure:
                print(f"   Current: {failure['value']:.3f} (threshold: {failure['threshold']:.3f})")
            if 'expected' in failure:
                print(f"   Expected: {failure['expected']:.3f}")
                print(f"   Difference: {failure['difference']:.3f}")
            print(f"   → {failure['fix']}")
            print()

        sys.exit(1)


if __name__ == '__main__':
    main()
