(ns utils.image
  "Image processing utilities.

   Handles:
   - EXIF orientation reading and auto-rotation
   - Image dimension extraction
   - Canvas-based image transformations")

;; ── EXIF Orientation ─────────────────────────────────────────────────────────
;; JPEG files store orientation in EXIF tag 0x0112
;; Values 1-8 indicate rotation/flip needed

(defn- read-exif-orientation
  "Read EXIF orientation from JPEG ArrayBuffer.
   Returns orientation 1-8, or 1 if not found/not JPEG."
  [array-buffer]
  (let [view (js/DataView. array-buffer)]
    (if (not= (.getUint16 view 0 false) 0xFFD8)
      ;; Not a JPEG
      1
      ;; Parse JPEG markers looking for EXIF (0xFFE1)
      (let [length (.-byteLength view)]
        (loop [offset 2]
          (if (>= offset length)
            1 ;; No EXIF found
            (let [marker (.getUint16 view offset false)]
              (cond
                ;; Found EXIF marker
                (= marker 0xFFE1)
                (if (not= (.getUint32 view (+ offset 2) false) 0x45786966)
                  1 ;; Not actually EXIF
                  (let [little-endian? (= (.getUint16 view (+ offset 8) false) 0x4949)
                        ifd-offset (+ offset 8 (.getUint32 view (+ offset 12) little-endian?))
                        tag-count (.getUint16 view ifd-offset little-endian?)]
                    ;; Search tags for orientation (0x0112)
                    (loop [i 0]
                      (if (>= i tag-count)
                        1 ;; Orientation tag not found
                        (let [tag-offset (+ ifd-offset 2 (* i 12))]
                          (if (= (.getUint16 view tag-offset little-endian?) 0x0112)
                            (.getUint16 view (+ tag-offset 8) little-endian?)
                            (recur (inc i))))))))

                ;; Invalid marker - stop
                (not= (bit-and marker 0xFF00) 0xFF00)
                1

                ;; Skip this marker
                :else
                (recur (+ offset 2 (.getUint16 view (+ offset 2) false)))))))))))

(defn- orientation-swaps-dimensions?
  "Returns true if orientation requires swapping width/height."
  [orientation]
  (contains? #{5 6 7 8} orientation))

(defn- apply-orientation-transform!
  "Apply canvas transform for given EXIF orientation."
  [ctx width height orientation]
  (case orientation
    2 (.transform ctx -1  0  0  1 width  0)
    3 (.transform ctx -1  0  0 -1 width  height)
    4 (.transform ctx  1  0  0 -1 0      height)
    5 (.transform ctx  0  1  1  0 0      0)
    6 (.transform ctx  0  1 -1  0 height 0)
    7 (.transform ctx  0 -1 -1  0 height width)
    8 (.transform ctx  0 -1  1  0 0      width)
    ;; Default (1 or unknown) - no transform
    (.transform ctx  1  0  0  1 0      0)))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn get-orientation
  "Get EXIF orientation from a File/Blob.
   Returns Promise<number> (1-8, or 1 if not found)."
  [file]
  (js/Promise.
   (fn [on-resolve _reject]
     (let [reader (js/FileReader.)]
       (set! (.-onload reader)
             (fn [e]
               (let [orientation (read-exif-orientation (.-result (.-target e)))]
                 (on-resolve orientation))))
       (set! (.-onerror reader)
             (fn [_] (on-resolve 1))) ;; Default to no rotation on error
       ;; Only read first 64KB (enough for EXIF header)
       (.readAsArrayBuffer reader (.slice file 0 65536))))))

(defn fix-orientation
  "Fix EXIF orientation by redrawing image on canvas.
   Returns Promise<Blob> with corrected image.

   If orientation is 1 (normal) or image type doesn't support EXIF,
   returns the original file unchanged."
  [file]
  (-> (get-orientation file)
      (.then
       (fn [orientation]
         (if (= orientation 1)
           ;; No rotation needed
           (js/Promise.resolve file)
           ;; Need to rotate - load image and redraw
           (js/Promise.
            (fn [on-resolve on-reject]
              (let [url (js/URL.createObjectURL file)
                    img (js/Image.)]
                (set! (.-onload img)
                      (fn []
                        (let [width (.-naturalWidth img)
                              height (.-naturalHeight img)
                              ;; Canvas dimensions may swap for 90° rotations
                              [canvas-w canvas-h] (if (orientation-swaps-dimensions? orientation)
                                                    [height width]
                                                    [width height])
                              canvas (js/document.createElement "canvas")
                              ctx (.getContext canvas "2d")]
                          (set! (.-width canvas) canvas-w)
                          (set! (.-height canvas) canvas-h)
                          ;; Apply transform and draw
                          (apply-orientation-transform! ctx width height orientation)
                          (let [[draw-w draw-h] (if (orientation-swaps-dimensions? orientation)
                                                  [height width]
                                                  [width height])]
                            (.drawImage ctx img 0 0 draw-w draw-h))
                          ;; Convert to blob
                          (.toBlob canvas
                                   (fn [blob]
                                     (js/URL.revokeObjectURL url)
                                     (on-resolve blob))
                                   (.-type file)
                                   0.92)))) ;; Quality for JPEG
                (set! (.-onerror img)
                      (fn []
                        (js/URL.revokeObjectURL url)
                        (on-reject (js/Error. "Failed to load image"))))
                (set! (.-src img) url)))))))))

(defn get-dimensions
  "Get image dimensions from a File/Blob.
   Returns Promise<{:width :height}>."
  [file]
  (js/Promise.
   (fn [on-resolve on-reject]
     (let [url (js/URL.createObjectURL file)
           img (js/Image.)]
       (set! (.-onload img)
             (fn []
               (js/URL.revokeObjectURL url)
               (on-resolve {:width (.-naturalWidth img)
                            :height (.-naturalHeight img)})))
       (set! (.-onerror img)
             (fn []
               (js/URL.revokeObjectURL url)
               (on-reject (js/Error. "Failed to load image"))))
       (set! (.-src img) url)))))

(defn process-image-for-upload
  "Process an image file for upload:
   1. Fix EXIF orientation
   2. Extract dimensions
   Returns Promise<{:blob :width :height}>."
  [file]
  (-> (fix-orientation file)
      (.then (fn [fixed-blob]
               (-> (get-dimensions fixed-blob)
                   (.then (fn [{:keys [width height]}]
                            {:blob fixed-blob
                             :width width
                             :height height})))))))
