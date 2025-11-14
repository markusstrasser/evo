(ns view-util
  "Utilities for testing Replicant views (components).

   Replicant views are pure functions that return hiccup data.
   Since hiccup is just data (vectors, maps), we can test it without a browser.

   This module provides:
   - Element selectors (find-element, find-all-elements)
   - Attribute extractors (select-attribute, has-class?)
   - Action extractors (select-actions, extract-event-handlers)
   - Placeholder interpolation (interpolate-placeholders)

   Usage:
     (let [view (Block {:db db :block-id \"a\" :on-intent identity})]
       ;; Find elements
       (find-element view :span)
       (find-element view :.content-edit)

       ;; Extract attributes
       (select-attribute view :.content-edit :contentEditable)
       (has-class? view :.block \"editing\")

       ;; Extract actions from event handlers
       (select-actions view :.content-edit [:on :input]))"
  (:require [clojure.walk :as walk]
            [clojure.string :as str]))

;; =============================================================================
;; Element Selection
;; =============================================================================

(defn parse-tag
  "Parse hiccup tag keyword into {:tag \"div\" :classes [\"foo\" \"bar\"] :id \"baz\"}
   Handles tags like :div.foo.bar#baz"
  [tag-kw]
  (when (keyword? tag-kw)
    (let [tag-str (name tag-kw)
          ;; Split by # first to get id
          [tag-and-classes id] (str/split tag-str #"#" 2)
          ;; Split by . to get tag and classes
          parts (str/split tag-and-classes #"\.")
          tag (first parts)
          classes (rest parts)]
      {:tag tag
       :classes (vec classes)
       :id id})))

(defn tag-matches?
  "Check if hiccup element tag matches selector.
   Selector can be:
   - Keyword for tag: :div, :span
   - Keyword with class: :div.foo, :span.bar, :.foo (class only)
   - Keyword with id: :div#foo, :#foo (id only)
   - String for class: \"foo\", \"bar\"
   - String for id: \"#foo\""
  [element selector]
  (when (vector? element)
    (let [tag-kw (first element)
          tag-parsed (parse-tag tag-kw)
          attrs (when (map? (second element)) (second element))]
      (cond
        ;; Plain keyword selector (tag only)
        (and (keyword? selector)
             (not (str/includes? (name selector) "."))
             (not (str/includes? (name selector) "#")))
        (= (:tag tag-parsed) (name selector))

        ;; Keyword with class: :div.foo or :.foo (class only)
        (and (keyword? selector)
             (str/includes? (name selector) "."))
        (let [sel-parsed (parse-tag selector)]
          (and
           ;; If selector has a tag, it must match
           (or (empty? (:tag sel-parsed))
               (= (:tag tag-parsed) (:tag sel-parsed)))
           ;; Must have matching class
           (or
            ;; Class in tag itself (e.g., :span.content-view)
            (some (set (:classes tag-parsed)) (:classes sel-parsed))
            ;; Class in :class attribute
            (when-let [attr-classes (:class attrs)]
              (some (set attr-classes) (:classes sel-parsed))))))

        ;; Keyword with id: :div#foo or :#foo (id only)
        (and (keyword? selector)
             (str/includes? (name selector) "#"))
        (let [sel-parsed (parse-tag selector)]
          (and
           ;; If selector has a tag, it must match
           (or (empty? (:tag sel-parsed))
               (= (:tag tag-parsed) (:tag sel-parsed)))
           ;; Must have matching id
           (or (= (:id tag-parsed) (:id sel-parsed))
               (= (:id attrs) (:id sel-parsed)))))

        ;; String class selector: \"foo\"
        (and (string? selector)
             (not (str/starts-with? selector "#")))
        (or
         ;; Class in tag
         (some #(= selector %) (:classes tag-parsed))
         ;; Class in :class attribute
         (when-let [attr-classes (:class attrs)]
           (some #(= selector %) attr-classes)))

        ;; String id selector: \"#foo\"
        (and (string? selector)
             (str/starts-with? selector "#"))
        (let [id (subs selector 1)]
          (or (= (:id tag-parsed) id)
              (= (:id attrs) id)))

        :else false))))

(defn find-element
  "Find first element in hiccup matching selector.
   Returns the element vector or nil if not found.

   Examples:
     (find-element hiccup :div)         ; Find first <div>
     (find-element hiccup :span.foo)    ; Find first <span class=\"foo\">
     (find-element hiccup :div#bar)     ; Find first <div id=\"bar\">
     (find-element hiccup \"foo\")       ; Find first element with class \"foo\"
     (find-element hiccup \"#bar\")      ; Find first element with id \"bar\""
  [hiccup selector]
  (let [result (atom nil)]
    (walk/prewalk
     (fn [x]
       (when (and (nil? @result)
                  (tag-matches? x selector))
         (reset! result x))
       x)
     hiccup)
    @result))

(defn find-all-elements
  "Find all elements in hiccup matching selector.
   Returns a vector of matching elements."
  [hiccup selector]
  (let [results (atom [])]
    (walk/prewalk
     (fn [x]
       (when (tag-matches? x selector)
         (swap! results conj x))
       x)
     hiccup)
    @results))

;; =============================================================================
;; Attribute Extraction
;; =============================================================================

(defn get-attrs
  "Get attributes map from hiccup element.
   Returns nil if element has no attributes."
  [element]
  (when (and (vector? element)
             (map? (second element)))
    (second element)))

(defn select-attribute
  "Extract attribute value from element matching selector.

   Examples:
     (select-attribute hiccup :div [:style :color])
     (select-attribute hiccup :.content-edit :contentEditable)
     (select-attribute hiccup :div#foo :data-block-id)"
  [hiccup selector attr-path]
  (when-let [element (find-element hiccup selector)]
    (let [attrs (get-attrs element)]
      (if (vector? attr-path)
        (get-in attrs attr-path)
        (get attrs attr-path)))))

(defn has-class?
  "Check if element matching selector has CSS class.

   Examples:
     (has-class? hiccup :div \"active\")
     (has-class? hiccup :.block \"editing\")"
  [hiccup selector class-name]
  (when-let [element (find-element hiccup selector)]
    (when-let [attrs (get-attrs element)]
      (boolean (some #(= class-name %) (:class attrs))))))

(defn extract-text
  "Extract text content from element.
   Walks through children and collects strings.

   Examples:
     (extract-text [:div \"Hello\" [:span \"World\"]])  ;=> \"HelloWorld\""
  [element]
  (cond
    (string? element) element
    (vector? element) (->> element
                           (drop 1)  ; Skip tag
                           (drop-while map?)  ; Skip attrs
                           (mapcat (fn [child]
                                     (if (sequential? child)
                                       (map extract-text child)
                                       [(extract-text child)])))
                           (apply str))
    (sequential? element) (->> element
                               (map extract-text)
                               (apply str))
    :else ""))

;; =============================================================================
;; Action Extraction (for data-driven event handlers)
;; =============================================================================

(defn extract-event-handlers
  "Extract :on event handler map from element.
   Returns nil if element has no :on handlers."
  [element]
  (when-let [attrs (get-attrs element)]
    (:on attrs)))

(defn select-actions
  "Extract actions from event handler in hiccup element.

   Examples:
     (select-actions hiccup :.content-edit [:input])
     (select-actions hiccup :button [:click])

   Returns action vector(s) or nil if handler not found."
  [hiccup selector event-path]
  (when-let [element (find-element hiccup selector)]
    (when-let [handlers (extract-event-handlers element)]
      (if (vector? event-path)
        (get-in handlers event-path)
        (get handlers event-path)))))

(defn find-all-actions
  "Find all actions in hiccup tree.
   Returns a vector of action vectors.
   Useful for verifying no unexpected actions are present."
  [hiccup]
  (let [actions (atom [])]
    (walk/prewalk
     (fn [x]
       (when (and (vector? x)
                  (map? (second x))
                  (:on (second x)))
         (doseq [[_event-name handler] (:on (second x))]
           (cond
             ;; Single action vector
             (and (vector? handler)
                  (keyword? (first handler)))
             (swap! actions conj handler)

             ;; Multiple action vectors
             (and (vector? handler)
                  (vector? (first handler)))
             (swap! actions into handler))))
       x)
     hiccup)
    @actions))

;; =============================================================================
;; Placeholder Interpolation (for testing actions)
;; =============================================================================

(defn interpolate-placeholders
  "Replace event placeholders with actual values for testing.

   Placeholders are keywords like :event/target.value that need to be
   replaced with concrete values to test action handlers.

   Examples:
     (interpolate-placeholders
       [[:update-content \"a\" :event/target.value]]
       {:event/target.value \"Hello\"})
     ;=> [[:update-content \"a\" \"Hello\"]]"
  [actions placeholder-values]
  (walk/postwalk
   (fn [x]
     (if (and (keyword? x)
              (contains? placeholder-values x))
       (get placeholder-values x)
       x))
   actions))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn pprint-hiccup
  "Pretty-print hiccup for debugging.
   Returns formatted string."
  [hiccup]
  (with-out-str
    (clojure.pprint/pprint hiccup)))

(defn count-elements
  "Count elements matching selector in hiccup."
  [hiccup selector]
  (count (find-all-elements hiccup selector)))

(defn element-exists?
  "Check if element matching selector exists in hiccup."
  [hiccup selector]
  (boolean (find-element hiccup selector)))

;; =============================================================================
;; Hiccup Predicates
;; =============================================================================

(defn lifecycle-hook?
  "Check if element has a Replicant lifecycle hook.
   Hook can be :replicant/on-mount, :replicant/on-render, etc."
  [element hook-name]
  (when-let [attrs (get-attrs element)]
    (fn? (get attrs hook-name))))

(defn has-event-handler?
  "Check if element has event handler for given event.
   Event can be :click, :input, etc."
  [element event-name]
  (when-let [handlers (extract-event-handlers element)]
    (contains? handlers event-name)))
