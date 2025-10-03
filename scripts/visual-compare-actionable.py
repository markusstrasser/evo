#!/usr/bin/env python3
"""
Actionable Visual Comparison - Show WHAT differs and HOW to fix it.

Instead of abstract metrics, this shows:
- Reference has X rings, you have Y rings → increase wave frequency
- Reference is 40% darker → reduce lighting intensity
- Wave amplitude differs by 50% → adjust height multiplier
"""

import sys
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
import cv2
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

def analyze_wave_pattern(gray):
    """Extract wave pattern characteristics."""
    height, width = gray.shape
    center_y, center_x = height // 2, width // 2
    max_radius = min(center_x, center_y)

    # Radial profile
    radial_profile = []
    for r in range(0, max_radius, 2):
        samples = []
        for angle in np.linspace(0, 2*np.pi, 8, endpoint=False):
            x = int(center_x + r * np.cos(angle))
            y = int(center_y + r * np.sin(angle))
            if 0 <= x < width and 0 <= y < height:
                samples.append(gray[y, x])
        if samples:
            radial_profile.append(np.mean(samples))

    radial_profile = np.array(radial_profile)

    # Detect peaks
    from scipy.signal import find_peaks
    peaks, _ = find_peaks(radial_profile, distance=5, prominence=10)
    valleys, _ = find_peaks(-radial_profile, distance=5, prominence=10)

    # Wave amplitude (peak-to-valley difference)
    if len(peaks) > 0 and len(valleys) > 0:
        amplitude = np.mean([radial_profile[p] for p in peaks]) - np.mean([radial_profile[v] for v in valleys])
    else:
        amplitude = 0

    # Ring spacing
    if len(peaks) > 1:
        spacing = np.mean(np.diff(peaks)) * 2
    else:
        spacing = 0

    return {
        'num_rings': len(peaks),
        'ring_spacing': spacing,
        'amplitude': amplitude,
        'radial_profile': radial_profile
    }

def analyze_lighting(gray):
    """Extract lighting characteristics."""
    mean_brightness = np.mean(gray)
    std_brightness = np.std(gray)

    # Dark/bright pixel ratio
    dark_threshold = np.percentile(gray, 25)
    bright_threshold = np.percentile(gray, 75)
    dark_ratio = np.sum(gray < dark_threshold) / gray.size
    bright_ratio = np.sum(gray > bright_threshold) / gray.size

    return {
        'mean_brightness': mean_brightness,
        'std_brightness': std_brightness,
        'dark_ratio': dark_ratio,
        'bright_ratio': bright_ratio,
        'contrast': std_brightness / (mean_brightness + 1)
    }

