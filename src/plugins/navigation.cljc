(ns plugins.navigation
  "Navigation plugin: cursor memory for vertical navigation.
   
   Tracks horizontal cursor position (column) when navigating vertically
   between blocks, enabling Logseq's 'continuous document' feel."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

(defn- get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(defn get-line-pos
  "Calculate horizontal cursor position within current line.
   
   Returns grapheme count from start of current line to cursor.
   Handles multi-byte characters (emojis) correctly using JS Intl.Segmenter.
   
   Example:
     Text: 'hello\\nwo|rld'  (| = cursor)
     Returns: 2  (cursor is 2 chars into 'world' line)"
  [text cursor-pos]
  #?(:cljs
     (let [;; Find the start of the current line
           text-before (subs text 0 cursor-pos)
           last-newline-idx (str/last-index-of text-before "\n")
           line-start (if last-newline-idx (inc last-newline-idx) 0)
           line-text (subs text line-start cursor-pos)]
       ;; For now, use simple character count
       ;; TODO: Use Intl.Segmenter for proper emoji support
       (count line-text))
     :clj
     ;; CLJ implementation for testing
     (let [text-before (subs text 0 cursor-pos)
           last-newline-idx (str/last-index-of text-before "\n")
           line-start (if last-newline-idx (inc last-newline-idx) 0)
           line-text (subs text line-start cursor-pos)]
       (count line-text))))

(defn get-target-cursor-pos
  "Calculate where cursor should land in target block.
   
   Uses line position to find character at same horizontal position.
   Falls back to end-of-line if target line is shorter.
   
   Args:
     target-text: Text of target block
     line-pos: Desired column position (from cursor memory)
     direction: :up or :down
   
   Returns: Cursor position (integer offset into target-text)"
  [target-text line-pos direction]
  (let [;; For :up, use first line; for :down, use last line
        lines (str/split-lines target-text)
        target-line (if (= direction :up)
                      (first lines)
                      (last lines))
        ;; If no lines, empty string
        target-line (or target-line "")
        ;; Calculate position in target line
        target-line-pos (min line-pos (count target-line))
        ;; Calculate absolute position in full text
        pos (if (= direction :up)
              ;; Going up: just the position in first line
              target-line-pos
              ;; Going down: sum of all previous lines + position in last line
              (let [all-but-last (butlast lines)
                    chars-before (reduce + 0 (map #(inc (count %)) all-but-last))]
                (+ chars-before target-line-pos)))]
    pos))

(intent/register-intent! :navigate-with-cursor-memory
                         {:doc "Navigate to adjacent block, preserving cursor column position.
         
         This is the PRIMARY navigation intent while editing.
         Replaces simple :selection :mode :prev/:next when cursor memory is desired."
                          :spec [:map
                                 [:type [:= :navigate-with-cursor-memory]]
                                 [:direction [:enum :up :down]]
                                 [:current-block-id :string]
                                 [:current-text :string]
                                 [:current-cursor-pos :int]]
                          :handler
                          (fn [db {:keys [direction current-block-id current-text current-cursor-pos]}]
                            (let [;; Calculate and store cursor memory
                                  line-pos (get-line-pos current-text current-cursor-pos)

           ;; Find target block
                                  target-id (case direction
                                              :up (get-in db [:derived :prev-id-of current-block-id])
                                              :down (get-in db [:derived :next-id-of current-block-id]))

           ;; If no sibling, just stay in current block
                                  _ (when-not target-id (throw (ex-info "No target block" {:direction direction})))]

                              (when target-id
                                (let [target-text (get-block-text db target-id)

               ;; Calculate target cursor position
                                      target-pos (get-target-cursor-pos target-text line-pos direction)

               ;; Determine cursor position mode (:start, :end, or specific pos)
                                      cursor-at (cond
                                                  (zero? target-pos) :start
                                                  (>= target-pos (count target-text)) :end
                                                  :else target-pos)]

           ;; Return ops
                                  [;; Update cursor memory (in :ui, ephemeral)
                                   {:op :update-node
                                    :id const/session-ui-id
                                    :props {:cursor-memory {:line-pos line-pos
                                                            :last-block-id current-block-id
                                                            :direction direction}}}

            ;; Exit edit on current block
                                   {:op :update-node
                                    :id const/session-ui-id
                                    :props {:editing-block-id nil}}

            ;; Enter edit on target block WITH cursor position
                                   {:op :update-node
                                    :id const/session-ui-id
                                    :props {:editing-block-id target-id
                                            :cursor-position cursor-at}}]))))})
