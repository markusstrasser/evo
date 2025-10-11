# Image Occlusion Schema Design

**Analysis by**: GPT-5 Codex (high reasoning effort)
**Date**: 2025-10-11

## Recommended Schema

### Core Principles

1. **Parent-child architecture**: One card with multiple occlusions, each reviewed separately
2. **Normalized coordinates**: 0-1 range for device/resolution independence
3. **Asset content-addressing**: SHA-256 hash for image identity
4. **Virtual derived cards**: Computed at reduce-time, not stored

### Schema Structure

```clojure
;; Parent card (stored in event log)
{:type :image-occlusion
 :asset {:id "sha256:ab12..."           ; Content hash
         :path "assets/brain.png"       ; File System Access API reference
         :width 1450                     ; Original dimensions
         :height 1024
         :hash "sha256:..."              ; Byte-level hash for validation
         :last-modified 1738623312}

 :prompt "Name the covered region"      ; Shared question across occlusions

 :occlusions
 [{:oid #uuid "..."                     ; Stable ID per occlusion
   :shape {:kind :rect                  ; Shape type: :rect, :polygon, :freehand
           :normalized? true
           :x 0.42 :y 0.18 :w 0.11 :h 0.07
           :bbox {:x 0.42 :y 0.18 :w 0.11 :h 0.07}}  ; For hit-testing
   :answer "Broca's area"
   :hints ["Language production"]
   :mask-style {:fill "#00ff00" :opacity 0.45}}     ; Visual only, not hashed

  {:oid #uuid "..."
   :shape {:kind :freehand
           :normalized? true
           :path [{:x 0.61 :y 0.27} {:x 0.63 :y 0.30} ...]
           :simplification {:epsilon 0.002}}         ; Douglas-Peucker
   :answer "Wernicke's area"}]

 :metadata {:created-at 1738623312 :author "alien"}}
```

### Derived Review Cards (Virtual)

Computed during event reduction, not stored:

```clojure
;; Generated per occlusion for SM-2 scheduling
{:type :image-occlusion/item
 :parent-id "sha256:card..."           ; Links back to parent
 :occlusion-oid #uuid "..."            ; Which occlusion from parent
 :question {:asset "sha256:ab12..."
            :mask {:kind :rect :x 0.42 :y 0.18 :w 0.11 :h 0.07}}
 :answer "Broca's area"}
```

### Meta Storage

```clojure
;; SM-2 scheduling state keyed by composite ID
{:meta {["sha256:card..." #uuid "occlusion1"] {:reviews 3 :due-at ...}
        ["sha256:card..." #uuid "occlusion2"] {:reviews 1 :due-at ...}}}
```

## Event Sourcing Integration

### Event Types

```clojure
;; Creating parent card with all occlusions
{:event/type :card-created
 :event/timestamp #inst "2025-10-11"
 :event/data {:card-hash "sha256:..."
              :card {:type :image-occlusion ...}}}

;; Reviewing a specific occlusion
{:event/type :review
 :event/timestamp #inst "2025-10-11"
 :event/data {:card-hash ["sha256:..." #uuid "occlusion-oid"]
              :rating :good}}
```

### Reduction Logic

```clojure
(defn reduce-events [events]
  (reduce apply-event {:cards {} :meta {}} events))

(defn apply-event [state event]
  (case (:event/type event)
    :card-created
    (let [{:keys [card-hash card]} (:event/data event)]
      (if (= :image-occlusion (:type card))
        ;; Expand parent into virtual children
        (reduce (fn [s {:keys [oid] :as occlusion}]
                  (let [child-id [card-hash oid]]
                    (-> s
                        (assoc-in [:cards child-id]
                                  (derive-child-card card occlusion))
                        (assoc-in [:meta child-id]
                                  (new-card-meta child-id)))))
                (assoc-in state [:cards card-hash] card)
                (:occlusions card))
        ;; Regular card
        (assoc-in state [:cards card-hash] card)))

    :review
    ;; Works with composite keys [parent-id occlusion-oid]
    (let [{:keys [card-hash rating]} (:event/data event)]
      (update-in state [:meta card-hash] schedule-card rating))))
```

