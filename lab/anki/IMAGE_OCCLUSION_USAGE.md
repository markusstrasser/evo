# Image Occlusion - Usage Guide

## Testing the Feature

### 1. Start the dev server

```bash
npx shadow-cljs watch anki
```

Open http://localhost:8081/public/anki.html

### 2. Create a test card

1. Click "Select Folder" and choose an anki folder (or create a new empty folder)
2. Once in the review screen, click "Create Test Image Occlusion"
3. This will create 4 virtual cards from the test image (one for each colored region)

### 3. Review the cards

- Each occlusion is reviewed separately
- The canvas shows the image with a green mask over the occluded region
- Click "Show Answer" to reveal the answer and remove the mask
- Rate the card (Forgot/Hard/Good/Easy) to schedule the next review

## Current Implementation

**What works:**
- Parent-child card architecture (1 parent → N virtual children)
- Canvas rendering with green occlusion masks
- Normalized coordinates (0-1 range, device-independent)
- Rectangle shapes only
- SM-2 scheduling per occlusion
- Review history display

**Simplified:**
- No polygon or freehand shapes (rectangles only)
- No drawing UI (cards created programmatically)
- No File System Access API integration (uses public URLs)
- No image upload (test image pre-loaded)

## Test Image

Location: `public/test-images/test-regions.png`

- 400x300px image with 4 colored regions
- Region A (red, top-left): coordinates (50, 50, 100, 80)
- Region B (cyan, top-right): coordinates (200, 50, 100, 80)
- Region C (yellow, bottom-left): coordinates (50, 180, 100, 80)
- Region D (teal, bottom-right): coordinates (200, 180, 100, 80)

## Schema

See `IMAGE_OCCLUSION_SCHEMA.md` for full schema documentation.

**Parent Card:**
```clojure
{:type :image-occlusion
 :asset {:url "/test-images/test-regions.png"
         :width 400
         :height 300}
 :prompt "What is this region?"
 :occlusions [{:oid #uuid "..."
               :shape {:kind :rect
                       :normalized? true
                       :x 0.125 :y 0.167 :w 0.25 :h 0.267}
               :answer "Region A"}
              ...]}
```

**Virtual Child Card** (computed at reduce-time):
```clojure
{:type :image-occlusion/item
 :parent-id "sha256:..."
 :occlusion-oid #uuid "..."
 :asset {:url "/test-images/test-regions.png" :width 400 :height 300}
 :prompt "What is this region?"
 :shape {:kind :rect :normalized? true :x 0.125 :y 0.167 :w 0.25 :h 0.267}
 :answer "Region A"}
```

## Next Steps (Future Work)

If you want to extend this implementation:

1. **Drawing UI**: Add canvas-based rectangle drawing tool
2. **Polygon shapes**: Implement polygon rendering and drawing
3. **Freehand shapes**: Add freehand path drawing with Douglas-Peucker simplification
4. **Image upload**: Integrate File System Access API for local images
5. **Image hashing**: Compute SHA-256 hash for asset content-addressing
6. **Editor screen**: Add dedicated screen for creating/editing image occlusion cards
7. **Markdown parsing**: Support image occlusion syntax in cards.md

## Code Locations

- **Core logic**: `src/lab/anki/core.cljc` (expand-image-occlusion, apply-event)
- **UI rendering**: `src/lab/anki/ui.cljs` (draw-occlusion-mask!, review-card)
- **Test helpers**: `src/lab/anki/test_occlusion.cljs`
- **E2E tests**: `src/lab/anki/test/image-occlusion.spec.js`
- **Schema docs**: `src/lab/anki/IMAGE_OCCLUSION_SCHEMA.md`
