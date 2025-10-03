#!/usr/bin/env python3
"""
Reference Analysis - Extract characteristics to guide implementation.

Instead of just comparing impl vs ref, this analyzes the reference
to tell you WHAT to implement.
"""

import sys
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
import cv2
import matplotlib.pyplot as plt

def analyze_wave_pattern(gray):
    """Detect concentric ring pattern and frequency."""
    height, width = gray.shape
    center_y, center_x = height // 2, width // 2

    # Sample along radius from center
    max_radius = min(center_x, center_y)
    radial_profile = []

    for r in range(0, max_radius, 2):
        # Sample pixels at this radius (8 directions)
        samples = []
        for angle in np.linspace(0, 2*np.pi, 8, endpoint=False):
            x = int(center_x + r * np.cos(angle))
            y = int(center_y + r * np.sin(angle))
            if 0 <= x < width and 0 <= y < height:
                samples.append(gray[y, x])

        if samples:
            radial_profile.append(np.mean(samples))

    radial_profile = np.array(radial_profile)

    # Find peaks (bright rings) and valleys (dark rings)
    from scipy.signal import find_peaks

    peaks, _ = find_peaks(radial_profile, distance=5, prominence=10)
    valleys, _ = find_peaks(-radial_profile, distance=5, prominence=10)

    # Estimate frequency by counting rings
    num_rings = len(peaks)

    # Estimate spacing (average distance between peaks)
    if len(peaks) > 1:
        spacing = np.mean(np.diff(peaks)) * 2  # *2 because we sampled every 2 pixels
    else:
        spacing = 0

    # Try to estimate wave frequency by FFT
    from scipy.fft import fft, fftfreq

    if len(radial_profile) > 10:
        yf = fft(radial_profile)
        xf = fftfreq(len(radial_profile), 2.0)  # 2.0 = sampling interval

        # Find dominant frequency (exclude DC component)
        dominant_freq_idx = np.argmax(np.abs(yf[1:len(yf)//2])) + 1
        dominant_freq = xf[dominant_freq_idx]
        estimated_pi_multiplier = abs(dominant_freq) * max_radius / np.pi
    else:
        estimated_pi_multiplier = 0

    return {
        'num_rings': num_rings,
        'ring_spacing_px': spacing,
        'estimated_frequency': estimated_pi_multiplier,
        'radial_profile': radial_profile,
        'peaks': peaks,
        'valleys': valleys
    }

def analyze_lighting(image_rgb, gray):
    """Analyze lighting direction and intensity."""
    # Find brightest region (likely light source reflection)
    bright_threshold = np.percentile(gray, 95)
    bright_mask = gray > bright_threshold

    if np.any(bright_mask):
        y_coords, x_coords = np.where(bright_mask)
        center_of_brightness = (np.mean(x_coords), np.mean(y_coords))
    else:
        center_of_brightness = None

    # Analyze overall brightness distribution
    mean_brightness = np.mean(gray)
    std_brightness = np.std(gray)

    # Dark vs bright ratio
    dark_threshold = np.percentile(gray, 25)
    bright_threshold = np.percentile(gray, 75)

    dark_pixels = np.sum(gray < dark_threshold)
    bright_pixels = np.sum(gray > bright_threshold)
    contrast_ratio = bright_pixels / (dark_pixels + 1)

    return {
        'mean_brightness': mean_brightness,
        'std_brightness': std_brightness,
        'contrast_ratio': contrast_ratio,
        'brightness_center': center_of_brightness
    }

def analyze_color_distribution(image_rgb):
    """Analyze color characteristics."""
    # Convert to HSV for better color analysis
    hsv = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2HSV)

    # Get dominant hue
    h_channel = hsv[:,:,0]
    s_channel = hsv[:,:,1]
    v_channel = hsv[:,:,2]

    # Filter out low-saturation pixels (grays)
    colored_pixels = s_channel > 30

    if np.any(colored_pixels):
        dominant_hue = np.median(h_channel[colored_pixels])
        saturation = np.mean(s_channel[colored_pixels])
    else:
        dominant_hue = None
        saturation = 0

    # Overall color temperature (warm vs cool)
    # More blue = cool, more red/orange = warm
    r_mean = np.mean(image_rgb[:,:,0])
    b_mean = np.mean(image_rgb[:,:,2])

    if b_mean > r_mean:
        temperature = "cool"
    elif r_mean > b_mean:
        temperature = "warm"
    else:
        temperature = "neutral"

    return {
        'dominant_hue': dominant_hue,
        'saturation': saturation,
        'temperature': temperature,
        'is_grayscale': saturation < 10
    }

def analyze_geometry(gray):
    """Analyze geometric properties of main object."""
    # Threshold to find main object
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # Find largest contour
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    if not contours:
        return None

    main_contour = max(contours, key=cv2.contourArea)

    # Fit ellipse
    if len(main_contour) >= 5:
        ellipse = cv2.fitEllipse(main_contour)
        (center, (width, height), angle) = ellipse

        # Eccentricity
        a = max(width, height) / 2
        b = min(width, height) / 2

        if a > 0:
            eccentricity = np.sqrt(1 - (b**2 / a**2))
        else:
            eccentricity = 0

        return {
            'eccentricity': eccentricity,
            'aspect_ratio': width / height if height > 0 else 1.0,
            'angle': angle,
            'center': center,
            'size': (width, height)
        }

    return None

def save_analysis_visualization(image_rgb, wave_analysis, lighting_analysis, output_dir):
    """Create visualizations of analysis."""
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    fig, axes = plt.subplots(2, 2, figsize=(12, 10))

    # Original image
    axes[0, 0].imshow(image_rgb)
    axes[0, 0].set_title('Reference Image')
    axes[0, 0].axis('off')

    # Radial profile with detected rings
    if wave_analysis:
        axes[0, 1].plot(wave_analysis['radial_profile'], label='Brightness along radius')
        axes[0, 1].plot(wave_analysis['peaks'],
                       wave_analysis['radial_profile'][wave_analysis['peaks']],
                       'r^', label=f"{len(wave_analysis['peaks'])} bright rings")
        axes[0, 1].plot(wave_analysis['valleys'],
                       wave_analysis['radial_profile'][wave_analysis['valleys']],
                       'bv', label=f"{len(wave_analysis['valleys'])} dark valleys")
        axes[0, 1].set_title('Radial Wave Pattern Analysis')
        axes[0, 1].set_xlabel('Distance from center (pixels)')
        axes[0, 1].set_ylabel('Brightness')
        axes[0, 1].legend()
        axes[0, 1].grid(True, alpha=0.3)

    # Brightness distribution
    gray = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2GRAY)
    axes[1, 0].hist(gray.ravel(), bins=50, color='gray', alpha=0.7)
    axes[1, 0].axvline(lighting_analysis['mean_brightness'], color='r',
                      linestyle='--', label=f"Mean: {lighting_analysis['mean_brightness']:.1f}")
    axes[1, 0].set_title('Brightness Distribution')
    axes[1, 0].set_xlabel('Pixel Value')
    axes[1, 0].set_ylabel('Frequency')
    axes[1, 0].legend()

    # Brightness heatmap
    axes[1, 1].imshow(gray, cmap='hot')
    axes[1, 1].set_title('Brightness Heatmap')
    axes[1, 1].axis('off')

    plt.tight_layout()
    plt.savefig(output_dir / 'reference_analysis.png', dpi=150, bbox_inches='tight')
    plt.close()

def main():
    parser = argparse.ArgumentParser(description='Analyze reference image characteristics')
    parser.add_argument('reference', help='Path to reference image')
    parser.add_argument('--output-dir', default='/tmp/reference-analysis',
                       help='Output directory for visualizations')

    args = parser.parse_args()

    # Load image
    image = cv2.imread(args.reference)
    if image is None:
        print(f"Error: Could not load image: {args.reference}")
        sys.exit(1)

    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    print("=" * 60)
    print("REFERENCE IMAGE ANALYSIS")
    print("=" * 60)
    print()

    # Analyze wave pattern
    print("[1/4] Analyzing wave pattern...")
    wave_analysis = analyze_wave_pattern(gray)

    print(f"\n📊 WAVE PATTERN CHARACTERISTICS:")
    print(f"  • Detected rings: {wave_analysis['num_rings']}")
    print(f"  • Ring spacing: {wave_analysis['ring_spacing_px']:.1f} pixels")
    print(f"  • Estimated frequency: ~{wave_analysis['estimated_frequency']:.1f} (for sin(dist * π * N))")
    print()

    # Analyze lighting
    print("[2/4] Analyzing lighting...")
    lighting_analysis = analyze_lighting(image_rgb, gray)

    print(f"\n💡 LIGHTING CHARACTERISTICS:")
    print(f"  • Mean brightness: {lighting_analysis['mean_brightness']:.1f} / 255")
    print(f"  • Std deviation: {lighting_analysis['std_brightness']:.1f}")
    print(f"  • Contrast ratio: {lighting_analysis['contrast_ratio']:.2f}")
    if lighting_analysis['brightness_center']:
        print(f"  • Brightest region center: ({lighting_analysis['brightness_center'][0]:.0f}, {lighting_analysis['brightness_center'][1]:.0f})")
    print()

    # Analyze color
    print("[3/4] Analyzing color distribution...")
    color_analysis = analyze_color_distribution(image_rgb)

    print(f"\n🎨 COLOR CHARACTERISTICS:")
    if color_analysis['is_grayscale']:
        print(f"  • Image is grayscale (saturation: {color_analysis['saturation']:.1f}%)")
    else:
        print(f"  • Dominant hue: {color_analysis['dominant_hue']:.0f}°")
        print(f"  • Saturation: {color_analysis['saturation']:.1f}%")
    print(f"  • Temperature: {color_analysis['temperature']}")
    print()

    # Analyze geometry
    print("[4/4] Analyzing geometry...")
    geometry_analysis = analyze_geometry(gray)

    if geometry_analysis:
        print(f"\n📐 GEOMETRIC CHARACTERISTICS:")
        print(f"  • Eccentricity: {geometry_analysis['eccentricity']:.3f}")
        print(f"  • Aspect ratio: {geometry_analysis['aspect_ratio']:.3f}")
        print(f"  • Rotation angle: {geometry_analysis['angle']:.1f}°")
        print(f"  • Size: {geometry_analysis['size'][0]:.0f} x {geometry_analysis['size'][1]:.0f} px")
    print()

    # Save visualizations
    save_analysis_visualization(image_rgb, wave_analysis, lighting_analysis, args.output_dir)

    print("=" * 60)
    print(f"✓ Analysis complete. Visualizations saved to: {args.output_dir}")
    print("=" * 60)
    print()

    # Actionable recommendations
    print("🎯 IMPLEMENTATION GUIDANCE:")
    print()
    print(f"1. Wave Pattern:")
    print(f"   Use: (sin(dist * π * {wave_analysis['estimated_frequency']:.0f}))")
    print(f"   Create {wave_analysis['num_rings']} concentric rings")
    print()
    print(f"2. Lighting:")
    if lighting_analysis['mean_brightness'] < 100:
        print(f"   Use LOW ambient + directional lighting (dark scene)")
    elif lighting_analysis['mean_brightness'] > 150:
        print(f"   Use HIGH ambient + directional lighting (bright scene)")
    else:
        print(f"   Use MEDIUM lighting (balanced scene)")
    print(f"   Contrast ratio: {lighting_analysis['contrast_ratio']:.2f} (higher = more dramatic)")
    print()
    print(f"3. Color:")
    if color_analysis['is_grayscale']:
        print(f"   Use neutral gray materials (no color tint)")
    print(f"   Temperature: {color_analysis['temperature']}")
    print()

    if geometry_analysis:
        print(f"4. Camera Angle:")
        if geometry_analysis['eccentricity'] < 0.3:
            print(f"   Near top-down view (low eccentricity)")
        elif geometry_analysis['eccentricity'] > 0.7:
            print(f"   3/4 perspective view (high eccentricity)")
        print(f"   Rotate: {geometry_analysis['angle']:.0f}°")
    print()

if __name__ == '__main__':
    main()
