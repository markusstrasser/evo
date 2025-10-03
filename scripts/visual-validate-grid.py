#!/usr/bin/env python3
"""
Grid validation using SAM 2 (Segment Anything Model 2).
Detects and validates grid structure, pin count, and spacing.
"""

import sys
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
import cv2
import torch

def segment_grid(image_path):
    """Segment the pin array grid using SAM 2."""
    try:
        from sam2.build_sam import build_sam2
        from sam2.sam2_image_predictor import SAM2ImagePredictor

        # Load image
        image = cv2.imread(str(image_path))
        image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

        # Initialize SAM 2
        checkpoint = "checkpoints/sam2_hiera_small.pt"
        model_cfg = "sam2_hiera_s.yaml"

        # Try to build model (may not have checkpoint)
        try:
            sam2 = build_sam2(model_cfg, checkpoint)
            predictor = SAM2ImagePredictor(sam2)
        except:
            # Fallback: use basic grid detection without SAM
            print("Warning: SAM 2 checkpoint not found, using basic grid detection")
            return None, image_rgb

        predictor.set_image(image_rgb)

        # Auto-segment using grid points
        height, width = image_rgb.shape[:2]
        center_x, center_y = width // 2, height // 2

        # Sample points in a grid pattern
        input_points = np.array([
            [center_x, center_y],
            [center_x - width//4, center_y],
            [center_x + width//4, center_y],
            [center_x, center_y - height//4],
            [center_x, center_y + height//4],
        ])
        input_labels = np.array([1, 1, 1, 1, 1])  # All foreground points

        masks, _, _ = predictor.predict(
            point_coords=input_points,
            point_labels=input_labels,
            multimask_output=True
        )

        # Return the largest mask
        mask = masks[np.argmax([m.sum() for m in masks])]
        return mask, image_rgb

    except Exception as e:
        print(f"SAM 2 segmentation failed: {e}")
        print("Falling back to basic grid detection")
        image = cv2.imread(str(image_path))
        image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        return None, image_rgb

def detect_grid_basic(image):
    """Detect grid structure using basic computer vision."""
    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_RGB2GRAY)

    # Threshold to find bright pins
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # Find contours (each pin)
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    # Filter small contours (noise)
    min_area = 10
    pin_contours = [c for c in contours if cv2.contourArea(c) > min_area]

    # Get pin centers
    centers = []
    for contour in pin_contours:
        M = cv2.moments(contour)
        if M["m00"] != 0:
            cx = int(M["m10"] / M["m00"])
            cy = int(M["m01"] / M["m00"])
            centers.append((cx, cy))

    return np.array(centers), pin_contours

def analyze_grid_structure(centers):
    """Analyze grid structure from pin centers."""
    if len(centers) < 4:
        return None

    # Compute pairwise distances
    from scipy.spatial.distance import pdist, squareform
    distances = pdist(centers)

    # Find most common distance (grid spacing)
    hist, bins = np.histogram(distances, bins=50)
    spacing = bins[np.argmax(hist)]

    # Estimate grid angle using nearest neighbors
    from sklearn.neighbors import NearestNeighbors
    nbrs = NearestNeighbors(n_neighbors=5).fit(centers)
    distances_nn, indices_nn = nbrs.kneighbors(centers)

    # Calculate angles from each point to its neighbors
    angles = []
    for i, (point, neighbors) in enumerate(zip(centers, indices_nn)):
        for neighbor_idx in neighbors[1:]:  # Skip self
            neighbor = centers[neighbor_idx]
            dx = neighbor[0] - point[0]
            dy = neighbor[1] - point[1]
            angle = np.arctan2(dy, dx) * 180 / np.pi
            angles.append(angle)

    # Find dominant angles (should be 0°, 90°, 180°, 270° for a grid)
    angles = np.array(angles) % 180  # Normalize to 0-180
    hist_angles, bins_angles = np.histogram(angles, bins=36)
    dominant_angle = bins_angles[np.argmax(hist_angles)]

    # Calculate grid regularity (coefficient of variation of distances)
    regularity = 1.0 - (np.std(distances_nn[:, 1:]) / np.mean(distances_nn[:, 1:]))

    return {
        'pin_count': len(centers),
        'spacing': spacing,
        'angle': dominant_angle,
        'regularity': regularity
    }

def compare_grids(impl_metrics, ref_metrics):
    """Compare grid metrics between implementation and reference."""
    if impl_metrics is None or ref_metrics is None:
        return None

    pin_count_diff = abs(impl_metrics['pin_count'] - ref_metrics['pin_count'])
    pin_count_ratio = pin_count_diff / ref_metrics['pin_count']

    spacing_diff = abs(impl_metrics['spacing'] - ref_metrics['spacing'])
    spacing_ratio = spacing_diff / ref_metrics['spacing']

    angle_diff = abs(impl_metrics['angle'] - ref_metrics['angle'])

    regularity_diff = abs(impl_metrics['regularity'] - ref_metrics['regularity'])

    return {
        'pin_count_ratio': pin_count_ratio,
        'spacing_ratio': spacing_ratio,
        'angle_diff': angle_diff,
        'regularity_diff': regularity_diff,
        'impl_metrics': impl_metrics,
        'ref_metrics': ref_metrics
    }

def save_grid_visualizations(impl_image, ref_image, impl_centers, ref_centers,
                            impl_contours, ref_contours, output_dir):
    """Save grid detection visualizations."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Visualize implementation grid
    impl_vis = impl_image.copy()
    for center in impl_centers:
        cv2.circle(impl_vis, tuple(center.astype(int)), 3, (255, 0, 0), -1)
    cv2.drawContours(impl_vis, impl_contours, -1, (0, 255, 0), 1)
    cv2.imwrite(str(output_dir / 'impl_grid.png'), cv2.cvtColor(impl_vis, cv2.COLOR_RGB2BGR))

    # Visualize reference grid
    ref_vis = ref_image.copy()
    for center in ref_centers:
        cv2.circle(ref_vis, tuple(center.astype(int)), 3, (255, 0, 0), -1)
    cv2.drawContours(ref_vis, ref_contours, -1, (0, 255, 0), 1)
    cv2.imwrite(str(output_dir / 'ref_grid.png'), cv2.cvtColor(ref_vis, cv2.COLOR_RGB2BGR))

    # Create comparison
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(12, 6))

    axes[0].imshow(impl_vis)
    axes[0].set_title(f'Implementation ({len(impl_centers)} pins)')
    axes[0].axis('off')

    axes[1].imshow(ref_vis)
    axes[1].set_title(f'Reference ({len(ref_centers)} pins)')
    axes[1].axis('off')

    plt.tight_layout()
    plt.savefig(output_dir / 'grid_comparison.png', dpi=150, bbox_inches='tight')
    plt.close()

def validate_grid(impl_path, ref_path, output_dir, thresholds):
    """Validate grid structure between implementation and reference."""
    print("Detecting grid in implementation...")
    impl_mask, impl_image = segment_grid(impl_path)
    impl_centers, impl_contours = detect_grid_basic(impl_image)
    impl_metrics = analyze_grid_structure(impl_centers)

    print("Detecting grid in reference...")
    ref_mask, ref_image = segment_grid(ref_path)
    ref_centers, ref_contours = detect_grid_basic(ref_image)
    ref_metrics = analyze_grid_structure(ref_centers)

    if impl_metrics is None or ref_metrics is None:
        print("Error: Could not detect grid structure")
        return False

    # Compare grids
    comparison = compare_grids(impl_metrics, ref_metrics)

    # Save visualizations
    save_grid_visualizations(impl_image, ref_image, impl_centers, ref_centers,
                            impl_contours, ref_contours, output_dir)

    # Check thresholds
    failures = []

    if comparison['pin_count_ratio'] > thresholds['pin_count_ratio']:
        failures.append(('Pin Count', comparison['impl_metrics']['pin_count'],
                        comparison['ref_metrics']['pin_count'],
                        f"Pin count differs by {comparison['pin_count_ratio']:.1%}"))

    if comparison['spacing_ratio'] > thresholds['spacing_ratio']:
        failures.append(('Spacing', comparison['impl_metrics']['spacing'],
                        comparison['ref_metrics']['spacing'],
                        f"Grid spacing differs by {comparison['spacing_ratio']:.1%}"))

    if comparison['angle_diff'] > thresholds['angle_diff']:
        failures.append(('Angle', comparison['impl_metrics']['angle'],
                        comparison['ref_metrics']['angle'],
                        f"Grid angle differs by {comparison['angle_diff']:.1f}°"))

    if comparison['regularity_diff'] > thresholds['regularity_diff']:
        failures.append(('Regularity', comparison['impl_metrics']['regularity'],
                        comparison['ref_metrics']['regularity'],
                        'Grid regularity differs'))

    # Print results
    print(f"\n{'=' * 50}")
    print("GRID VALIDATION RESULTS")
    print(f"{'=' * 50}\n")

    print(f"Pin Count:   {impl_metrics['pin_count']} vs {ref_metrics['pin_count']}")
    print(f"Spacing:     {impl_metrics['spacing']:.2f} vs {ref_metrics['spacing']:.2f}")
    print(f"Angle:       {impl_metrics['angle']:.2f}° vs {ref_metrics['angle']:.2f}°")
    print(f"Regularity:  {impl_metrics['regularity']:.4f} vs {ref_metrics['regularity']:.4f}")

    if failures:
        print(f"\n✗ GRID VALIDATION FAILED\n")
        print("Fix these issues:\n")
        for i, (metric, impl_val, ref_val, suggestion) in enumerate(failures, 1):
            print(f"{i}. {metric}")
            print(f"   Implementation: {impl_val}")
            print(f"   Reference: {ref_val}")
            print(f"   → {suggestion}\n")
        return False
    else:
        print(f"\n✓ GRID VALIDATION PASSED\n")
        return True

def main():
    parser = argparse.ArgumentParser(description='Validate grid structure using SAM 2')
    parser.add_argument('implementation', help='Path to implementation image')
    parser.add_argument('reference', help='Path to reference image')
    parser.add_argument('--output-dir', default='/tmp/grid-validation',
                       help='Output directory for visualizations')
    parser.add_argument('--pin-count-threshold', type=float, default=0.10,
                       help='Pin count difference threshold (default: 10%%)')
    parser.add_argument('--spacing-threshold', type=float, default=0.15,
                       help='Spacing difference threshold (default: 15%%)')
    parser.add_argument('--angle-threshold', type=float, default=10.0,
                       help='Angle difference threshold in degrees (default: 10°)')
    parser.add_argument('--regularity-threshold', type=float, default=0.15,
                       help='Regularity difference threshold (default: 0.15)')

    args = parser.parse_args()

    thresholds = {
        'pin_count_ratio': args.pin_count_threshold,
        'spacing_ratio': args.spacing_threshold,
        'angle_diff': args.angle_threshold,
        'regularity_diff': args.regularity_threshold
    }

    try:
        success = validate_grid(args.implementation, args.reference,
                               args.output_dir, thresholds)
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"\nError during grid validation: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(2)

if __name__ == '__main__':
    main()
