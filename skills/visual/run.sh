#!/usr/bin/env bash
# Visual Validation Skill - Main orchestration script
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
SCRIPTS_DIR="${PROJECT_ROOT}/scripts"
CONFIG_FILE="${SCRIPT_DIR}/config.edn"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

usage() {
    cat <<EOF
Visual Validation Skill - Analyze and compare visual outputs

USAGE:
    run.sh <command> [options]

COMMANDS:
    analyze <image>              Extract patterns from reference image
    compare <ref> <impl>         Compare and get actionable fixes
    batch <cmd> <pattern>        Process multiple images
    check-env                    Verify Python dependencies
    help                         Show this help

OPTIONS:
    --aspect <aspect>            Focus on specific aspect (waves|lighting|geometry)
    --output <file>              Save results to file
    --format <format>            Output format (text|json|yaml|markdown)
    --verbose                    Show detailed output

EXAMPLES:
    # Analyze reference
    run.sh analyze reference.png

    # Get fixes
    run.sh compare reference.png implementation.png

    # Focus on lighting
    run.sh compare ref.png impl.png --aspect lighting

    # Batch process
    run.sh batch analyze tests/references/*.png

    # JSON output
    run.sh compare ref.png impl.png --format json > report.json
EOF
}

check_env() {
    echo -e "${BLUE}Checking Python environment...${NC}"

    local missing=()

    # Check Python
    if ! command -v python3 >/dev/null 2>&1; then
        missing+=("python3")
    fi

    # Check required packages
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "import cv2" 2>/dev/null || missing+=("opencv-python")
        python3 -c "import numpy" 2>/dev/null || missing+=("numpy")
        python3 -c "import PIL" 2>/dev/null || missing+=("pillow")
    fi

    if [[ ${#missing[@]} -gt 0 ]]; then
        echo -e "${RED}✗ Missing dependencies: ${missing[*]}${NC}" >&2
        echo ""
        echo "Install with:"
        echo "  pip3 install opencv-python numpy pillow"
        exit 1
    fi

    # Check scripts exist
    if [[ ! -f "${SCRIPTS_DIR}/visual-analyze-reference" ]]; then
        echo -e "${YELLOW}⚠ Warning: visual-analyze-reference script not found${NC}"
    fi

    if [[ ! -f "${SCRIPTS_DIR}/visual-compare-actionable" ]]; then
        echo -e "${YELLOW}⚠ Warning: visual-compare-actionable script not found${NC}"
    fi

    echo -e "${GREEN}✓ Environment OK${NC}"
    return 0
}

analyze_image() {
    local image="$1"
    local aspect="${2:-all}"
    local output_file="${3:-}"
    local format="${4:-text}"

    if [[ ! -f "$image" ]]; then
        echo -e "${RED}✗ Image not found: $image${NC}" >&2
        exit 1
    fi

    echo -e "${GREEN}🔍 Analyzing: $image${NC}"

    local script="${SCRIPTS_DIR}/visual-analyze-reference"

    if [[ -f "$script" ]]; then
        # Call the actual script
        if [[ -n "$output_file" ]]; then
            "$script" "$image" > "$output_file"
            echo -e "${GREEN}✓ Results saved to: $output_file${NC}"
        else
            "$script" "$image"
        fi
    else
        # Fallback: Basic analysis with Python inline
        echo -e "${YELLOW}Using basic analysis (full script not available)${NC}"
        python3 <<EOF
import cv2
import numpy as np
from pathlib import Path

img = cv2.imread('$image', cv2.IMREAD_GRAYSCALE)
if img is None:
    print("Error: Could not read image")
    exit(1)

h, w = img.shape
mean_brightness = np.mean(img)
std_brightness = np.std(img)

print(f"Image Analysis: $(basename $image)")
print(f"  Size: {w}x{h}px")
print(f"  Brightness: {mean_brightness:.1f} (±{std_brightness:.1f})")
print(f"  Range: {np.min(img)} - {np.max(img)}")

# Simple wave detection (circles)
circles = cv2.HoughCircles(img, cv2.HOUGH_GRADIENT, 1, 20,
                           param1=50, param2=30, minRadius=5, maxRadius=200)
if circles is not None:
    circles = np.uint16(np.around(circles))
    print(f"  Detected patterns: {len(circles[0])} circular features")
else:
    print(f"  Detected patterns: None (try adjusting thresholds)")
EOF
    fi
}

compare_images() {
    local reference="$1"
    local implementation="$2"
    local aspect="${3:-all}"
    local format="${4:-text}"

    if [[ ! -f "$reference" ]]; then
        echo -e "${RED}✗ Reference not found: $reference${NC}" >&2
        exit 1
    fi

    if [[ ! -f "$implementation" ]]; then
        echo -e "${RED}✗ Implementation not found: $implementation${NC}" >&2
        exit 1
    fi

    echo -e "${GREEN}🔬 Comparing images${NC}"
    echo -e "  Reference: $(basename $reference)"
    echo -e "  Implementation: $(basename $implementation)"
    echo ""

    local script="${SCRIPTS_DIR}/visual-compare-actionable"

    if [[ -f "$script" ]]; then
        # Call the actual script
        "$script" "$reference" "$implementation"
    else
        # Fallback: Basic comparison
        echo -e "${YELLOW}Using basic comparison (full script not available)${NC}"
        python3 <<EOF
import cv2
import numpy as np

ref = cv2.imread('$reference', cv2.IMREAD_GRAYSCALE)
impl = cv2.imread('$implementation', cv2.IMREAD_GRAYSCALE)

if ref is None or impl is None:
    print("Error: Could not read images")
    exit(1)

# Resize if needed
if ref.shape != impl.shape:
    impl = cv2.resize(impl, (ref.shape[1], ref.shape[0]))

# Calculate differences
diff = np.abs(ref.astype(float) - impl.astype(float))
mean_diff = np.mean(diff)
max_diff = np.max(diff)

ref_brightness = np.mean(ref)
impl_brightness = np.mean(impl)
brightness_ratio = impl_brightness / ref_brightness if ref_brightness > 0 else 1.0

print("Comparison Results:")
print(f"  Mean difference: {mean_diff:.1f}")
print(f"  Max difference: {max_diff:.1f}")
print("")

print("Fixes:")
if abs(brightness_ratio - 1.0) > 0.10:
    if brightness_ratio > 1.0:
        mult = 1.0 / brightness_ratio
        print(f"  ✗ Scene {(brightness_ratio-1)*100:.0f}% too bright → multiply intensity by {mult:.2f}")
    else:
        mult = 1.0 / brightness_ratio
        print(f"  ✗ Scene {(1-brightness_ratio)*100:.0f}% too dark → multiply intensity by {mult:.2f}")
else:
    print(f"  ✓ Brightness OK (within 10%)")

if mean_diff < 10:
    print(f"  ✓ Overall match good")
elif mean_diff < 30:
    print(f"  ⚠ Moderate differences detected")
else:
    print(f"  ✗ Significant differences detected")
EOF
    fi
}

batch_process() {
    local command="$1"
    shift

    local images=("$@")
    local passed=0
    local failed=0
    local total=${#images[@]}

    echo -e "${BLUE}Batch processing ${total} images...${NC}"
    echo ""

    for img in "${images[@]}"; do
        echo -e "${BLUE}Processing: $(basename $img)${NC}"

        case "$command" in
            analyze)
                if analyze_image "$img" "all" "" "text" >/dev/null 2>&1; then
                    ((passed++))
                    echo -e "${GREEN}  ✓ OK${NC}"
                else
                    ((failed++))
                    echo -e "${RED}  ✗ Failed${NC}"
                fi
                ;;
            *)
                echo -e "${RED}Unknown batch command: $command${NC}"
                exit 1
                ;;
        esac
        echo ""
    done

    echo "======================================"
    echo -e "Summary: ${GREEN}${passed}${NC} passed, ${RED}${failed}${NC} failed"

    [[ $failed -eq 0 ]] && exit 0 || exit 1
}

# Main command dispatcher
main() {
    if [[ $# -eq 0 ]]; then
        usage
        exit 1
    fi

    local command="$1"
    shift

    case "$command" in
        analyze)
            [[ $# -eq 0 ]] && { echo "Error: analyze requires image path"; usage; exit 1; }
            local image="$1"
            shift

            local aspect="all"
            local output_file=""
            local format="text"

            while [[ $# -gt 0 ]]; do
                case "$1" in
                    --aspect) aspect="$2"; shift 2 ;;
                    --output) output_file="$2"; shift 2 ;;
                    --format) format="$2"; shift 2 ;;
                    *) echo "Unknown option: $1"; usage; exit 1 ;;
                esac
            done

            check_env
            analyze_image "$image" "$aspect" "$output_file" "$format"
            ;;

        compare)
            [[ $# -lt 2 ]] && { echo "Error: compare requires reference and implementation"; usage; exit 1; }
            local reference="$1"
            local implementation="$2"
            shift 2

            local aspect="all"
            local format="text"

            while [[ $# -gt 0 ]]; do
                case "$1" in
                    --aspect) aspect="$2"; shift 2 ;;
                    --format) format="$2"; shift 2 ;;
                    *) echo "Unknown option: $1"; usage; exit 1 ;;
                esac
            done

            check_env
            compare_images "$reference" "$implementation" "$aspect" "$format"
            ;;

        batch)
            [[ $# -lt 2 ]] && { echo "Error: batch requires command and pattern"; usage; exit 1; }
            check_env
            batch_process "$@"
            ;;

        check-env)
            check_env
            ;;

        help|--help|-h)
            usage
            ;;

        *)
            echo "Unknown command: $command"
            usage
            exit 1
            ;;
    esac
}

main "$@"
