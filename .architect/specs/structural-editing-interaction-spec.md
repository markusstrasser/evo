# Structural Editing UI Interaction Spec

| Generic role | Your domain term            | Example                                         |
| ------------ | --------------------------- | ----------------------------------------------- |
| Container    | Page outline                | `Journal 2025-09-30`                            |
| Item         | Block bullet                | `"- Prepare demo"`                              |
| Selection    | Highlighted blocks          | `"Second bullet + child"`                       |
| Action       | Keyboard or pointer gesture | `press "Tab"`, `drag handle to after "Block C"` |

## 2-3. Behavior Snapshots & Executable Tests

```clojure
(ns logseq.outliner.spec
  (:require [clojure.test :refer [deftest]]
            [logseq.testkit.outliner :refer [setup-page select-block select-blocks collapse-block expand-block press type-text drag-to expect-outline expect-selection expect-caret expect-toast expect-focus expect-collapsed expect-expanded]]))

;; DSL primitives (must exist or be implemented to satisfy this spec):
;; setup-page :: vector -> page handle. Accepts nested vectors [:block-id "Text" children?].
;; select-block :: page -> block-id -> page. Highlights matching block.
;; select-blocks :: page -> [block-id ...] -> page. Highlights blocks in order.
;; press :: page -> "Key" -> page. Sends keyboard input to primary caret.
;; type-text :: page -> string -> page. Types characters at caret.
;; drag-to :: page -> {:block block-id :position :before|:after|:into} -> page. Performs pointer drag-and-drop.
;; collapse-block / expand-block :: toggles visible children.
;; expect-* assertions must check visible outline order, selection highlights, caret positions, toast banners, and collapsed markers exactly as the visitor sees them.

;; Move down keeps children visually attached
;; - A1            press "Cmd+Shift+Down" on A1         - B1
;;   - A1a   ->     (outline after)               ->         - A1
;; - B1                                              - C1    - A1a
;; - C1                                              - C1
(deftest move-block-down-keeps-children
  (-> (setup-page [[:a1 "A1" [[:a1a "A1a"]]]
                   [:b1 "B1" [[:c1 "C1"]]]
                   [:c2 "C2"]])
      (select-block :a1)
      (press "Cmd+Shift+Down")
      (expect-outline [[:b1 "B1" [[:c1 "C1"]]]
                       [:a1 "A1" [[:a1a "A1a"]]]
                       [:c2 "C2"]])
      (expect-selection [:a1])))

;; Indent turns sibling into child
;; - Root                press "Tab" on B1                - Root
;; - B1          ->       (caret moves right)      ->          - B1
;; - C1                                                      - C1
(deftest indent-promotes-to-child
  (-> (setup-page [[:root "Root"]
                   [:b1 "B1"]
                   [:c1 "C1"]])
      (select-block :b1)
      (press "Tab")
      (expect-outline [[:root "Root" [[:b1 "B1"]]]
                       [:c1 "C1"]])
      (expect-selection [:b1])
      (expect-caret :b1 0)))

;; Outdent lifts block to parent level
;; - Root               press "Shift+Tab" on B1          - Root
;;   - B1        ->      (caret moves left)        ->       - B1
;;   - C1                                               - C1
(deftest outdent-lifts-to-parent
  (-> (setup-page [[:root "Root" [[:b1 "B1"] [:c1 "C1"]]]])
      (select-block :b1)
      (press "Shift+Tab")
      (expect-outline [[:root "Root" [[:c1 "C1"]]]
                       [:b1 "B1"]])
      (expect-selection [:b1])
      (expect-caret :b1 0)))

;; Enter splits text at caret into two blocks
;; - "Alpha|Bravo"      press "Enter"                 - "Alpha"
;;                ->      (caret at split)      ->       - "Bravo"
(deftest enter-splits-block
  (-> (setup-page [[:b1 "AlphaBravo" :caret 5]])
      (select-block :b1)
      (press "Enter")
      (expect-outline [[:b1 "Alpha"]
                       [:b1a "Bravo"]])
      (expect-caret :b1a 0)
      (expect-selection [:b1a])))

;; Backspace merges with previous block when caret at start
;; - "Alpha"            press "Backspace" on second   - "Alpha Bravo"
;; - |"Bravo"    ->      (caret at start)         ->     (caret between words)
(deftest backspace-merges-into-previous
  (-> (setup-page [[:b1 "Alpha"]
                   [:b2 "Bravo" :caret 0]])
      (select-block :b2)
      (press "Backspace")
      (expect-outline [[:b1 "Alpha Bravo"]])
      (expect-caret :b1 6)
      (expect-selection [:b1])))

;; Multi-selection preserves relative order when moved
;; - A1                select [B1, B2], press "Cmd+Shift+Down" twice
;; - B1        ->                                               - A1
;; - B2                                                      - C1
;; - C1                                              ->       - B1
;; - C2                                                      - B2
;;                                                           - C2
(deftest move-multi-selection-retains-order
  (-> (setup-page [[:a1 "A1"] [:b1 "B1"] [:b2 "B2"] [:c1 "C1"] [:c2 "C2"]])
      (select-blocks [:b1 :b2])
      (press "Cmd+Shift+Down")
      (press "Cmd+Shift+Down")
      (expect-outline [[:a1 "A1"]
                       [:c1 "C1"]
                       [:b1 "B1"]
                       [:b2 "B2"]
                       [:c2 "C2"]])
      (expect-selection [:b1 :b2])))

;; Dragging clamp shows drop guide before target and respects descendants
;; - Parent            drag Parent into Child (invalid)      toast + unchanged
;;   - Child    ->      drag Parent after Sibling      ->      - Sibling
;; - Sibling                                               - Parent
;;                                                          - Child
(deftest drag-move-ignores-descendant-target
  (-> (setup-page [[:parent "Parent" [[:child "Child"]]]
                   [:sibling "Sibling"]])
      (select-block :parent)
      (drag-to {:block :child :position :into})
      (expect-outline [[:parent "Parent" [[:child "Child"]]]
                       [:sibling "Sibling"]])
      (expect-toast "Cannot nest block inside its own child")
      (drag-to {:block :sibling :position :after})
      (expect-outline [[:sibling "Sibling"]
                       [:parent "Parent" [[:child "Child"]]]])
      (expect-selection [:parent])))

;; Collapse hides children while preserving caret focus
;; - Parent [open] (expanded)   click collapse chevron           - Parent [closed]
;;   - Child        ->      (child rows hidden)      ->        (child hidden)
(deftest collapse-hides-children
  (-> (setup-page [[:parent "Parent" [[:child "Child"]]]])
      (expand-block :parent)
      (select-block :parent)
      (press "Ctrl+Alt+Left")
      (expect-outline [[:parent "Parent" [[:child "Child"]]]])
      (expect-collapsed :parent)
      (expect-selection [:parent])
      (expect-focus :parent)))

;; Expand reveals children again
;; - Parent [closed] (collapsed)   press shortcut or click triangle    - Parent [open]
;;                 ->        (child rows visible)         ->       - Child
(deftest expand-shows-children
  (-> (setup-page [[:parent "Parent" [[:child "Child"]]]])
      (collapse-block :parent)
      (press "Ctrl+Alt+Right")
      (expect-expanded :parent)
      (expect-outline [[:parent "Parent" [[:child "Child"]]]])
      (expect-selection [:parent])))
```

