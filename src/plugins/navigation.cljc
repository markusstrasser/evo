(ns plugins.navigation
  "Navigation plugin: cursor memory for vertical navigation.

   Tracks horizontal cursor position (column) when navigating vertically
   between blocks, enabling Logseq's 'continuous document' feel."
  (:require [kernel.intent :as intent]
            [kernel.query :as q]
            [kernel.navigation :as nav]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; Sentinel for DCE prevention - referenced by spec.runner

;; NOTE: visible-in-context? removed - was dead code with bugs
;; Visibility now computed in kernel/navigation.cljc with proper (db, session)

(defn- get-prev-visible-block
  "Get the previous visible block in DOM order, respecting page boundaries.

   LOGSEQ PARITY: Uses pre-order traversal (DOM order), not sibling order.
   Also respects page scope - won't navigate out of current page."
  [db session block-id]
  (let [target (nav/prev-visible-block db session block-id)]
    ;; Only return target if it's on the same page (or no page scoping)
    (when (and target (q/same-page? db session block-id target))
      target)))

(defn- get-next-visible-block
  "Get the next visible block in DOM order, respecting page boundaries.

   LOGSEQ PARITY: Uses pre-order traversal (DOM order), not sibling order.
   Also respects page scope - won't navigate out of current page."
  [db session block-id]
  (let [target (nav/next-visible-block db session block-id)]
    ;; Only return target if it's on the same page (or no page scoping)
    (when (and target (q/same-page? db session block-id target))
      target)))

(defn- grapheme-count
  "Count graphemes (user-perceived characters) in a string.
   Handles emoji, CJK, and other multi-byte characters correctly."
  [s]
  #?(:cljs
     (try
       (if (and js/Intl js/Intl.Segmenter)
         ;; Use Intl.Segmenter for proper grapheme counting (emoji-aware)
         (let [segmenter (js/Intl.Segmenter. "en" #js {:granularity "grapheme"})
               segments (.segment segmenter s)]
           ;; Convert iterator to array by spreading
           (-> segments
               (js-invoke "values")
               (js/Array.from)
               (.-length)))
         ;; Fallback for older browsers or when Segmenter not available
         (count s))
       (catch js/Error _
         ;; Fallback if Segmenter fails
         (count s)))
     :clj
     ;; CLJ implementation for testing - use Java BreakIterator for grapheme counting
     (let [^java.text.BreakIterator bi (java.text.BreakIterator/getCharacterInstance)]
       (.setText bi s)
       ;; Count by iterating through boundaries
       (loop [num-graphemes 0
              pos (.next bi)]
         (if (= java.text.BreakIterator/DONE pos)
           num-graphemes
           (recur (inc num-graphemes) (.next bi)))))))

(defn get-line-pos
  "Calculate horizontal cursor position within current line.

   Returns grapheme count from start of current line to cursor.
   Handles multi-byte characters (emojis) correctly using JS Intl.Segmenter.

   Example:
     Text: 'hello\\nwo|rld'  (| = cursor)
     Returns: 2  (cursor is 2 chars into 'world' line)"
  [text cursor-pos]
  (let [;; Find the start of the current line
        text-before (subs text 0 cursor-pos)
        last-newline-idx (str/last-index-of text-before "\n")
        line-start (if last-newline-idx (inc last-newline-idx) 0)
        line-text (subs text line-start cursor-pos)]
    (grapheme-count line-text)))

(defn- grapheme-offset-to-char-offset
  "Convert grapheme offset to character offset in a string.
   Example: '👨‍👩‍👧‍👦hello' with grapheme offset 1 returns char offset 7 (after the emoji)"
  [s grapheme-offset]
  #?(:cljs
     (try
       (if (and js/Intl js/Intl.Segmenter)
         (let [segmenter (js/Intl.Segmenter. "en" #js {:granularity "grapheme"})
               segments (.segment segmenter s)
               segment-array (js/Array.from (js-invoke segments "values"))]
           (if (<= grapheme-offset 0)
             0
             (if (>= grapheme-offset (.-length segment-array))
               (count s)
               ;; Get the segment at grapheme-offset and return its start index
               (let [target-segment (aget segment-array grapheme-offset)]
                 (.-index target-segment)))))
         ;; Fallback for older browsers
         (min grapheme-offset (count s)))
       (catch js/Error _
         ;; Fallback if Segmenter fails
         (min grapheme-offset (count s))))
     :clj
     ;; CLJ implementation using Java BreakIterator
     (if (<= grapheme-offset 0)
       0
       (let [^java.text.BreakIterator bi (java.text.BreakIterator/getCharacterInstance)]
         (.setText bi s)
         (loop [idx 0
                pos (.next bi)]
           (cond
             (= pos java.text.BreakIterator/DONE) (count s)
             (>= idx grapheme-offset) pos
             :else (recur (inc idx) (.next bi))))))))

(defn get-target-cursor-pos
  "Calculate where cursor should land in target block.

   LOGSEQ BEHAVIOR (mirrored here):
   - Up navigation → lands on LAST line of target block
   - Down navigation → lands on FIRST line of target block

   Uses grapheme-aware counting for proper emoji/CJK positioning.
   Falls back to end-of-line if target line is shorter.

   Args:
     target-text: Text of target block
     line-pos: Desired column position (from cursor memory, in graphemes)
     direction: :up or :down

   Returns: Cursor position (integer offset into target-text)"
  [target-text line-pos direction]
  (let [up? (= direction :up)
        lines (str/split-lines target-text)
        ;; Select target line based on direction: up → last, down → first
        target-line (str (if up? (last lines) (first lines)))
        ;; Calculate clamped position in target line
        target-line-grapheme-count (grapheme-count target-line)
        target-line-grapheme-pos (min line-pos target-line-grapheme-count)
        target-line-char-pos (grapheme-offset-to-char-offset target-line target-line-grapheme-pos)]
    ;; Calculate absolute position: up → offset from previous lines, down → position in first line
    (if up?
      (transduce (map #(inc (count %))) + target-line-char-pos (butlast lines))
      target-line-char-pos)))

(intent/register-intent! :navigate-with-cursor-memory
                         {:doc "Navigate to adjacent block, preserving cursor column position.

         This is the PRIMARY navigation intent while editing.
         Replaces simple :selection :mode :prev/:next when cursor memory is desired."

                          :fr/ids #{:fr.nav/vertical-cursor-memory}
                          :allowed-states #{:editing}

                          :spec [:map
                                 [:type [:= :navigate-with-cursor-memory]]
                                 [:direction [:enum :up :down]]
                                 [:current-block-id :string]
                                 [:current-text :string]
                                 [:current-cursor-pos :int]]
                          :handler
                          (fn [db session {:keys [direction current-block-id current-text current-cursor-pos]}]
                            (let [;; Calculate and store cursor memory
                                  line-pos (get-line-pos current-text current-cursor-pos)

                                  ;; Find target block (fold/zoom/page-aware)
                                  target-id (case direction
                                              :up (get-prev-visible-block db session current-block-id)
                                              :down (get-next-visible-block db session current-block-id))]

                              ;; BOUNDARY HANDLING: If no target, return no-op that keeps cursor in place
                              ;; This prevents exceptions at document edges (Logseq behavior)
                              (if-not target-id
                                ;; No target block - stay in current block with cursor at boundary
                                {:session-updates
                                 {:ui {:editing-block-id current-block-id
                                       :cursor-position (if (= direction :up) 0 (count current-text))}}}

                                ;; Target exists - navigate to it
                                (let [target-text (q/block-text db target-id)

                                      ;; Calculate target cursor position
                                      target-pos (get-target-cursor-pos target-text line-pos direction)

                                      ;; Clamp to valid range (always numeric - no :end/:start keywords)
                                      cursor-at (max 0 (min target-pos (count target-text)))]

                                  ;; Return session-updates (ephemeral UI state)
                                  {:session-updates
                                   {:ui {:cursor-memory {:line-pos line-pos
                                                         :last-block-id current-block-id
                                                         :direction direction}
                                         :editing-block-id target-id
                                         :cursor-position cursor-at}}}))))})

(intent/register-intent! :navigate-to-adjacent
                         {:doc "Navigate to adjacent block (for left/right arrows at boundaries).

         Simpler than :navigate-with-cursor-memory - doesn't preserve column.
         Just enters adjacent block at specified position."
                          :fr/ids #{:fr.nav/horizontal-boundary}
                          :allowed-states #{:editing}
                          :spec [:map
                                 [:type [:= :navigate-to-adjacent]]
                                 [:direction [:enum :up :down]]
                                 [:current-block-id :string]
                                 [:cursor-position [:or :int [:enum :max]]]]
                          :handler
                          (fn [db session {:keys [direction current-block-id cursor-position]}]
                            (let [target-id (case direction
                                              :up (q/visible-prev-block db session current-block-id)
                                              :down (q/visible-next-block db session current-block-id))]
                              (when target-id
                                (let [target-text (q/block-text db target-id)
                                      actual-pos (if (= cursor-position :max)
                                                   (count target-text)
                                                   cursor-position)]
                                  {:session-updates
                                   {:ui {:editing-block-id target-id
                                         :cursor-position actual-pos}}}))))})

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
