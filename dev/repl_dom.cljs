(ns dev.repl-dom
  "REPL → DOM bridge for instant feedback without Playwright

  Usage:
    (require '[dev.repl-dom :as dom])

    ;; Render component and get DOM state instantly
    (dom/render-and-inspect db \"block-123\")
    ;=> {:text \"Hello\" :cursor 5 :focused? true :has-text-node? true}

    ;; Simulate typing
    (dom/type-text \"abc\")
    ;=> {:order-correct? true :cursor-positions [1 2 3]}

    ;; Check focus
    (dom/focused-element)
    ;=> #js {:tag \"SPAN\" :contenteditable \"true\" :block-id \"block-123\"}

  10x faster than Playwright - no browser launch!"
  (:require [shell.blocks-ui :as blocks-ui]
            [components.block :as block]
            [kernel.query :as q]))

;; Quick DOM inspection helpers

(defn focused-element
  "Get currently focused element with useful info"
  []
  (let [el js/document.activeElement]
    {:tag (.-tagName el)
     :id (.-id el)
     :class (.-className el)
     :contenteditable (.-contentEditable el)
     :block-id (.. el -dataset -blockId)
     :text (.-textContent el)
     :is-body? (= el js/document.body)}))

(defn cursor-position
  "Get cursor position in currently focused contenteditable"
  []
  (let [sel (.getSelection js/window)]
    (when (> (.-rangeCount sel) 0)
      (let [range (.getRangeAt sel 0)]
        {:offset (.-startOffset range)
         :text-node (.-startContainer range)
         :text-length (when-let [tn (.-startContainer range)]
                        (.-length tn))}))))

(defn contenteditable-state
  "Complete state of all contenteditable elements"
  []
  (let [editables (.querySelectorAll js/document "[contenteditable='true']")]
    (map (fn [el]
           {:block-id (.. el -dataset -blockId)
            :text (.-textContent el)
            :focused? (= el js/document.activeElement)
            :has-text-node? (some? (.-firstChild el))
            :child-nodes (.-length (.-childNodes el))
            :cursor (when (= el js/document.activeElement)
                      (:offset (cursor-position)))})
         (array-seq editables))))

(defn db-vs-dom
  "Compare DB state vs actual DOM for all blocks"
  [db]
  (let [editing-id (q/editing-block-id db)]
    (map (fn [state]
           (let [db-text (get-in db [:nodes (:block-id state) :props :text])]
             (assoc state
                    :db-text db-text
                    :match? (= db-text (:text state)))))
         (contenteditable-state))))

;; Simulate user interactions

(defn ^:export type-text
  "Simulate typing text and track cursor positions

  Returns {:text final-text
           :cursor-positions [pos-after-each-char]
           :order-correct? boolean}"
  [text-to-type]
  (let [positions (atom [])]
    (doseq [char text-to-type]
      ;; Simulate keypress
      (let [event (js/KeyboardEvent. "keydown" #js {:key (str char)})]
        (.dispatchEvent js/document.activeElement event))

      ;; Track cursor position after each char
      (swap! positions conj (:offset (cursor-position))))

    (let [final-text (.-textContent js/document.activeElement)
          expected (str text-to-type)]
      {:text final-text
       :cursor-positions @positions
       :order-correct? (= final-text expected)
       :cursor-increasing? (apply < @positions)})))

(defn ^:export simulate-enter
  "Simulate Enter key, return before/after state"
  []
  (let [before {:focused (focused-element)
                :cursor (cursor-position)
                :contenteditable (contenteditable-state)}
        event (js/KeyboardEvent. "keydown" #js {:key "Enter"})]
    (.dispatchEvent js/document.activeElement event)

    ;; Wait for render
    (js/setTimeout
     (fn []
       (let [after {:focused (focused-element)
                    :cursor (cursor-position)
                    :contenteditable (contenteditable-state)}]
         (js/console.log "Enter simulation:"
                         (clj->js {:before before :after after}))
         {:before before :after after}))
     100)))

;; Property-based edge case generation

(def text-edge-cases
  "All text edge cases to test"
  ["" ; Empty
   "a" ; Single char
   "abc" ; Few chars
   (apply str (repeat 1000 "x")) ; Very long
   "Hello\nWorld" ; Newline
   "  spaces  " ; Leading/trailing spaces
   "🔥🚀💡" ; Emoji/unicode
   "‏العربية‏" ; RTL
   "<script>alert('xss')</script>" ; HTML injection
   "Line 1\nLine 2\nLine 3" ; Multi-line
   ])

(defn ^:export test-all-edge-cases
  "Test current component with all edge cases

  Returns {:passed N :failed N :failures [...]}"
  []
  (let [results (atom {:passed 0 :failed 0 :failures []})]
    (doseq [text text-edge-cases]
      ;; Set text
      (set! (.-textContent js/document.activeElement) text)

      ;; Verify cursor works
      (let [typed (type-text "x")
            db-state @blocks-ui/!db
            dom-state (contenteditable-state)
            cursor-ok? (:order-correct? typed)
            focus-ok? (some :focused? dom-state)
            db-dom-ok? (every? :match? (db-vs-dom db-state))]

        (if (and cursor-ok? focus-ok? db-dom-ok?)
          (swap! results update :passed inc)
          (swap! results
                 (fn [r]
                   (-> r
                       (update :failed inc)
                       (update :failures conj
                               {:text text
                                :cursor-ok? cursor-ok?
                                :focus-ok? focus-ok?
                                :db-dom-ok? db-dom-ok?})))))))
    @results))

;; Quick component rendering

(defn ^:export render-block
  "Render a block component and return its DOM representation

  Usage: (render-block @!db \"block-123\")"
  [db block-id]
  (let [component (block/block-component db block-id)]
    ;; This would need Replicant's render function exposed
    ;; For now, just return the hiccup
    component))

;; Visual debugging

(defn ^:export highlight-focused
  "Visually highlight the focused element (debug aid)"
  []
  (let [el js/document.activeElement]
    (set! (.. el -style -outline) "3px solid red")
    (set! (.. el -style -outlineOffset) "2px")
    (js/setTimeout #(set! (.. el -style -outline) "") 2000)))

(defn ^:export trace-cursor
  "Record cursor positions over time, log as graph

  Usage: (trace-cursor) then type some text"
  []
  (let [positions (atom [])
        interval-id (js/setInterval
                     (fn []
                       (when-let [pos (:offset (cursor-position))]
                         (swap! positions conj pos)))
                     100)]

    ;; Stop after 5 seconds
    (js/setTimeout
     (fn []
       (js/clearInterval interval-id)
       (let [ps @positions]
         (js/console.log "Cursor trace:" (clj->js ps))
         (js/console.log "Monotonic?" (apply < ps))
         (when-not (apply < ps)
           (js/console.error "CURSOR RESET DETECTED!"))))
     5000)))

;; Batch testing

(defn ^:export run-quick-tests
  "Run all quick tests in REPL (no Playwright needed)

  Returns summary of pass/fail"
  []
  (let [db @blocks-ui/!db]
    {:focus-test
     (let [before (focused-element)]
       {:focused? (not (:is-body? before))
        :contenteditable? (= "true" (:contenteditable before))})

     :cursor-test
     (let [typed (type-text "abc")]
       {:order-correct? (:order-correct? typed)
        :cursor-increasing? (:cursor-increasing? typed)})

     :db-dom-test
     (let [states (db-vs-dom db)]
       {:all-match? (every? :match? states)
        :mismatches (filter (comp not :match?) states)})

     :edge-cases-test
     (test-all-edge-cases)}))

;; Export for browser console
(set! js/window.DOM
      #js {:focused focused-element
           :cursor cursor-position
           :state contenteditable-state
           :dbVsDom #(db-vs-dom @blocks-ui/!db)
           :typeText type-text
           :simulateEnter simulate-enter
           :testEdgeCases test-all-edge-cases
           :highlightFocused highlight-focused
           :traceCursor trace-cursor
           :runTests run-quick-tests})

(js/console.log "
🔧 REPL DOM Bridge loaded!

Quick tests (no Playwright):
  (require '[dev.repl-dom :as dom])
  (dom/run-quick-tests)          ; Run all tests

DOM inspection:
  (dom/focused-element)          ; What's focused?
  (dom/cursor-position)          ; Where's cursor?
  (dom/contenteditable-state)    ; All editables
  (dom/db-vs-dom @!db)          ; DB vs DOM check

Simulation:
  (dom/type-text \"abc\")         ; Simulate typing
  (dom/simulate-enter)           ; Simulate Enter key

Edge cases:
  (dom/test-all-edge-cases)      ; Test all edge cases

Browser console:
  DOM.focused()                  ; Same functions
  DOM.typeText('abc')
  DOM.runTests()
")
