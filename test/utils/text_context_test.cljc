(ns utils.text-context-test
  "Unit tests for context detection.

   Tests all context detection functions according to TEXT_EDITING_TESTING_STRATEGY.md"
  (:require [clojure.test :refer [deftest testing is]]
            [utils.text-context :as ctx]))

;; ── Markup Detection Tests ────────────────────────────────────────────────────

(deftest detect-markup-test
  (testing "Cursor inside bold markup"
    (is (= {:type :markup
            :marker "**"
            :markup-type :bold
            :start 6
            :end 15
            :inner-start 8
            :inner-end 13
            :complete? true}
           (ctx/detect-markup-at-cursor "Hello **world** test" 10))))

  (testing "Cursor at start of bold content"
    (is (= {:type :markup
            :marker "**"
            :markup-type :bold
            :start 6
            :end 15
            :inner-start 8
            :inner-end 13
            :complete? true}
           (ctx/detect-markup-at-cursor "Hello **world** test" 8))))

  (testing "Cursor at end of bold content"
    (is (= {:type :markup
            :marker "**"
            :markup-type :bold
            :start 6
            :end 15
            :inner-start 8
            :inner-end 13
            :complete? true}
           (ctx/detect-markup-at-cursor "Hello **world** test" 13))))

  (testing "Cursor outside markup"
    (is (nil? (ctx/detect-markup-at-cursor "Hello **world** test" 2))))

  (testing "Cursor before opening marker"
    (is (nil? (ctx/detect-markup-at-cursor "Hello **world** test" 5))))

  (testing "Cursor after closing marker"
    (is (nil? (ctx/detect-markup-at-cursor "Hello **world** test" 16))))

  (testing "Italic markup"
    (is (= :italic
           (:markup-type (ctx/detect-markup-at-cursor "__italic__" 5)))))

  (testing "Strikethrough markup"
    (is (= :strike
           (:markup-type (ctx/detect-markup-at-cursor "~~strike~~" 5)))))

  (testing "Highlight markup"
    (is (= :highlight
           (:markup-type (ctx/detect-markup-at-cursor "^^highlight^^" 5)))))

  (testing "Multiple markup on same line - first match"
    (let [result (ctx/detect-markup-at-cursor "**A** **B**" 2)]
      (is (= "**" (:marker result)))
      (is (= 0 (:start result)))
      (is (= 5 (:end result)))))

  (testing "Multiple markup on same line - second match"
    (let [result (ctx/detect-markup-at-cursor "**A** **B**" 8)]
      (is (= "**" (:marker result)))
      (is (= 6 (:start result)))
      (is (= 11 (:end result)))))

  (testing "Empty markup"
    (is (= {:type :markup
            :marker "**"
            :markup-type :bold
            :start 0
            :end 4
            :inner-start 2
            :inner-end 2
            :complete? true}
           (ctx/detect-markup-at-cursor "****" 2))))

  (testing "Incomplete markup (only opening)"
    (is (nil? (ctx/detect-markup-at-cursor "**incomplete" 5)))))

;; ── Page Reference Detection Tests ────────────────────────────────────────────

(deftest detect-page-ref-test
  (testing "Valid page reference"
    (is (= {:type :page-ref
            :start 8
            :end 19
            :inner-start 10
            :inner-end 17
            :complete? true
            :page-name "My Page"}
           (ctx/detect-page-ref-at-cursor "Link to [[My Page]] here" 12))))

  (testing "Cursor at start of page name"
    (is (= "My Page"
           (:page-name (ctx/detect-page-ref-at-cursor "[[My Page]]" 2)))))

  (testing "Cursor at end of page name"
    (is (= "My Page"
           (:page-name (ctx/detect-page-ref-at-cursor "[[My Page]]" 9)))))

  (testing "Outside page reference"
    (is (nil? (ctx/detect-page-ref-at-cursor "Link to [[My Page]] here" 0))))

  (testing "Page name with special characters"
    (is (= "Page/With/Slash"
           (:page-name (ctx/detect-page-ref-at-cursor "[[Page/With/Slash]]" 5)))))

  (testing "Empty page reference"
    (is (= ""
           (:page-name (ctx/detect-page-ref-at-cursor "[[]]" 2))))))