def generate_recommendations(ref_wave, impl_wave, ref_light, impl_light):
    """Generate specific code changes to fix differences."""
    recommendations = []

    # Wave frequency
    ring_diff = ref_wave['num_rings'] - impl_wave['num_rings']
    if abs(ring_diff) > 2:
        if ring_diff > 0:
            current_freq = 6.0  # Assumed from code
            suggested_freq = current_freq * (ref_wave['num_rings'] / max(impl_wave['num_rings'], 1))
            recommendations.append({
                'issue': f"Too few rings: {impl_wave['num_rings']} vs {ref_wave['num_rings']} (reference)",
                'fix': f"Increase wave frequency to ~{suggested_freq:.1f}",
                'code': f"(js/Math.sin (* dist js/Math.PI {suggested_freq:.1f}))"
            })
        else:
            current_freq = 6.0
            suggested_freq = current_freq * (ref_wave['num_rings'] / max(impl_wave['num_rings'], 1))
            recommendations.append({
                'issue': f"Too many rings: {impl_wave['num_rings']} vs {ref_wave['num_rings']} (reference)",
                'fix': f"Decrease wave frequency to ~{suggested_freq:.1f}",
                'code': f"(js/Math.sin (* dist js/Math.PI {suggested_freq:.1f}))"
            })

    # Wave amplitude
    amp_diff_pct = ((impl_wave['amplitude'] - ref_wave['amplitude']) / (ref_wave['amplitude'] + 1)) * 100
    if abs(amp_diff_pct) > 20:
        if amp_diff_pct > 0:
            recommendations.append({
                'issue': f"Wave amplitude {amp_diff_pct:.0f}% too high",
                'fix': f"Reduce height multiplier by {abs(amp_diff_pct):.0f}%",
                'code': f"(* {1.0 * (1 - abs(amp_diff_pct)/100):.2f} (js/Math.sin ...))"
            })
        else:
            recommendations.append({
                'issue': f"Wave amplitude {abs(amp_diff_pct):.0f}% too low",
                'fix': f"Increase height multiplier by {abs(amp_diff_pct):.0f}%",
                'code': f"(* {1.0 * (1 + abs(amp_diff_pct)/100):.2f} (js/Math.sin ...))"
            })

    # Brightness
    brightness_diff_pct = ((impl_light['mean_brightness'] - ref_light['mean_brightness']) / (ref_light['mean_brightness'] + 1)) * 100
    if abs(brightness_diff_pct) > 15:
        if brightness_diff_pct > 0:
            recommendations.append({
                'issue': f"Scene {brightness_diff_pct:.0f}% too bright",
                'fix': f"Reduce lighting intensity by {abs(brightness_diff_pct):.0f}%",
                'code': f"dir-light1: intensity *= {1 - abs(brightness_diff_pct)/100:.2f}"
            })
        else:
            recommendations.append({
                'issue': f"Scene {abs(brightness_diff_pct):.0f}% too dark",
                'fix': f"Increase lighting intensity by {abs(brightness_diff_pct):.0f}%",
                'code': f"dir-light1: intensity *= {1 + abs(brightness_diff_pct)/100:.2f}"
            })

    # Contrast
    contrast_diff_pct = ((impl_light['contrast'] - ref_light['contrast']) / (ref_light['contrast'] + 1)) * 100
    if abs(contrast_diff_pct) > 20:
        if contrast_diff_pct > 0:
            recommendations.append({
                'issue': f"Contrast {contrast_diff_pct:.0f}% too high",
                'fix': "Add ambient light or reduce directional",
                'code': "ambient-light: 0x000000 → 0x111111"
            })
        else:
            recommendations.append({
                'issue': f"Contrast {abs(contrast_diff_pct):.0f}% too low",
                'fix': "Remove ambient light or increase directional",
                'code': "ambient-light: 0x111111 → 0x000000"
            })

    return recommendations

def create_comparison_visualization(ref_rgb, impl_rgb, ref_wave, impl_wave,
                                   ref_light, impl_light, recommendations, output_dir):
    """Create detailed comparison visualization."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    fig = plt.figure(figsize=(16, 10))
    gs = fig.add_gridspec(3, 3, hspace=0.3, wspace=0.3)

    # Images
    ax1 = fig.add_subplot(gs[0, 0])
    ax1.imshow(ref_rgb)
    ax1.set_title('Reference', fontsize=14, fontweight='bold')
    ax1.axis('off')

    ax2 = fig.add_subplot(gs[0, 1])
    ax2.imshow(impl_rgb)
    ax2.set_title('Implementation', fontsize=14, fontweight='bold')
    ax2.axis('off')

    # Diff heatmap
    ax3 = fig.add_subplot(gs[0, 2])
    ref_gray = cv2.cvtColor(ref_rgb, cv2.COLOR_RGB2GRAY)
    impl_gray = cv2.cvtColor(impl_rgb, cv2.COLOR_RGB2GRAY)

    # Resize to same size
    if ref_gray.shape != impl_gray.shape:
        impl_gray_resized = cv2.resize(impl_gray, (ref_gray.shape[1], ref_gray.shape[0]))
    else:
        impl_gray_resized = impl_gray

    diff = np.abs(ref_gray.astype(float) - impl_gray_resized.astype(float))
    ax3.imshow(diff, cmap='hot')
    ax3.set_title('Absolute Difference', fontsize=14, fontweight='bold')
    ax3.axis('off')

    # Radial profiles
    ax4 = fig.add_subplot(gs[1, :2])
    ax4.plot(ref_wave['radial_profile'], label=f"Reference ({ref_wave['num_rings']} rings)",
            color='blue', linewidth=2)
    ax4.plot(impl_wave['radial_profile'], label=f"Implementation ({impl_wave['num_rings']} rings)",
            color='red', linewidth=2, alpha=0.7)
    ax4.set_title('Wave Pattern Comparison', fontsize=14, fontweight='bold')
    ax4.set_xlabel('Distance from center (pixels)')
    ax4.set_ylabel('Brightness')
    ax4.legend()
    ax4.grid(True, alpha=0.3)

    # Metrics comparison
    ax5 = fig.add_subplot(gs[1, 2])
    ax5.axis('off')

    metrics_text = f"""METRICS COMPARISON

