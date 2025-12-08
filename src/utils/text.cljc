(ns utils.text
  "Text manipulation utilities with multi-byte character support.

   NOTE: This is a simplified implementation. For full emoji/grapheme cluster support,
   install grapheme-splitter library: npm install grapheme-splitter")

;; ── Grapheme Cluster Handling ────────────────────────────────────────────────

(defn grapheme-length-at
  "Get length of grapheme cluster at position (handles basic emoji, CJK).

   Simplified implementation without grapheme-splitter library.
   Handles basic emoji (surrogate pairs) but not complex clusters.

   Example:
     text: 'Hello😀World'
     pos: 5
     => 2  (emoji takes 2 UTF-16 code units)

   Returns: Integer (1 for ASCII, 2 for basic emoji/CJK)

   TODO: Use grapheme-splitter library for full Unicode correctness:
         npm install grapheme-splitter
         Then use GraphemeSplitter.splitGraphemes()"
  [text pos]
  #?(:cljs
     ;; Simple surrogate pair detection
     (let [char-code (.charCodeAt text pos)]
       (if (and (>= char-code 0xD800) (<= char-code 0xDBFF))
         2  ; High surrogate - emoji/CJK likely 2 code units
         1))
     :clj
     ;; On JVM, use Character.charCount
     (if (< pos (count text))
       (let [code-point (.codePointAt text (int pos))]
         (Character/charCount code-point))
       1)))

(defn count-graphemes
  "Count grapheme clusters in string (not UTF-16 code units).

   Simplified implementation - counts surrogate pairs as 1 grapheme.

   Example:
     text: 'Hi😀'
     => 3  (not 4)

   TODO: Use grapheme-splitter for full correctness"
  [text]
  #?(:cljs
     ;; Simple counting - treat surrogate pairs as 1
     (loop [i 0
            grapheme-count 0]
       (if (>= i (.-length text))
         grapheme-count
         (let [char-code (.charCodeAt text i)]
           (if (and (>= char-code 0xD800) (<= char-code 0xDBFF))
             (recur (+ i 2) (inc grapheme-count))  ; Skip surrogate pair
             (recur (inc i) (inc grapheme-count))))))
     :clj
     (.codePointCount text 0 (count text))))

(defn cursor-pos-to-grapheme-index
  "Convert UTF-16 cursor position to grapheme index.

   Useful for cursor positioning that respects emoji.

   Simplified implementation using surrogate pair detection.

   TODO: Use grapheme-splitter for full correctness"
  [text cursor-pos]
  #?(:cljs
     (loop [i 0
            grapheme-idx 0]
       (if (>= i cursor-pos)
         grapheme-idx
         (let [char-code (.charCodeAt text i)]
           (if (and (>= char-code 0xD800) (<= char-code 0xDBFF))
             (recur (+ i 2) (inc grapheme-idx))  ; Surrogate pair
             (recur (inc i) (inc grapheme-idx))))))
     :clj
     (loop [idx 0
            utf16-pos 0]
       (if (>= utf16-pos cursor-pos)
         idx
         (if (< utf16-pos (count text))
           (let [code-point (.codePointAt text (int utf16-pos))
                 char-count (Character/charCount code-point)]
             (recur (inc idx) (+ utf16-pos char-count)))
           idx)))))

;; ── Word Boundary Detection ──────────────────────────────────────────────────

(defn whitespace?
  "Check if character is whitespace (space, newline, or tab)."
  [c]
  (or (= c \space)
      (= c \newline)
      (= c \tab)
      (= c \return)))

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
  (let [len (count text)
        ;; Skip current word (non-whitespace chars)
        skip-current (loop [p pos]
                      (if (and (< p len)
                              (not (whitespace? (nth text p))))
                        (recur (inc p))
                        p))
        ;; Skip whitespace
        skip-spaces (loop [p skip-current]
                     (if (and (< p len)
                             (whitespace? (nth text p)))
                       (recur (inc p))
                       p))]
    skip-spaces))

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
  (let [len (count text)]
    ;; Skip non-whitespace (current word) only
    (loop [p pos]
      (if (and (< p len)
               (not (whitespace? (nth text p))))
        (recur (inc p))
        p))))

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
    (let [;; Move back one if at space
          start-pos (if (and (< pos (count text))
                            (pos? pos)
                            (whitespace? (nth text (dec pos))))
                     (dec pos)
                     pos)
          ;; Skip spaces backward
          skip-spaces (loop [p (dec start-pos)]
                       (if (and (>= p 0)
                               (whitespace? (nth text p)))
                         (recur (dec p))
                         p))
          ;; Skip word backward
          skip-word (loop [p skip-spaces]
                     (if (and (>= p 0)
                             (not (whitespace? (nth text p))))
                       (recur (dec p))
                       p))]
      (max 0 (inc skip-word)))))