;; ── Code Block Detection Tests ────────────────────────────────────────────────

(deftest detect-code-block-test
  (testing "Simple code block with language"
    (let [text "```clojure\n(+ 1 2)\n```"
          result (ctx/detect-code-block-at-cursor text 15)]
      (is (= :code-block (:type result)))
      (is (= "clojure" (:lang result)))
      (is (= 0 (:start result)))))

  (testing "Code block without language"
    (let [text "```\ncode\n```"
          result (ctx/detect-code-block-at-cursor text 6)]
      (is (= :code-block (:type result)))
      (is (nil? (:lang result)))))

  (testing "Multi-line code block"
    (let [text "```javascript\nfunction test() {\n  return 42;\n}\n```"
          result (ctx/detect-code-block-at-cursor text 20)]
      (is (= :code-block (:type result)))
      (is (= "javascript" (:lang result)))))

  (testing "Cursor before opening marker"
    (is (nil? (ctx/detect-code-block-at-cursor "before\n```\ncode\n```" 3))))

  (testing "Cursor after closing marker"
    (is (nil? (ctx/detect-code-block-at-cursor "```\ncode\n```\nafter" 15))))

  (testing "Incomplete code block (no closing)"
    (is (nil? (ctx/detect-code-block-at-cursor "```\ncode" 5))))

  (testing "Text before code block"
    (let [text "prefix\n```\ncode\n```"
          result (ctx/detect-code-block-at-cursor text 10)]
      (is (= :code-block (:type result))))))

;; ── List Item Detection Tests ─────────────────────────────────────────────────

(deftest detect-list-item-test
  (testing "Dash list item"
    (let [result (ctx/detect-list-item-at-cursor "- item text" 5)]
      (is (= :list-item (:type result)))
      (is (= "- " (:marker result)))
      (is (false? (:numbered? result)))
      (is (= "item text" (:content result)))))

  (testing "Asterisk list item"
    (let [result (ctx/detect-list-item-at-cursor "* item text" 5)]
      (is (= "* " (:marker result)))))

  (testing "Plus list item"
    (let [result (ctx/detect-list-item-at-cursor "+ item text" 5)]
      (is (= "+ " (:marker result)))))

  (testing "Numbered list item"
    (let [result (ctx/detect-list-item-at-cursor "1. First item" 5)]
      (is (= :list-item (:type result)))
      (is (= "1. " (:marker result)))
      (is (true? (:numbered? result)))
      (is (= 1 (:number result)))
      (is (= "First item" (:content result)))))

  (testing "Multi-digit numbered list"
    (let [result (ctx/detect-list-item-at-cursor "42. Item forty-two" 8)]
      (is (= 42 (:number result)))))

  (testing "Unchecked checkbox"
    (let [result (ctx/detect-list-item-at-cursor "- [ ] Task text" 8)]
      (is (= :checkbox (:type result)))
      (is (= "- [ ]" (:marker result)))
      (is (false? (:checked? result)))
      (is (= "Task text" (:content result)))))

  (testing "Checked checkbox (lowercase x)"
    (let [result (ctx/detect-list-item-at-cursor "- [x] Done task" 8)]
      (is (= :checkbox (:type result)))
      (is (true? (:checked? result)))))

  (testing "Checked checkbox (uppercase X)"
    (let [result (ctx/detect-list-item-at-cursor "- [X] Done task" 8)]
      (is (true? (:checked? result)))))

  (testing "Checkbox with asterisk bullet"
    (let [result (ctx/detect-list-item-at-cursor "* [ ] Task" 5)]
      (is (= :checkbox (:type result)))
      (is (= "* [ ]" (:marker result)))))

  (testing "Empty list item (just marker)"
    (is (nil? (ctx/detect-list-item-at-cursor "plain text" 5))))

  (testing "Multi-line: cursor on list line"
    (let [text "plain\n- list item\nmore"
          result (ctx/detect-list-item-at-cursor text 10)]
      (is (= :list-item (:type result)))))

  (testing "Multi-line: cursor not on list line"
    (let [text "plain\n- list item\nmore"]
      (is (nil? (ctx/detect-list-item-at-cursor text 2))))))