Wave Pattern:
  Rings:    {impl_wave['num_rings']} vs {ref_wave['num_rings']}
  Spacing:  {impl_wave['ring_spacing']:.1f} vs {ref_wave['ring_spacing']:.1f}px
  Amplitude: {impl_wave['amplitude']:.1f} vs {ref_wave['amplitude']:.1f}

Lighting:
  Brightness: {impl_light['mean_brightness']:.1f} vs {ref_light['mean_brightness']:.1f}
  Contrast:   {impl_light['contrast']:.3f} vs {ref_light['contrast']:.3f}
  Dark ratio: {impl_light['dark_ratio']:.1%} vs {ref_light['dark_ratio']:.1%}
"""

    ax5.text(0.1, 0.5, metrics_text, fontsize=10, family='monospace',
            verticalalignment='center', bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.3))

    # Recommendations
    ax6 = fig.add_subplot(gs[2, :])
    ax6.axis('off')

    rec_text = "🎯 ACTIONABLE FIXES:\n\n"
    for i, rec in enumerate(recommendations, 1):
        rec_text += f"{i}. {rec['issue']}\n"
        rec_text += f"   → {rec['fix']}\n"
        rec_text += f"   Code: {rec['code']}\n\n"

    if not recommendations:
        rec_text += "✓ No major differences detected!"

    ax6.text(0.05, 0.95, rec_text, fontsize=11, family='monospace',
            verticalalignment='top', bbox=dict(boxstyle='round', facecolor='lightgreen', alpha=0.3))

    plt.savefig(output_dir / 'comparison_actionable.png', dpi=150, bbox_inches='tight')
    plt.close()

def main():
    parser = argparse.ArgumentParser(description='Actionable visual comparison')
    parser.add_argument('reference', help='Reference image')
    parser.add_argument('implementation', help='Implementation image')
    parser.add_argument('--output-dir', default='/tmp/comparison-actionable',
                       help='Output directory')

    args = parser.parse_args()

    # Load images
    ref_img = cv2.imread(args.reference)
    impl_img = cv2.imread(args.implementation)

    if ref_img is None or impl_img is None:
        print("Error: Could not load images")
        sys.exit(1)

    ref_rgb = cv2.cvtColor(ref_img, cv2.COLOR_BGR2RGB)
    impl_rgb = cv2.cvtColor(impl_img, cv2.COLOR_BGR2RGB)

    ref_gray = cv2.cvtColor(ref_img, cv2.COLOR_BGR2GRAY)
    impl_gray = cv2.cvtColor(impl_img, cv2.COLOR_BGR2GRAY)

    print("=" * 70)
    print("ACTIONABLE VISUAL COMPARISON")
    print("=" * 70)
    print()

    # Analyze both images
    print("Analyzing reference...")
    ref_wave = analyze_wave_pattern(ref_gray)
    ref_light = analyze_lighting(ref_gray)

    print("Analyzing implementation...")
    impl_wave = analyze_wave_pattern(impl_gray)
    impl_light = analyze_lighting(impl_gray)

    print()
    print("=" * 70)
    print("COMPARISON RESULTS")
    print("=" * 70)
    print()

    # Generate recommendations
    recommendations = generate_recommendations(ref_wave, impl_wave, ref_light, impl_light)

    print("🎯 ACTIONABLE FIXES:")
    print()

    if recommendations:
        for i, rec in enumerate(recommendations, 1):
            print(f"{i}. {rec['issue']}")
            print(f"   → {rec['fix']}")
            print(f"   Code: {rec['code']}")
            print()
    else:
        print("✓ No major differences detected!")
        print()

    # Create visualization
    print("Creating comparison visualization...")
    create_comparison_visualization(ref_rgb, impl_rgb, ref_wave, impl_wave,
                                   ref_light, impl_light, recommendations,
                                   args.output_dir)

    print(f"✓ Saved to: {args.output_dir}/comparison_actionable.png")
    print()

if __name__ == '__main__':
    main()
