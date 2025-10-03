#!/usr/bin/env python3
"""
Depth validation using Depth Anything V2.
Extracts and compares depth/height maps between implementation and reference.
"""

import sys
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
import torch
from transformers import pipeline

def extract_depth(image_path):
    """Extract depth map using Depth Anything V2 via torch.hub."""
    # Load image
    image = Image.open(image_path).convert('RGB')

    # Try loading via torch.hub (Depth Anything V2)
    try:
        # Load model from torch hub
        model = torch.hub.load('DepthAnything/Depth-Anything-V2', 'depth_anything_v2_vits', pretrained=True)
        if torch.cuda.is_available():
            model = model.cuda()
        model.eval()

        # Prepare image
        from torchvision import transforms
        transform = transforms.Compose([
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])
        ])

        img_tensor = transform(image).unsqueeze(0)
        if torch.cuda.is_available():
            img_tensor = img_tensor.cuda()

        # Get depth
        with torch.no_grad():
            depth_map = model(img_tensor)
            depth_map = depth_map.squeeze().cpu().numpy()

        return depth_map, image

    except Exception as e:
        # Fallback to simpler depth estimation using DPT
        print(f"Depth Anything V2 not available ({e}), trying DPT...")
        try:
            from transformers import DPTImageProcessor, DPTForDepthEstimation

            processor = DPTImageProcessor.from_pretrained("Intel/dpt-large")
            model = DPTForDepthEstimation.from_pretrained("Intel/dpt-large")

            if torch.cuda.is_available():
                model = model.cuda()
            model.eval()

            inputs = processor(images=image, return_tensors="pt")
            if torch.cuda.is_available():
                inputs = {k: v.cuda() for k, v in inputs.items()}

            with torch.no_grad():
                outputs = model(**inputs)
                depth_map = outputs.predicted_depth
                depth_map = depth_map.squeeze().cpu().numpy()

            return depth_map, image

        except Exception as e2:
            raise Exception(f"Could not load any depth estimation model: {e}, {e2}")

def normalize_depth(depth_map):
    """Normalize depth map to 0-1 range."""
    min_val = depth_map.min()
    max_val = depth_map.max()
    if max_val - min_val == 0:
        return np.zeros_like(depth_map)
    return (depth_map - min_val) / (max_val - min_val)

def compute_depth_metrics(impl_depth, ref_depth):
    """Compute depth comparison metrics."""
    # Normalize both depth maps
    impl_norm = normalize_depth(impl_depth)
    ref_norm = normalize_depth(ref_depth)

    # Resize to same dimensions if needed
    if impl_norm.shape != ref_norm.shape:
        from scipy.ndimage import zoom
        scale_y = ref_norm.shape[0] / impl_norm.shape[0]
        scale_x = ref_norm.shape[1] / impl_norm.shape[1]
        impl_norm = zoom(impl_norm, (scale_y, scale_x), order=1)

    # Compute metrics
    mae = np.mean(np.abs(impl_norm - ref_norm))  # Mean Absolute Error
    rmse = np.sqrt(np.mean((impl_norm - ref_norm) ** 2))  # Root Mean Square Error

    # Depth correlation
    corr = np.corrcoef(impl_norm.flatten(), ref_norm.flatten())[0, 1]

    return {
        'mae': mae,
        'rmse': rmse,
        'correlation': corr
    }

