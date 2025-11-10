# Constants Reference

Quick reference for commonly used constants. Saves grep time.

**Source:** `src/kernel/constants.cljc`

## Root IDs

```clojure
;; Root nodes (keywords, no prefix)
:doc          ; Main document root
:trash        ; Deleted blocks go here
:session      ; Session-specific data (selection, UI state)
```

**Usage:**
```clojure
{:op :place :id block-id :under :doc :at :last}
{:op :place :id deleted-id :under :trash :at :last}  ; NOT :root-trash!
```

## Session Node IDs

```clojure
;; Session nodes (strings with slash)
"session/selection"   ; const/session-selection-id
"session/ui"          ; const/session-ui-id
```

**Usage:**
```clojure
(get-in db [:nodes "session/ui" :props :editing-block-id])
;; Or use constant:
(get-in db [:nodes const/session-ui-id :props :editing-block-id])
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
:id-by-pre       ; Reverse lookup: position → ID
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

### Get editing block ID
```clojure
(get-in db [:nodes const/session-ui-id :props :editing-block-id])
```

### Get selected block IDs
```clojure
(get-in db [:nodes const/session-selection-id :props :ids])
```

### Place block in trash
```clojure
{:op :place :id block-id :under :trash :at :last}
```
