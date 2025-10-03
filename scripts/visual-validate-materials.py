#!/usr/bin/env python3
"""
Material/texture validation using DINOv2.
Compares visual features, materials, and texture similarity.
"""

import sys
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
import torch
import torchvision.transforms as transforms

def extract_features(image_path, model):
    """Extract DINOv2 features from image."""
    # Load and preprocess image
    image = Image.open(image_path).convert('RGB')

    # DINOv2 preprocessing
    transform = transforms.Compose([
        transforms.Resize(256),
        transforms.CenterCrop(224),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])

    img_tensor = transform(image).unsqueeze(0)

    # Extract features
    with torch.no_grad():
        if torch.cuda.is_available():
            img_tensor = img_tensor.cuda()
        features = model(img_tensor)

    return features.cpu().numpy(), image

def compute_feature_similarity(impl_features, ref_features):
    """Compute similarity metrics between feature vectors."""
    # Flatten features
    impl_flat = impl_features.flatten()
    ref_flat = ref_features.flatten()

    # Cosine similarity
    dot_product = np.dot(impl_flat, ref_flat)
    norm_impl = np.linalg.norm(impl_flat)
    norm_ref = np.linalg.norm(ref_flat)
    cosine_sim = dot_product / (norm_impl * norm_ref)

    # Euclidean distance (normalized)
    euclidean_dist = np.linalg.norm(impl_flat - ref_flat)
    max_dist = np.sqrt(len(impl_flat) * 2)  # Max possible distance
    euclidean_sim = 1.0 - (euclidean_dist / max_dist)

    # Manhattan distance (normalized)
    manhattan_dist = np.sum(np.abs(impl_flat - ref_flat))
    max_manhattan = len(impl_flat) * 2
    manhattan_sim = 1.0 - (manhattan_dist / max_manhattan)

    return {
        'cosine_similarity': cosine_sim,
        'euclidean_similarity': euclidean_sim,
        'manhattan_similarity': manhattan_sim,
        'mean_similarity': (cosine_sim + euclidean_sim + manhattan_sim) / 3.0
    }

def extract_patch_features(image_path, model, patch_size=224, stride=112):
    """Extract features from image patches for detailed comparison."""
    image = Image.open(image_path).convert('RGB')
    width, height = image.size

    transform = transforms.Compose([
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])

    patches = []
    positions = []

    # Extract patches
    for y in range(0, height - patch_size + 1, stride):
        for x in range(0, width - patch_size + 1, stride):
            patch = image.crop((x, y, x + patch_size, y + patch_size))
            patch_tensor = transform(patch).unsqueeze(0)

            with torch.no_grad():
                if torch.cuda.is_available():
                    patch_tensor = patch_tensor.cuda()
                features = model(patch_tensor)

            patches.append(features.cpu().numpy())
            positions.append((x, y))

    return np.array(patches), positions, image

def compute_spatial_similarity(impl_patches, ref_patches, impl_positions, ref_positions):
    """Compute spatial feature similarity between patches."""
    if len(impl_patches) == 0 or len(ref_patches) == 0:
        return None

    # For each implementation patch, find best matching reference patch
    similarities = []

    for impl_patch in impl_patches:
        impl_flat = impl_patch.flatten()
        best_sim = -1

        for ref_patch in ref_patches:
            ref_flat = ref_patch.flatten()

            # Cosine similarity
            dot_product = np.dot(impl_flat, ref_flat)
            norm_impl = np.linalg.norm(impl_flat)
            norm_ref = np.linalg.norm(ref_flat)

            if norm_impl > 0 and norm_ref > 0:
                sim = dot_product / (norm_impl * norm_ref)
                best_sim = max(best_sim, sim)

        similarities.append(best_sim)

    return {
        'mean_patch_similarity': np.mean(similarities),
        'min_patch_similarity': np.min(similarities),
        'max_patch_similarity': np.max(similarities),
        'std_patch_similarity': np.std(similarities)
    }

def save_feature_visualizations(impl_image, ref_image, output_dir, metrics):
    """Save feature comparison visualizations."""
    import matplotlib.pyplot as plt

    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Create comparison figure
    fig, axes = plt.subplots(1, 2, figsize=(12, 6))

    axes[0].imshow(impl_image)
    axes[0].set_title('Implementation')
    axes[0].axis('off')

    axes[1].imshow(ref_image)
    axes[1].set_title('Reference')
    axes[1].axis('off')

    plt.suptitle(f"Feature Similarity: {metrics['mean_similarity']:.4f}", fontsize=14)
    plt.tight_layout()
    plt.savefig(output_dir / 'material_comparison.png', dpi=150, bbox_inches='tight')
    plt.close()

    # Save metrics to text file
    with open(output_dir / 'metrics.txt', 'w') as f:
        for key, value in metrics.items():
            f.write(f"{key}: {value:.4f}\n")