## 4. Edge Cases (failing-first, then codified above)

| Action              | Invalid when                                          | Observable outcome                                           |
| ------------------- | ----------------------------------------------------- | ------------------------------------------------------------ |
| Indent block        | Block is already first visible item on page           | Outline order unchanged, toast banner: "Cannot indent top-level block" |
| Outdent block       | Block has no parent (top level)                       | Outline order unchanged, toast banner: "Already top level"   |
| Move block via drag | Drop target is its own descendant                     | No outline change, toast banner: "Cannot nest block inside its own child" |
| Multi-select        | Selection mix includes ancestor and direct descendant | Selection highlight clears, toast banner: "Choose blocks on the same level" |
| Collapse block      | Block already collapsed                               | Chevron stays right-pointing, no children visible, no toast  |
| Split block         | Caret already at start of block                       | New empty block appears above, caret stays at start of original |

## Invariants

```clojure
(defn outline-invariants-hold?
  "Invariant checks that must succeed after every operation triggered above."
  [page]
  (and (no-duplicate-bullets? page)            ;; each visible bullet label is unique on screen
       (children-only-visible-when-parent? page) ;; collapsed markers hide descendants
       (selection-visible? page)               ;; every highlighted block is scrolled into view
       (caret-inside-visible-block? page)))    ;; caret never points into a hidden child
```

- 

## Review Checklist

- [ ] Every test above includes an ASCII snapshot describing before/after.
- [ ] Each edge case is backed by a toast assertion in tests.
- [ ] No banned implementation words appear.
- [ ] `outline-invariants-hold?` passes after every test step.