;; ── Context Priority Tests ────────────────────────────────────────────────────

(deftest context-at-cursor-test
  (testing "Markup takes priority"
    (is (= :markup (:type (ctx/context-at-cursor "**bold**" 3)))))

  (testing "Code block context"
    (is (= :code-block (:type (ctx/context-at-cursor "```\ncode\n```" 6)))))

  (testing "Page ref context"
    (is (= :page-ref (:type (ctx/context-at-cursor "[[page]]" 3)))))

  (testing "List item context"
    (is (= :list-item (:type (ctx/context-at-cursor "- item" 2)))))

  (testing "Checkbox context"
    (is (= :checkbox (:type (ctx/context-at-cursor "- [ ] task" 5)))))

  (testing "Plain text - no context"
    (is (= :none (:type (ctx/context-at-cursor "plain text" 5)))))

  (testing "Empty string"
    (is (= :none (:type (ctx/context-at-cursor "" 0)))))

  (testing "Complex mixed content - bold wins"
    (let [text "**bold with [[ref]]**"
          result (ctx/context-at-cursor text 15)] ; Inside [[ref]] but also inside **
      (is (= :markup (:type result)))))

  (testing "Complex mixed content - ref when outside bold"
    (let [text "before [[page]] after"
          result (ctx/context-at-cursor text 10)]
      (is (= :page-ref (:type result))))))

;; ── Edge Case Tests ───────────────────────────────────────────────────────────

(deftest edge-cases-test
  (testing "Cursor at position 0"
    (is (= :none (:type (ctx/context-at-cursor "text" 0)))))

  (testing "Cursor at end of text"
    (is (= :none (:type (ctx/context-at-cursor "text" 4)))))

  (testing "Nested markup (inner wins)"
    (let [text "__outer **inner** outer__"
          result (ctx/context-at-cursor text 12)] ; Inside **inner**
      (is (= :markup (:type result)))
      (is (= "**" (:marker result)))))

  (testing "Nested page refs - cursor in inner"
    (let [text "[[outer [[inner]] rest]]"
          result (ctx/detect-page-ref-at-cursor text 12)] ; Inside "inner"
      (is (= :page-ref (:type result)))
      (is (= "inner" (:page-name result)))
      (is (= 8 (:start result)))
      (is (= 17 (:end result)))))

  (testing "Nested page refs - cursor in outer after inner"
    (let [text "[[outer [[inner]] rest]]"
          result (ctx/detect-page-ref-at-cursor text 20)] ; Inside "rest"
      (is (= :page-ref (:type result)))
      (is (= "outer [[inner]] rest" (:page-name result)))
      (is (= 0 (:start result)))
      (is (= 24 (:end result)))))

  (testing "Nested page refs - cursor in outer before inner"
    (let [text "[[outer [[inner]] rest]]"
          result (ctx/detect-page-ref-at-cursor text 4)] ; Inside "outer"
      (is (= :page-ref (:type result)))
      (is (= "outer [[inner]] rest" (:page-name result)))
      (is (= 0 (:start result)))))

  (testing "Adjacent markup regions"
    (let [text "**first****second**"]
      (is (= 0 (:start (ctx/context-at-cursor text 2)))) ; In first
      (is (= 8 (:start (ctx/context-at-cursor text 10)))))) ; In second (cursor after ****)

  (testing "Markup spanning multiple lines"
    (let [text "**multi\nline\nbold**"
          result (ctx/context-at-cursor text 10)]
      (is (= :markup (:type result)))))

  (testing "Code block with nested markup syntax"
    (let [text "```\n**not markup**\n```"
          result (ctx/context-at-cursor text 8)]
      ;; Code block wins - markup inside code is not detected
      (is (= :code-block (:type result)))))

  (testing "List item at start of text"
    (let [result (ctx/context-at-cursor "- item" 0)]
      (is (= :list-item (:type result)))))

  (testing "Unicode in page ref"
    (let [result (ctx/context-at-cursor "[[日本語]]" 5)]
      (is (= :page-ref (:type result)))
      (is (= "日本語" (:page-name result))))))
