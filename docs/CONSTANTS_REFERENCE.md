# Constants Reference

Quick reference for commonly used constants. Saves grep time.

**Source:** `src/kernel/constants.cljc`

## Root IDs

```clojure
;; Root nodes (keywords, no prefix)
:doc          ; Main document root
:trash        ; Deleted blocks go here
```

**Usage:**
```clojure
{:op :place :id block-id :under :doc :at :last}
{:op :place :id deleted-id :under :trash :at :last}  ; NOT :root-trash!
```

## Session State (Separate Atom)

**IMPORTANT**: Session state no longer lives in DB. It's in a separate atom managed by `shell/session.cljs`.

```clojure
;; Session state structure (in session atom, NOT db)
{:cursor {:block-id nil :offset 0}
 :selection {:nodes #{} :focus nil :anchor nil :direction nil}
 :buffer {:block-id nil :text "" :dirty? false}
 :ui {:folded #{}
      :zoom-root nil
      :editing-block-id nil
      :cursor-position nil
      :cursor-memory nil}
 :sidebar {:right []}}
```

**Query session state:**
```clojure
(require '[kernel.query :as q])

;; Get editing block
(q/editing-block-id session)

;; Get selection
(q/selection session)           ; #{...} set of selected IDs
(q/selected? session "a")       ; Check if block selected
(q/focus session)               ; Current focus block

;; Get fold state
(q/folded? session "a")         ; Check if block folded
(q/zoom-root session)           ; Current zoom root
```

## Derived Index Keys

Available in `[:derived ...]`:

```clojure
:parent-of       ; {:map-of Id [:or Id :keyword]}
:index-of        ; {:map-of Id :int}
:prev-id-of      ; {:map-of Id [:maybe Id]}
:next-id-of      ; {:map-of Id [:maybe Id]}
:pre             ; Pre-order traversal positions
:post            ; Post-order traversal positions
:id-by-pre       ; Reverse lookup: position -> ID
:doc/pre         ; Doc-only pre-order
:doc/id-by-pre   ; Doc-only reverse lookup
```

**Common queries:**
```clojure
;; Get parent
(get-in db [:derived :parent-of block-id])

;; Get next sibling
(get-in db [:derived :next-id-of block-id])

;; Get children (NOT in derived - use direct)
(get-in db [:children-by-parent parent-id])

;; First child (compute it)
(first (get-in db [:children-by-parent parent-id]))
```

**Note:** There is NO `:first-child-of` in derived indexes!

## Common Patterns

### Check if block is deleted
```clojure
(= :trash (get-in db [:derived :parent-of block-id]))
```

### Check if block is being edited
```clojure
(= "a" (q/editing-block-id session))
```

### Get selected block IDs
```clojure
(q/selection session)  ; Returns #{...}
```

### Place block in trash
```clojure
{:op :place :id block-id :under :trash :at :last}
```
