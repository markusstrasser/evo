(ns test-dsl
  "DSL for visualizing test flows in ASCII.

   Makes it easy to review if tests check the right thing by showing:
   - State → Hiccup structure
   - Event → Actions dispatched
   - Action → State changes

   Usage:
     (show-render-flow db component-fn)
     (show-action-flow hiccup selector event-path)
     (show-intent-flow db intent)
     (show-test-scenario scenario)"
  (:require [view-util :as vu]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Hiccup Structure Visualization
;; =============================================================================

(defn hiccup->tree
  "Convert hiccup to ASCII tree.

   Example:
     [:div.block
      [:span.bullet \"•\"]
      [:span.content-view \"Hello\"]]

   Becomes:
     div.block
     ├─ span.bullet \"•\"
     └─ span.content-view \"Hello\""
  ([hiccup] (hiccup->tree hiccup "" true))
  ([element prefix is-last]
   (cond
     (string? element)
     (str prefix "\"" element "\"")

     (vector? element)
     (let [tag (first element)
           attrs (when (map? (second element)) (second element))
           children (->> element
                        (drop 1)
                        (drop-while map?)
                        (filter #(or (vector? %) (string? %))))
           tag-str (name tag)
           ;; Add key attrs for context
           attr-str (cond
                      (:id attrs) (str "#" (:id attrs))
                      (seq (:class attrs)) (str " [" (str/join " " (:class attrs)) "]")
                      :else "")
           event-handlers (when (:on attrs)
                           (str " {" (str/join " " (map name (keys (:on attrs)))) "}"))
           lifecycle-hooks (->> attrs
                               (filter #(str/starts-with? (name (key %)) "replicant/on-"))
                               (map #(str/replace (name (key %)) "replicant/on-" "@"))
                               (str/join " "))
           hooks-str (when (seq lifecycle-hooks) (str " <" lifecycle-hooks ">"))
           node-line (str prefix
                         (if is-last "└─ " "├─ ")
                         tag-str attr-str event-handlers hooks-str)
           child-prefix (str prefix (if is-last "   " "│  "))]
       (str node-line "\n"
            (->> children
                 (map-indexed
                  (fn [idx child]
                    (hiccup->tree child
                                  child-prefix
                                  (= idx (dec (count children))))))
                 (str/join "\n"))))

     (sequential? element)
     (str prefix "(list " (count element) " items)")

     :else
     (str prefix (pr-str element)))))

(defn show-hiccup
  "Pretty-print hiccup as ASCII tree."
  [hiccup]
  (println (hiccup->tree hiccup)))

;; =============================================================================
;; Action Flow Visualization
;; =============================================================================

(defn show-actions
  "Show actions dispatched by event handler.

   Format:
     Event: :input on span.content-edit
     Actions:
       → [:update-content \"a\" :event/target.value]
     With {value: \"Hello\"}:
       → [:update-content \"a\" \"Hello\"]"
  [hiccup selector event-path placeholder-values]
  (let [element (vu/find-element hiccup selector)
        actions (vu/select-actions hiccup selector event-path)
        interpolated (when (and actions placeholder-values)
                      (vu/interpolate-placeholders actions placeholder-values))]
    (println (str "Event: " (pr-str event-path) " on " selector))
    (println "Actions:")
    (doseq [action actions]
      (println (str "  → " (pr-str action))))
    (when interpolated
      (println (str "With " (pr-str placeholder-values) ":"))
      (doseq [action interpolated]
        (println (str "  → " (pr-str action)))))))

;; =============================================================================
;; Test Scenario DSL
;; =============================================================================

(defn scenario->ascii
  "Convert test scenario to ASCII flow diagram.

   Scenario format:
     {:given {:db {...} :editing \"a\"}
      :when {:render Block}
      :then {:shows :.content-edit
             :with-text \"Hello\"
             :on-event {:type :input :value \"New\"}
             :dispatches [[:update-content \"a\" \"New\"]]
             :results-in {:text \"New\"}}}

   Becomes:
     GIVEN:
       • editing block \"a\"
       • text: \"Hello\"

     WHEN: Render Block component
       div.block
       └─ span.content-edit \"Hello\" {input}

     THEN:
       ✓ Shows .content-edit
       ✓ Text = \"Hello\"

     ON EVENT: :input with \"New\"
       → [:update-content \"a\" \"New\"]

     RESULTS:
       • text = \"New\""
  [scenario]
  (let [given (:given scenario)
        when-clause (:when scenario)
        then (:then scenario)]
    (str/join "\n\n"
              [(str "GIVEN:\n"
                    (->> given
                         (map (fn [[k v]]
                                (str "  • " (name k) ": " (pr-str v))))
                         (str/join "\n")))

               (when-let [render (:render when-clause)]
                 (str "WHEN: Render " render))

               (when-let [shows (:shows then)]
                 (str "THEN:\n"
                      (str "  ✓ Shows " shows)))

               (when-let [event (:on-event then)]
                 (str "ON EVENT: " (pr-str event)))

               (when-let [actions (:dispatches then)]
                 (str "DISPATCHES:\n"
                      (->> actions
                           (map #(str "  → " (pr-str %)))
                           (str/join "\n"))))

               (when-let [results (:results-in then)]
                 (str "RESULTS:\n"
                      (->> results
                           (map (fn [[k v]]
                                  (str "  • " (name k) " = " (pr-str v))))
                           (str/join "\n"))))])))

;; =============================================================================
;; Compact Test Notation
;; =============================================================================

(defn compact-flow
  "Super compact notation for quick review.

   Format: State → Element → Event → Action → Result

   Example:
     {editing:a} → <span.edit> → :input → [:update a val] → {text:\"Hello\"}"
  [{:keys [state element event action result]}]
  (str
   ;; State
   "{"
   (->> state
        (map (fn [[k v]] (str (name k) ":" (pr-str v))))
        (str/join " "))
   "}"

   " → "

   ;; Element
   "<" (or element "?") ">"

   " → "

   ;; Event
   (pr-str event)

   " → "

   ;; Action
   (pr-str action)

   " → "

   ;; Result
   "{"
   (->> result
        (map (fn [[k v]] (str (name k) ":" (pr-str v))))
        (str/join " "))
   "}"))

;; =============================================================================
;; State Diff Visualization
;; =============================================================================

(defn show-state-diff
  "Show state changes from operation.

   Format:
     BEFORE:
       nodes.a.text = \"Hello\"
       cursor-pos = 5

     OPERATION: [:update-content \"a\" \"World\"]

     AFTER:
       nodes.a.text = \"World\"  (changed)
       cursor-pos = 5"
  [before ops after]
  (let [extract-paths (fn [m]
                        (letfn [(paths [m path]
                                  (if (map? m)
                                    (mapcat (fn [[k v]]
                                              (paths v (conj path k)))
                                            m)
                                    [(conj path m)]))]
                          (paths m [])))
        before-paths (set (extract-paths before))
        after-paths (set (extract-paths after))
        changed (filter (fn [path]
                         (not= (get-in before (butlast path))
                               (get-in after (butlast path))))
                       after-paths)]
    (println "BEFORE:")
    (doseq [path (take 5 (sort before-paths))]
      (let [path-val (get-in before (butlast path))]
        (println (str "  " (str/join "." (map name (butlast path)))
                     " = " (pr-str path-val)))))

    (println "\nOPERATION:")
    (doseq [op ops]
      (println (str "  " (pr-str op))))

    (println "\nAFTER:")
    (doseq [path (take 5 (sort changed))]
      (let [old-val (get-in before (butlast path))
            new-val (get-in after (butlast path))]
        (println (str "  " (str/join "." (map name (butlast path)))
                     " = " (pr-str new-val)
                     (when (not= old-val new-val) " (changed)")))))

    (println (str "\n" (count changed) " paths changed"))))

;; =============================================================================
;; Test Summary Table
;; =============================================================================

(defn test-table
  "Generate ASCII table of test scenarios.

   Format:
     | Scenario        | State    | Event   | Expected Action           | Status |
     |-----------------|----------|---------|---------------------------|--------|
     | Edit mode input | editing  | :input  | [:update-content a val]   | ✓      |
     | View mode click | viewing  | :click  | [:enter-edit a]           | ✓      |"
  [scenarios]
  (let [header ["Scenario" "State" "Event" "Expected Action" "Status"]
        rows (map (fn [s]
                   [(:name s)
                    (str (:state s))
                    (str (:event s))
                    (str (:expected s))
                    (if (:passing s) "✓" "✗")])
                 scenarios)
        widths (map (fn [col-idx]
                     (apply max (map count (map #(nth % col-idx) (cons header rows)))))
                   (range (count header)))
        fmt-row (fn [row]
                 (str "| "
                      (->> (map-indexed (fn [idx cell]
                                         (let [width (nth widths idx)]
                                           (str cell (str/join (repeat (- width (count cell)) " ")))))
                                       row)
                           (str/join " | "))
                      " |"))
        separator (str "|-"
                      (->> widths
                           (map #(str/join (repeat % "-")))
                           (str/join "-|-"))
                      "-|")]
    (str/join "\n"
              [(fmt-row header)
               separator
               (->> rows
                    (map fmt-row)
                    (str/join "\n"))])))

;; =============================================================================
;; Quick Review Helpers
;; =============================================================================

(defn review-test
  "Quick review of what a test checks.

   Shows:
   - What state we start with
   - What we render
   - What element we interact with
   - What event fires
   - What actions get dispatched
   - What state changes result"
  [test-fn]
  (println "=== TEST REVIEW ===")
  (println (str "(not implemented yet - call with test metadata)")))

(defn verify-action
  "Verify action does what you expect.

   Usage:
     (verify-action
       {:action [:update-content \"a\" \"Hello\"]
        :expect {:op :update-node
                 :id \"a\"
                 :props {:text \"Hello\"}}})

   Shows:
     ACTION: [:update-content \"a\" \"Hello\"]

     EXPECT: {:op :update-node, :id \"a\", :props {:text \"Hello\"}}

     ACTUAL: {:op :update-node, :id \"a\", :props {:text \"Hello\"}}

     ✓ MATCH"
  [{:keys [action expect actual]}]
  (println "ACTION:" (pr-str action))
  (println "\nEXPECT:" (pr-str expect))
  (println "\nACTUAL:" (pr-str actual))
  (println (if (= expect actual)
            "\n✓ MATCH"
            "\n✗ MISMATCH")))

;; =============================================================================
;; Example Usage
;; =============================================================================

(comment
  ;; Show hiccup structure
  (show-hiccup
   [:div.block
    [:span.bullet "•"]
    [:span.content-edit {:contentEditable true
                         :replicant/on-render (fn [_] ...)
                         :on {:input (fn [_] ...)}}
     "Hello"]])

  ;; Output:
  ;; div.block
  ;; ├─ span.bullet
  ;; │  └─ "•"
  ;; └─ span.content-edit {input} <render>
  ;;    └─ "Hello"

  ;; Show action flow
  (show-actions hiccup :.content-edit [:on :input]
                {:event/target.value "World"})

  ;; Output:
  ;; Event: [:on :input] on :.content-edit
  ;; Actions:
  ;;   → [:update-content "a" :event/target.value]
  ;; With {:event/target.value "World"}:
  ;;   → [:update-content "a" "World"]

  ;; Compact flow
  (compact-flow
   {:state {:editing "a" :text "Hello"}
    :element "span.edit"
    :event :input
    :action [:update-content "a" "World"]
    :result {:text "World"}})

  ;; Output:
  ;; {editing:"a" text:"Hello"} → <span.edit> → :input → [:update-content "a" "World"] → {text:"World"}

  ;; Test table
  (test-table
   [{:name "Edit input" :state "editing" :event ":input"
     :expected "[:update a val]" :passing true}
    {:name "View click" :state "viewing" :event ":click"
     :expected "[:enter-edit a]" :passing true}])

  ;; Output:
  ;; | Scenario    | State   | Event  | Expected Action     | Status |
  ;; |-------------|---------|--------|---------------------|--------|
  ;; | Edit input  | editing | :input | [:update a val]     | ✓      |
  ;; | View click  | viewing | :click | [:enter-edit a]     | ✓      |
  )
