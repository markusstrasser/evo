(ns view.util
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
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

;; =============================================================================
;; Element Selection
;; =============================================================================

(defn parse-tag
  "Parse hiccup tag keyword into {:tag \"div\" :classes [\"foo\" \"bar\"] :id \"baz\"}
   Handles tags like :div.foo.bar#baz"
  [tag-kw]
  (when (keyword? tag-kw)
    (let [tag-str (name tag-kw)
          [tag-and-classes id] (str/split tag-str #"#" 2)
          parts (str/split tag-and-classes #"\.")
          tag (first parts)
          classes (rest parts)]
      {:tag tag
       :classes (vec classes)
       :id id})))

(defn- element-attrs
  "Extract attributes map from hiccup element. Returns nil if no attrs."
  [element]
  (when (and (vector? element) (map? (second element)))
    (second element)))

(defn- tag-kw
  "Extract tag keyword from hiccup element."
  [element]
  (when (vector? element)
    (first element)))

(defn- selector-type
  "Classify selector type. Returns [:tag], [:class class-sel], [:id id-sel], etc."
  [selector]
  (cond
    (keyword? selector)
    (let [sel-name (name selector)]
      (cond
        (str/includes? sel-name "#") [:keyword-id selector]
        (str/includes? sel-name ".") [:keyword-class selector]
        :else [:tag selector]))

    (string? selector)
    (if (str/starts-with? selector "#")
      [:string-id (subs selector 1)]
      [:string-class selector])

    :else [:unknown]))

(defn- class-match?
  "Check if element has matching class from selector."
  [tag-parsed attrs sel-parsed]
  (or
   ;; Class in tag itself (e.g., :span.block-content)
   (some (set (:classes tag-parsed)) (:classes sel-parsed))
   ;; Class in :class attribute
   (when-let [attr-classes (:class attrs)]
     (some (set attr-classes) (:classes sel-parsed)))))

(defn- id-match?
  "Check if element has matching id from selector."
  [tag-parsed attrs sel-parsed-id]
  (or (= (:id tag-parsed) sel-parsed-id)
      (= (:id attrs) sel-parsed-id)))

(defn- tag-match?
  "Check if element tag matches (or no tag constraint)."
  [tag-parsed sel-parsed]
  (or (empty? (:tag sel-parsed))
      (= (:tag tag-parsed) (:tag sel-parsed))))

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
    (let [tag-parsed (parse-tag (tag-kw element))
          attrs (element-attrs element)
          [sel-type sel-value] (selector-type selector)]
      (case sel-type
        :tag
        (= (:tag tag-parsed) (name sel-value))

        :keyword-class
        (let [sel-parsed (parse-tag sel-value)]
          (and (tag-match? tag-parsed sel-parsed)
               (class-match? tag-parsed attrs sel-parsed)))

        :keyword-id
        (let [sel-parsed (parse-tag sel-value)]
          (and (tag-match? tag-parsed sel-parsed)
               (id-match? tag-parsed attrs (:id sel-parsed))))

        :string-class
        (or
         (some #(= sel-value %) (:classes tag-parsed))
         (when-let [attr-classes (:class attrs)]
           (some #(= sel-value %) attr-classes)))

        :string-id
        (id-match? tag-parsed attrs sel-value)

        false))))

(defn- walk-collect
  "Walk hiccup tree and collect matching elements.
   Predicate fn should return truthy for elements to collect.
   If early-exit? is true, stops after first match."
  [hiccup pred early-exit?]
  (let [results (atom [])]
    (walk/prewalk
     (fn [x]
       (when (and (or (not early-exit?) (empty? @results))
                  (pred x))
         (swap! results conj x))
       x)
     hiccup)
    @results))

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
  (first (walk-collect hiccup #(tag-matches? % selector) true)))

(defn find-all-elements
  "Find all elements in hiccup matching selector.
   Returns a vector of matching elements."
  [hiccup selector]
  (walk-collect hiccup #(tag-matches? % selector) false))

;; =============================================================================
;; Attribute Extraction
;; =============================================================================

(defn get-attrs
  "Get attributes map from hiccup element.
   Returns nil if element has no attributes.
   Alias for element-attrs for backward compatibility."
  [element]
  (element-attrs element))

(defn- get-attr-value
  "Get attribute value from attrs map using path (keyword or vector)."
  [attrs attr-path]
  (if (vector? attr-path)
    (get-in attrs attr-path)
    (get attrs attr-path)))

(defn select-attribute
  "Extract attribute value from element matching selector.

   Examples:
     (select-attribute hiccup :div [:style :color])
     (select-attribute hiccup :.content-edit :contentEditable)
     (select-attribute hiccup :div#foo :data-block-id)"
  [hiccup selector attr-path]
  (when-let [element (find-element hiccup selector)]
    (get-attr-value (element-attrs element) attr-path)))

(defn has-class?
  "Check if element matching selector has CSS class.

   Examples:
     (has-class? hiccup :div \"active\")
     (has-class? hiccup :.block \"editing\")"
  [hiccup selector class-name]
  (when-let [element (find-element hiccup selector)]
    (boolean (some #(= class-name %) (get-attr-value (element-attrs element) :class)))))

(defn- element-children
  "Get children from hiccup element (skipping tag and attrs)."
  [element]
  (when (vector? element)
    (->> element
         (drop 1)
         (drop-while map?))))

(defn extract-text
  "Extract text content from element.
   Walks through children and collects strings.

   Examples:
     (extract-text [:div \"Hello\" [:span \"World\"]])  ;=> \"HelloWorld\""
  [element]
  (cond
    (string? element) element
    (vector? element) (->> (element-children element)
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
  (get-attr-value (element-attrs element) :on))

(defn select-actions
  "Extract actions from event handler in hiccup element.

   Examples:
     (select-actions hiccup :.content-edit [:input])
     (select-actions hiccup :button [:click])

   Returns action vector(s) or nil if handler not found."
  [hiccup selector event-path]
  (when-let [element (find-element hiccup selector)]
    (get-attr-value (extract-event-handlers element) event-path)))

(defn- action-vector?
  "Check if value is an action vector (keyword-prefixed vector)."
  [v]
  (and (vector? v) (keyword? (first v))))

(defn- collect-handler-actions
  "Extract action vectors from a single event handler value."
  [handler]
  (cond
    ;; Single action vector
    (action-vector? handler)
    [handler]

    ;; Multiple action vectors
    (and (vector? handler) (every? action-vector? handler))
    handler

    :else []))

(defn find-all-actions
  "Find all actions in hiccup tree.
   Returns a vector of action vectors.
   Useful for verifying no unexpected actions are present."
  [hiccup]
  (let [actions (atom [])]
    (walk/prewalk
     (fn [x]
       (when-let [handlers (extract-event-handlers x)]
         (doseq [[_event-name handler] handlers]
           (swap! actions into (collect-handler-actions handler))))
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
    (pprint/pprint hiccup)))

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
  (fn? (get-attr-value (element-attrs element) hook-name)))

(defn has-event-handler?
  "Check if element has event handler for given event.
   Event can be :click, :input, etc."
  [element event-name]
  (contains? (or (extract-event-handlers element) {}) event-name))