def validate_materials(impl_path, ref_path, output_dir, thresholds, use_patches=False):
    """Validate material/texture similarity using DINOv2."""
    print("Loading DINOv2 model...")

    # Load DINOv2 model using torch.hub
    try:
        model = torch.hub.load('facebookresearch/dinov2', 'dinov2_vits14')
        if torch.cuda.is_available():
            model = model.cuda()
        model.eval()
    except Exception as e:
        print(f"Error loading DINOv2 model: {e}")
        print("Trying alternative loading method...")
        try:
            from transformers import AutoImageProcessor, Dinov2Model

            processor = AutoImageProcessor.from_pretrained('facebook/dinov2-small')
            model = Dinov2Model.from_pretrained('facebook/dinov2-small')

            if torch.cuda.is_available():
                model = model.cuda()
            model.eval()

            # Redefine feature extraction for transformers model
            def extract_features_transformers(image_path):
                image = Image.open(image_path).convert('RGB')
                inputs = processor(images=image, return_tensors="pt")
                if torch.cuda.is_available():
                    inputs = {k: v.cuda() for k, v in inputs.items()}

                with torch.no_grad():
                    outputs = model(**inputs)
                    features = outputs.last_hidden_state[:, 0, :]  # CLS token

                return features.cpu().numpy(), image

            # Use transformers-based extraction
            global extract_features
            extract_features = extract_features_transformers

        except Exception as e2:
            print(f"Error loading from transformers: {e2}")
            sys.exit(2)

    if use_patches:
        print("Extracting patch features from implementation...")
        impl_patches, impl_positions, impl_image = extract_patch_features(impl_path, model)

        print("Extracting patch features from reference...")
        ref_patches, ref_positions, ref_image = extract_patch_features(ref_path, model)

        print("Computing spatial similarity...")
        spatial_metrics = compute_spatial_similarity(impl_patches, ref_patches,
                                                     impl_positions, ref_positions)

        if spatial_metrics is None:
            print("Error: Could not extract patches")
            return False

        metrics = spatial_metrics
        primary_metric = 'mean_patch_similarity'

    else:
        print("Extracting global features from implementation...")
        impl_features, impl_image = extract_features(impl_path, model)

        print("Extracting global features from reference...")
        ref_features, ref_image = extract_features(ref_path, model)

        print("Computing feature similarity...")
        metrics = compute_feature_similarity(impl_features, ref_features)
        primary_metric = 'mean_similarity'

    # Save visualizations
    save_feature_visualizations(impl_image, ref_image, output_dir, metrics)

    # Check thresholds
    failures = []

    if metrics[primary_metric] < thresholds['similarity']:
        failures.append((primary_metric.replace('_', ' ').title(),
                        metrics[primary_metric],
                        thresholds['similarity'],
                        'Visual features differ significantly'))

    # Print results
    print(f"\n{'=' * 50}")
    print("MATERIAL/TEXTURE VALIDATION RESULTS")
    print(f"{'=' * 50}\n")

    for key, value in metrics.items():
        print(f"{key.replace('_', ' ').title()}: {value:.4f}")

    if failures:
        print(f"\n✗ MATERIAL VALIDATION FAILED\n")
        print("Fix these issues:\n")
        for i, (metric, value, threshold, suggestion) in enumerate(failures, 1):
            print(f"{i}. {metric}")
            print(f"   Current: {value:.4f} (threshold: {threshold:.4f})")
            print(f"   → {suggestion}\n")
        return False
    else:
        print(f"\n✓ MATERIAL VALIDATION PASSED\n")
        return True

def main():
    parser = argparse.ArgumentParser(description='Validate materials/textures using DINOv2')
    parser.add_argument('implementation', help='Path to implementation image')
    parser.add_argument('reference', help='Path to reference image')
    parser.add_argument('--output-dir', default='/tmp/material-validation',
                       help='Output directory for visualizations')
    parser.add_argument('--similarity-threshold', type=float, default=0.75,
                       help='Similarity threshold (default: 0.75)')
    parser.add_argument('--use-patches', action='store_true',
                       help='Use patch-based comparison (more detailed)')

    args = parser.parse_args()

    thresholds = {
        'similarity': args.similarity_threshold
    }

    try:
        success = validate_materials(args.implementation, args.reference,
                                    args.output_dir, thresholds, args.use_patches)
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"\nError during material validation: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(2)

if __name__ == '__main__':
    main()