def save_depth_visualizations(impl_depth, ref_depth, impl_img, ref_img, output_dir):
    """Save depth map visualizations."""
    import matplotlib.pyplot as plt

    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Normalize depth maps
    impl_norm = normalize_depth(impl_depth)
    ref_norm = normalize_depth(ref_depth)

    # Create comparison figure
    fig, axes = plt.subplots(2, 3, figsize=(15, 10))

    # Original images
    axes[0, 0].imshow(impl_img)
    axes[0, 0].set_title('Implementation')
    axes[0, 0].axis('off')

    axes[0, 1].imshow(ref_img)
    axes[0, 1].set_title('Reference')
    axes[0, 1].axis('off')

    # Depth maps
    axes[1, 0].imshow(impl_norm, cmap='plasma')
    axes[1, 0].set_title('Implementation Depth')
    axes[1, 0].axis('off')

    axes[1, 1].imshow(ref_norm, cmap='plasma')
    axes[1, 1].set_title('Reference Depth')
    axes[1, 1].axis('off')

    # Difference map
    if impl_norm.shape == ref_norm.shape:
        diff = np.abs(impl_norm - ref_norm)
        axes[1, 2].imshow(diff, cmap='hot')
        axes[1, 2].set_title('Depth Difference')
        axes[1, 2].axis('off')

    # Remove empty subplot
    axes[0, 2].axis('off')

    plt.tight_layout()
    plt.savefig(output_dir / 'depth_comparison.png', dpi=150, bbox_inches='tight')
    plt.close()

    # Save individual depth maps
    plt.imsave(output_dir / 'impl_depth.png', impl_norm, cmap='plasma')
    plt.imsave(output_dir / 'ref_depth.png', ref_norm, cmap='plasma')

    if impl_norm.shape == ref_norm.shape:
        plt.imsave(output_dir / 'depth_diff.png', diff, cmap='hot')

def validate_depth(impl_path, ref_path, output_dir, thresholds):
    """Validate depth similarity between implementation and reference."""
    print("Extracting depth from implementation...")
    impl_depth, impl_img = extract_depth(impl_path)

    print("Extracting depth from reference...")
    ref_depth, ref_img = extract_depth(ref_path)

    print("Computing depth metrics...")
    metrics = compute_depth_metrics(impl_depth, ref_depth)

    # Save visualizations
    save_depth_visualizations(impl_depth, ref_depth, impl_img, ref_img, output_dir)

    # Check thresholds
    failures = []

    if metrics['mae'] > thresholds['mae']:
        failures.append(('MAE', metrics['mae'], thresholds['mae'],
                        'Height distribution differs significantly'))

    if metrics['rmse'] > thresholds['rmse']:
        failures.append(('RMSE', metrics['rmse'], thresholds['rmse'],
                        'Overall depth structure differs'))

    if metrics['correlation'] < thresholds['correlation']:
        failures.append(('Correlation', metrics['correlation'], thresholds['correlation'],
                        'Depth patterns do not correlate'))

    # Print results
    print(f"\n{'=' * 50}")
    print("DEPTH VALIDATION RESULTS")
    print(f"{'=' * 50}\n")

    print(f"MAE:         {metrics['mae']:.4f} (threshold: {thresholds['mae']:.4f})")
    print(f"RMSE:        {metrics['rmse']:.4f} (threshold: {thresholds['rmse']:.4f})")
    print(f"Correlation: {metrics['correlation']:.4f} (threshold: {thresholds['correlation']:.4f})")

    if failures:
        print(f"\n✗ DEPTH VALIDATION FAILED\n")
        print("Fix these issues:\n")
        for i, (metric, value, threshold, suggestion) in enumerate(failures, 1):
            print(f"{i}. {metric}")
            print(f"   Current: {value:.4f} (threshold: {threshold:.4f})")
            print(f"   → {suggestion}\n")
        return False
    else:
        print(f"\n✓ DEPTH VALIDATION PASSED\n")
        return True

def main():
    parser = argparse.ArgumentParser(description='Validate depth/height using Depth Anything V2')
    parser.add_argument('implementation', help='Path to implementation image')
    parser.add_argument('reference', help='Path to reference image')
    parser.add_argument('--output-dir', default='/tmp/depth-validation',
                       help='Output directory for visualizations')
    parser.add_argument('--mae-threshold', type=float, default=0.15,
                       help='MAE threshold (default: 0.15)')
    parser.add_argument('--rmse-threshold', type=float, default=0.20,
                       help='RMSE threshold (default: 0.20)')
    parser.add_argument('--correlation-threshold', type=float, default=0.70,
                       help='Correlation threshold (default: 0.70)')

    args = parser.parse_args()

    thresholds = {
        'mae': args.mae_threshold,
        'rmse': args.rmse_threshold,
        'correlation': args.correlation_threshold
    }

    try:
        success = validate_depth(args.implementation, args.reference,
                                args.output_dir, thresholds)
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"\nError during depth validation: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(2)

if __name__ == '__main__':
    main()