## Shape Types

### Rectangle
```clojure
{:kind :rect
 :normalized? true
 :x 0.42        ; Normalized left (0-1)
 :y 0.18        ; Normalized top (0-1)
 :w 0.11        ; Normalized width
 :h 0.07        ; Normalized height
 :bbox {:x 0.42 :y 0.18 :w 0.11 :h 0.07}}
```

### Polygon
```clojure
{:kind :polygon
 :normalized? true
 :points [{:x 0.342 :y 0.418}
          {:x 0.389 :y 0.401}
          {:x 0.377 :y 0.468}
          {:x 0.331 :y 0.452}]
 :bbox {:x 0.331 :y 0.401 :w 0.058 :h 0.067}}
```

### Freehand
```clojure
{:kind :freehand
 :normalized? true
 :path [{:x 0.61 :y 0.27} {:x 0.63 :y 0.30} ...]
 :simplification {:epsilon 0.002}      ; Douglas-Peucker tolerance
 :bbox {:x 0.61 :y 0.27 :w 0.08 :h 0.09}}
```

## Card Hashing

### Canonical Form (for stable hashing)

```clojure
(defn canonical-occlusion-card [card]
  "Remove visual styling and non-semantic data before hashing"
  (-> card
      (dissoc :metadata)                    ; Timestamps, authors
      (update :occlusions
              #(mapv (fn [occ]
                       (-> occ
                           (dissoc :mask-style)       ; Colors, opacity
                           (update :shape dissoc :bbox) ; Derived data
                           (update-in [:shape :path]    ; Quantize coords
                                      (partial mapv quantize-point))))
                     %))
      (update :asset dissoc :last-modified)))  ; Ignore file metadata

(defn quantize-point [pt]
  "Round to 4 decimal places to avoid float drift"
  {:x (/ (Math/round (* (:x pt) 10000)) 10000)
   :y (/ (Math/round (* (:y pt) 10000)) 10000)})

(defn card-hash [card]
  (hash (pr-str (canonical-occlusion-card card))))
```

## Edge Cases & Gotchas

### 1. File Handle Invalidation
**Problem**: File System Access API handles expire when browser restarts
**Solution**: Store both hash and path; on missing handle, prompt user to reselect file, validate hash matches

```clojure
(defn restore-image [asset]
  (p/catch
    (resolve-handle (:path asset))
    (fn [_]
      ;; Handle expired, ask user to relink
      (p/let [new-handle (pick-file-with-prompt (:path asset))
              blob (.getFile new-handle)
              new-hash (compute-hash blob)]
        (if (= new-hash (:hash asset))
          new-handle
          (throw (ex-info "Hash mismatch - wrong file selected")))))))
```

### 2. Canvas Export Noise
**Problem**: Freehand paths vary by cursor sampling rate
**Solution**: Always simplify with Douglas-Peucker before hashing

```clojure
(defn normalize-freehand [path]
  (-> path
      (douglas-peucker 0.002)    ; Epsilon = 0.2% of image
      (mapv quantize-point)))     ; Round to 4 decimals
```

### 3. Image Edits
**Problem**: Editing image changes hash, invalidates occlusions
**Solution**: Treat as new card OR provide migration tool

```clojure
;; Option 1: New card (simplest)
(defn on-image-change [old-card new-image]
  (let [new-hash (compute-hash new-image)]
    (when (not= new-hash (get-in old-card [:asset :hash]))
      (warn "Image changed - creating new card")
      (create-new-card new-image))))

;; Option 2: Migration (advanced)
(defn migrate-occlusions [old-card new-image]
  (let [scale-x (/ (:width new-image) (get-in old-card [:asset :width]))
        scale-y (/ (:height new-image) (get-in old-card [:asset :height]))]
    ;; Normalized coords still work if aspect ratio unchanged
    (if (approx= scale-x scale-y)
      old-card  ; Safe to reuse
      (prompt-user-to-redraw))))  ; Aspect changed, need manual fix
```

### 4. Degenerate Shapes
**Problem**: Zero-area shapes, self-intersections
**Solution**: Validate at creation time

