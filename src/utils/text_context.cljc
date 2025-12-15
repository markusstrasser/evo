(ns utils.text-context
  "Context detection for cursor position in text.

   Detects what the cursor is inside (markup, refs, code blocks, etc.)
   to enable context-aware editing behaviors.

   Based on TEXT_EDITING_BEHAVIORS_SPEC.md Component 1."
  (:require #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; ── Context Types ─────────────────────────────────────────────────────────────

(def context-types
  "All possible context types the cursor can be in."
  #{:markup ; **bold**, __italic__, ~~strike~~, ^^highlight^^
    :code-block ; ```lang\n...\n```
    :admonition ; #+BEGIN_NOTE ... #+END
    :page-ref ; [[page name]]
    :property-drawer ; :PROPERTIES:\n:key: value\n:END:
    :list-item ; - item, * item, 1. item
    :checkbox ; - [ ] task, - [x] done
    :none}) ; Plain text

;; ── Pattern Matchers ──────────────────────────────────────────────────────────

(def markup-patterns
  "Markup patterns with their types."
  [{:marker "**" :type :bold}
   {:marker "__" :type :italic}
   {:marker "~~" :type :strike}
   {:marker "^^" :type :highlight}])

;; ── Helper Functions ──────────────────────────────────────────────────────────

(defn- find-line-start
  "Find start position of line containing cursor-pos."
  [text cursor-pos]
  (if (zero? cursor-pos)
    0
    (let [before (subs text 0 cursor-pos)
          last-newline (str/last-index-of before "\n")]
      (if last-newline
        (inc last-newline)
        0))))

(defn- find-line-end
  "Find end position of line containing cursor-pos."
  [text cursor-pos]
  (let [after (subs text cursor-pos)
        next-newline (str/index-of after "\n")]
    (if next-newline
      (+ cursor-pos next-newline)
      (count text))))

(defn- find-previous-marker
  "Search backwards from cursor-pos for marker.
   Returns {:pos position} or nil if not found."
  [text cursor-pos marker]
  (let [before (subs text 0 cursor-pos)
        pos (str/last-index-of before marker)]
    (when pos
      {:pos pos})))

(defn- find-next-marker
  "Search forwards from cursor-pos for marker.
   Returns {:pos position} or nil if not found."
  [text cursor-pos marker]
  (let [after (subs text cursor-pos)
        pos (str/index-of after marker)]
    (when pos
      {:pos (+ cursor-pos pos)})))

(defn- find-enclosing-pair-simple
  "Simple pair detection for symmetric markers (e.g., ** -> **).
   Searches backwards for opening, forwards for closing."
  [text cursor-pos marker]
  (let [marker-len (count marker)
        opening (find-previous-marker text cursor-pos marker)
        closing (find-next-marker text cursor-pos marker)]
    (when (and opening closing
               (< (:pos opening) cursor-pos)
               (<= cursor-pos (:pos closing)))
      {:start (:pos opening)
       :end (+ (:pos closing) marker-len)
       :inner-start (+ (:pos opening) marker-len)
       :inner-end (:pos closing)
       :complete? true})))

(defn- find-enclosing-pair-nested
  "Nested pair detection for asymmetric markers (e.g., [[ -> ]]).
   Tracks nesting depth to find the innermost enclosing pair.

   Algorithm:
   1. Scan backwards from cursor, tracking depth (+1 for closing, -1 for opening)
   2. When depth hits 0 at an opening marker, that's our match
   3. Scan forwards from cursor similarly
   4. Validate the inner content doesn't contain unbalanced markers (Logseq parity)"
  [text cursor-pos opening-marker closing-marker]
  (let [text-len (count text)
        open-len (count opening-marker)
        close-len (count closing-marker)

        ;; Search backwards for opening with depth tracking
        find-opening
        (fn []
          (loop [pos (dec cursor-pos)
                 depth 0]
            (when (>= pos 0)
              (cond
                ;; Found closing marker going backwards = increase depth
                (and (>= (+ pos close-len) 0)
                     (<= (+ pos close-len) text-len)
                     (= closing-marker (subs text pos (min text-len (+ pos close-len)))))
                (recur (dec pos) (inc depth))

                ;; Found opening marker going backwards
                (and (>= (+ pos open-len) 0)
                     (<= (+ pos open-len) text-len)
                     (= opening-marker (subs text pos (min text-len (+ pos open-len)))))
                (if (zero? depth)
                  pos ; Found our opening!
                  (recur (dec pos) (dec depth)))

                :else
                (recur (dec pos) depth)))))

        ;; Search forwards for closing with depth tracking
        find-closing
        (fn []
          (loop [pos cursor-pos
                 depth 0]
            (when (< pos text-len)
              (cond
                ;; Found opening marker going forwards = increase depth
                (and (<= (+ pos open-len) text-len)
                     (= opening-marker (subs text pos (+ pos open-len))))
                (recur (+ pos open-len) (inc depth))

                ;; Found closing marker going forwards
                (and (<= (+ pos close-len) text-len)
                     (= closing-marker (subs text pos (+ pos close-len))))
                (if (zero? depth)
                  pos ; Found our closing!
                  (recur (+ pos close-len) (dec depth)))

                :else
                (recur (inc pos) depth)))))

        opening-pos (find-opening)
        closing-pos (find-closing)]

    (when (and opening-pos closing-pos
               (< opening-pos cursor-pos)
               (<= cursor-pos closing-pos))
      {:start opening-pos
       :end (+ closing-pos close-len)
       :inner-start (+ opening-pos open-len)
       :inner-end closing-pos
       :complete? true})))

(defn- find-enclosing-pair
  "Find enclosing pair of markers around cursor position.

   Example:
     text: 'hello **world** test'
     cursor-pos: 10 (inside 'world')
     opening-marker: '**'
     closing-marker: '**' (or nil to use opening-marker)
     => {:start 6 :end 15 :inner-start 8 :inner-end 13 :complete? true}

   For asymmetric markers like [[/]], handles nested structures correctly:
     text: '[[outer [[inner]] rest]]'
     cursor-pos: 12 (inside 'inner')
     => Returns bounds of [[inner]], not [[outer...]]

   Returns nil if not found or cursor not inside pair."
  ([text cursor-pos marker]
   (find-enclosing-pair-simple text cursor-pos marker))
  ([text cursor-pos opening-marker closing-marker]
   (if (= opening-marker closing-marker)
     ;; Symmetric markers can't nest, use simple search
     (find-enclosing-pair-simple text cursor-pos opening-marker)
     ;; Asymmetric markers need depth tracking
     (find-enclosing-pair-nested text cursor-pos opening-marker closing-marker))))

;; ── Core Detection Functions ──────────────────────────────────────────────────

(defn detect-markup-at-cursor
  "Detect if cursor is inside markup like **bold** or __italic__.

   Handles:
   - **text** (bold)
   - __text__ (italic)
   - ~~text~~ (strikethrough)
   - ^^text^^ (highlight)

   Returns context map or nil if not in markup."
  [text cursor-pos]
  (some (fn [{:keys [marker type]}]
          (when-let [bounds (find-enclosing-pair text cursor-pos marker)]
            (assoc bounds :type :markup :marker marker :markup-type type)))
        markup-patterns))

(defn detect-page-ref-at-cursor
  "Detect if cursor is inside [[page-ref]].

   Example:
     text: 'Link to [[My Page]] here'
     cursor-pos: 12 (inside 'My Page')
     => {:type :page-ref :start 8 :end 19 :page-name 'My Page'}"
  [text cursor-pos]
  (when-let [bounds (find-enclosing-pair text cursor-pos "[[" "]]")]
    (let [inner-text (subs text (:inner-start bounds) (:inner-end bounds))]
      (assoc bounds
             :type :page-ref
             :page-name inner-text))))

(defn detect-code-block-at-cursor
  "Detect if cursor is inside ```code block```.

   Example:
     text: '```clojure\\n(+ 1 2)\\n```'
     cursor-pos: 15 (inside code)
     => {:type :code-block :lang 'clojure' :start 0 :end 24}"
  [text cursor-pos]
  (let [lines (str/split text #"\n")
        ;; Find which line contains cursor
        cursor-line-idx (loop [idx 0
                               pos 0]
                          (if (>= idx (count lines))
                            nil
                            (let [line-len (count (nth lines idx))
                                  line-end (+ pos line-len)]
                              (if (<= pos cursor-pos line-end)
                                idx
                                (recur (inc idx) (inc line-end)))))) ; +1 for \n

        ;; Search backwards for opening ```
        open-idx (when cursor-line-idx
                   (loop [idx cursor-line-idx]
                     (when (>= idx 0)
                       (if (str/starts-with? (nth lines idx) "```")
                         idx
                         (recur (dec idx))))))

        ;; Search forwards for closing ```
        close-idx (when cursor-line-idx
                    (loop [idx (inc cursor-line-idx)]
                      (when (< idx (count lines))
                        (if (str/starts-with? (nth lines idx) "```")
                          idx
                          (recur (inc idx))))))]

    (when (and open-idx close-idx)
      (let [open-line (nth lines open-idx)
            lang (str/trim (subs open-line 3)) ; Extract language after ```
            ;; Calculate positions
            start-pos (loop [idx 0 pos 0]
                        (if (= idx open-idx)
                          pos
                          (recur (inc idx) (+ pos (count (nth lines idx)) 1))))
            end-pos (loop [idx 0 pos 0]
                      (if (= idx (inc close-idx))
                        (dec pos) ; Don't include final newline
                        (recur (inc idx) (+ pos (count (nth lines idx)) 1))))]
        {:type :code-block
         :lang (if (str/blank? lang) nil lang)
         :start start-pos
         :end end-pos
         :inner-start (+ start-pos (count open-line) 1)
         :inner-end (- end-pos (count (nth lines close-idx)) 1)}))))

(defn detect-list-item-at-cursor
  "Detect if cursor is on a line starting with list marker.

   Handles:
   - - item (dash)
   - * item (asterisk)
   - + item (plus)
   - 1. item (numbered)
   - [ ] task (checkbox unchecked)
   - [x] task (checkbox checked)

   Returns:
     {:type :list-item :marker '- ' :start 0 :checkbox? false}
     or {:type :checkbox :marker '- [ ]' :checked? false}"
  [text cursor-pos]
  (let [line-start (find-line-start text cursor-pos)
        line-end (find-line-end text cursor-pos)
        line-text (subs text line-start line-end)

        ;; Check for checkbox first (more specific)
        checkbox-match (re-matches #"^([-*+])\s+(\[[ xX]\])\s+(.*)$" line-text)
        ;; Then check for numbered list
        numbered-match (re-matches #"^(\d+)\.\s+(.*)$" line-text)
        ;; Finally check for simple list
        list-match (re-matches #"^([-*+])\s+(.*)$" line-text)]

    (cond
      checkbox-match
      (let [[_ bullet checkbox content] checkbox-match
            checked? (not= (str/trim checkbox) "[ ]")]
        {:type :checkbox
         :marker (str bullet " " checkbox)
         :checked? checked?
         :start line-start
         :content content})

      numbered-match
      (let [[_ number content] numbered-match]
        {:type :list-item
         :marker (str number ". ")
         :numbered? true
         :number #?(:clj (Integer/parseInt number)
                    :cljs (js/parseInt number))
         :start line-start
         :content content})

      list-match
      (let [[_ bullet content] list-match]
        {:type :list-item
         :marker (str bullet " ")
         :numbered? false
         :start line-start
         :content content})

      :else nil)))

(defn context-at-cursor
  "Detect what context the cursor is within.

   Returns context map or {:type :none} if in plain text.

   Priority order (higher priority first):
   1. Code blocks (contain literal text, markup inside is not active)
   2. Markup
   3. Page refs
   4. List items

   Args:
     text: Block text content
     cursor-pos: Integer cursor position

   Returns:
     {:type :markup :marker \"**\" :start 5 :end 15 ...}
     or {:type :none}"
  [text cursor-pos]
  (or (detect-code-block-at-cursor text cursor-pos) ; Highest priority
      (detect-markup-at-cursor text cursor-pos)
      (detect-page-ref-at-cursor text cursor-pos)
      (detect-list-item-at-cursor text cursor-pos)
      {:type :none}))
