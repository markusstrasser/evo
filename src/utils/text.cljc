(ns utils.text
  "Text manipulation utilities with Unicode grapheme cluster support.

   Uses Intl.Segmenter (native browser API) for proper grapheme segmentation.
   Handles complex emoji sequences like ZWJ families, skin tones, and flags.
   Falls back to surrogate pair detection for older browsers.

   Browser support for Intl.Segmenter:
   - Chrome 87+, Edge 87+, Safari 14.1+, Firefox 125+")

;; ── Grapheme Cluster Handling ────────────────────────────────────────────────

;; ── Intl.Segmenter Support ───────────────────────────────────────────────────

#?(:cljs
   (def ^:private segmenter-supported?
     "Check if Intl.Segmenter is available (modern browsers)."
     (and (exists? js/Intl)
          (exists? js/Intl.Segmenter))))

#?(:cljs
   (def ^:private grapheme-segmenter
     "Lazy-initialized grapheme segmenter instance."
     (when segmenter-supported?
       (js/Intl.Segmenter. "en" #js {:granularity "grapheme"}))))

(defn split-graphemes
  "Split string into grapheme clusters (visual characters).

   Uses Intl.Segmenter when available for proper Unicode support.
   Handles complex sequences:
   - ZWJ families: 👨‍👩‍👧 (1 grapheme, 8 code units)
   - Skin tones: 👋🏽 (1 grapheme, 4 code units)
   - Flags: 🇺🇸 (1 grapheme, 4 code units)

   Falls back to surrogate pair detection on older browsers.

   Example:
     (split-graphemes \"Hi👋🏽\") => [\"H\" \"i\" \"👋🏽\"]"
  [text]
  #?(:cljs
     (if (and segmenter-supported? grapheme-segmenter)
       ;; Use Intl.Segmenter for proper grapheme clusters
       ;; .segment() returns a Segments iterable - use Array.from()
       (let [segments (.segment grapheme-segmenter text)
             arr (js/Array.from segments)]
         (vec (map #(.-segment %) arr)))
       ;; Fallback: simple surrogate pair detection
       (loop [i 0
              result []]
         (if (>= i (.-length text))
           result
           (let [char-code (.charCodeAt text i)]
             (if (and (>= char-code 0xD800) (<= char-code 0xDBFF)
                      (< (inc i) (.-length text)))
               ;; High surrogate - take 2 code units
               (recur (+ i 2) (conj result (.substring text i (+ i 2))))
               ;; Regular char
               (recur (inc i) (conj result (.substring text i (inc i)))))))))
     :clj
     ;; JVM: Use codepoints for basic support
     (let [codepoints (.codePoints text)]
       (vec (map #(String. (Character/toChars %))
                 (iterator-seq (.iterator codepoints)))))))

(defn grapheme-index-to-cursor-pos
  "Convert grapheme index to UTF-16 cursor position.

   Inverse of cursor-pos-to-grapheme-index.
   Useful for placing cursor after N visual characters.

   Example:
     text: \"Hi👋🏽there\"
     grapheme-idx: 3  => 6 (after 👋🏽, which is 4 code units)"
  [text grapheme-idx]
  (let [graphemes (split-graphemes text)
        clamped-idx (min grapheme-idx (count graphemes))]
    (reduce + 0 (map count (take clamped-idx graphemes)))))

;; ── Legacy Grapheme Helpers ──────────────────────────────────────────────────
;; These provide backwards compatibility with simpler surrogate-pair detection.
;; Prefer split-graphemes for new code.

(defn grapheme-length-at
  "Get UTF-16 length of grapheme cluster at cursor position.

   Uses Intl.Segmenter when available for proper Unicode support.
   Essential for cursor movement to skip entire visual characters.

   Example:
     text: \"Hi👋🏽World\"
     pos: 2  => 4  (👋🏽 is 4 UTF-16 code units)

   Returns: Integer (UTF-16 code units for grapheme at pos)"
  [text pos]
  (let [graphemes (split-graphemes text)]
    ;; Find which grapheme contains this position
    (loop [g-idx 0
           utf16-pos 0]
      (if (>= g-idx (count graphemes))
        1 ;; Past end, return 1
        (let [g (nth graphemes g-idx)
              g-len (count g)]
          (if (and (>= pos utf16-pos) (< pos (+ utf16-pos g-len)))
            g-len ;; Found the grapheme containing pos
            (recur (inc g-idx) (+ utf16-pos g-len))))))))

(defn count-graphemes
  "Count grapheme clusters in string (visual characters, not code units).

   Uses Intl.Segmenter when available for proper Unicode support.
   Falls back to surrogate pair counting on older browsers.

   Example:
     (count-graphemes \"Hi👋🏽\") => 3  (H, i, 👋🏽)"
  [text]
  (count (split-graphemes text)))

(defn cursor-pos-to-grapheme-index
  "Convert UTF-16 cursor position to grapheme index.

   Uses Intl.Segmenter when available for proper Unicode support.
   Useful for cursor positioning that respects complex emoji.

   Example:
     text: \"Hi👋🏽there\"
     cursor-pos: 6  => 3  (after 👋🏽)"
  [text cursor-pos]
  (let [graphemes (split-graphemes text)]
    (loop [g-idx 0
           utf16-pos 0]
      (cond
        (>= utf16-pos cursor-pos) g-idx
        (>= g-idx (count graphemes)) g-idx
        :else (recur (inc g-idx)
                     (+ utf16-pos (count (nth graphemes g-idx))))))))

;; ── Word Boundary Detection ──────────────────────────────────────────────────

(defn whitespace?
  "Check if character is whitespace (space, newline, or tab)."
  [c]
  (or (= c \space)
      (= c \newline)
      (= c \tab)
      (= c \return)))

(defn- skip-while-forward
  "Skip forward while predicate holds, starting from pos.
   Returns final position (stops at text length if predicate always holds)."
  [text pos pred]
  (let [len (count text)]
    (loop [p pos]
      (if (and (< p len)
               (pred (nth text p)))
        (recur (inc p))
        p))))

(defn find-next-word-boundary
  "Find position of start of next word.

   Args:
     text: String
     pos: Current cursor position

   Returns: Integer (position of next word start)

   Examples:
     (find-next-word-boundary \"hello world\" 0)  => 6
     (find-next-word-boundary \"hello   world\" 5) => 8
     (find-next-word-boundary \"hello\" 5)  => 5 (at end)"
  [text pos]
  (let [after-word (skip-while-forward text pos (complement whitespace?))
        after-spaces (skip-while-forward text after-word whitespace?)]
    after-spaces))

(defn find-word-end
  "Find position at end of current word (before trailing whitespace).

   Unlike find-next-word-boundary which skips whitespace to next word start,
   this stops immediately after the current word ends.

   Args:
     text: String
     pos: Current cursor position

   Returns: Integer (position at word end)

   Examples:
     (find-word-end \"hello world\" 2)  => 5  (after 'hello')
     (find-word-end \"hello world\" 0)  => 5  (after 'hello')
     (find-word-end \"hello\" 0)        => 5  (at end)
     (find-word-end \"  hello\" 0)      => 0  (already at whitespace)"
  [text pos]
  (skip-while-forward text pos (complement whitespace?)))

(defn- skip-while-backward
  "Skip backward while predicate holds, starting from (dec pos).
   Returns final position (may be negative if predicate always holds)."
  [text pos pred]
  (loop [p (dec pos)]
    (if (and (>= p 0)
             (pred (nth text p)))
      (recur (dec p))
      p)))

(defn find-prev-word-boundary
  "Find position of start of previous word.

   Args:
     text: String
     pos: Current cursor position

   Returns: Integer (position of previous word start) or nil if at start

   Examples:
     (find-prev-word-boundary \"hello world\" 11) => 6
     (find-prev-word-boundary \"hello   world\" 8) => 0
     (find-prev-word-boundary \"hello\" 0) => nil"
  [text pos]
  (when (pos? pos)
    (let [;; Move back one if currently at space
          start-pos (cond-> pos
                      (and (< pos (count text))
                           (whitespace? (nth text (dec pos))))
                      dec)
          ;; Skip trailing spaces, then word chars
          after-spaces (skip-while-backward text start-pos whitespace?)
          after-word (skip-while-backward text after-spaces (complement whitespace?))]
      (max 0 (inc after-word)))))