```clojure
(defn validate-shape [shape]
  (case (:kind shape)
    :rect (and (pos? (:w shape)) (pos? (:h shape)))
    :polygon (>= (count (:points shape)) 3)
    :freehand (>= (count (:path shape)) 2)))

(defn create-occlusion [shape answer]
  (if (validate-shape shape)
    {:oid (random-uuid) :shape shape :answer answer}
    (throw (ex-info "Invalid shape" {:shape shape}))))
```

### 5. Unicode in Answers
**Problem**: Different UTF-8 encodings produce different hashes
**Solution**: Normalize to NFC form before hashing

```clojure
(defn normalize-text [s]
  #?(:cljs (.normalize s "NFC")
     :clj (java.text.Normalizer/normalize s
            java.text.Normalizer$Form/NFC)))

(defn canonical-occlusion-card [card]
  (update card :occlusions
          (partial mapv #(update % :answer normalize-text))))
```

## Alternatives Considered

### 1. Independent Cards per Occlusion
```clojure
;; Each occlusion as separate card
{:type :image-occlusion
 :asset {:id "sha256:..." ...}
 :occlusion {:shape {...} :answer "..."}}
```
**Pros**: Simpler reducer, SM-2 works natively
**Cons**: Duplicative events, hard to edit shared metadata
**Verdict**: ❌ Too much duplication

### 2. SVG Path Strings
```clojure
{:shape {:kind :path
         :d "M 100,200 L 300,400 Z"}}
```
**Pros**: Direct rendering reuse
**Cons**: Fragile hashing (whitespace), absolute coords
**Verdict**: ❌ Normalized maps cleaner

### 3. Absolute Pixel Coordinates
```clojure
{:shape {:kind :rect
         :x 420 :y 180 :w 110 :h 70}}
```
**Pros**: No conversion math
**Cons**: Breaks on resize, device-dependent
**Verdict**: ❌ Normalized coords essential

### 4. Binary Mask Images
```clojure
{:occlusion {:mask-url "data:image/png;base64,..."}}
```
**Pros**: Handles complex shapes, fast masking
**Cons**: Large storage, hard to edit, complex hashing
**Verdict**: ❌ Over-engineered for our use case

## Rendering Strategy

### Canvas Approach
```clojure
(defn render-occlusion [ctx image occlusion revealed?]
  (let [{:keys [width height]} image
        shape (:shape occlusion)
        denorm (fn [pt] {:x (* (:x pt) width)
                        :y (* (:y pt) height)})]
    ;; Draw image
    (.drawImage ctx image 0 0)

    ;; Draw mask if not revealed
    (when-not revealed?
      (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.45)")
      (case (:kind shape)
        :rect (let [{:keys [x y w h]} (denorm-rect shape width height)]
                (.fillRect ctx x y w h))
        :polygon (draw-polygon ctx (mapv denorm (:points shape)))
        :freehand (draw-path ctx (mapv denorm (:path shape)))))))
```

### SVG Approach (Alternative)
```clojure
(defn render-svg [image occlusion revealed?]
  [:svg {:width "100%" :height "100%"
         :viewBox (str "0 0 " (:width image) " " (:height image))}
   [:image {:href (:path image) :width "100%" :height "100%"}]
   (when-not revealed?
     (occlusion-mask occlusion (:width image) (:height image)))])
```

## Implementation Checklist

- [ ] Asset management (hash, path, File System Access API)
- [ ] Shape drawing UI (Canvas/SVG)
- [ ] Shape normalization (0-1 coords)
- [ ] Douglas-Peucker simplification
- [ ] Canonical hashing (exclude visual metadata)
- [ ] Parent-child card expansion in reducer
- [ ] Composite key support in review events
- [ ] Image file handle restoration
- [ ] Shape validation
- [ ] Migration tool for edited images

## Next Steps

1. **Implement asset management**: SHA-256 hashing, File System Access API integration
2. **Build shape editor**: Canvas-based drawing with rect/polygon/freehand
3. **Add reducer logic**: Expand parent cards into virtual children
4. **Update review flow**: Support composite keys `[parent-id occlusion-oid]`
5. **Test edge cases**: File handle expiry, image edits, degenerate shapes
