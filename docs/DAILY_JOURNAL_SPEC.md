# Daily Journal Specification

Based on Logseq's implementation. Source: a local upstream Logseq checkout.

## Overview

Daily journal pages are auto-created date-named pages that serve as the default landing page. They provide a frictionless capture surface for daily notes.

## Logseq Defaults

### Page Title Format
- **Default**: `"MMM do, yyyy"`
- **Example**: "Dec 11th, 2025"
- **Config key**: `:journal/page-title-format`

### Filename Format
- **Default**: `"yyyy_MM_dd"`
- **Example**: `2025_12_11.md`
- **Config key**: `:journal/file-name-format`
- **Directory**: `journals/` subdirectory

### Behavior

1. **App Start**: Navigate to today's journal page
2. **Auto-create**: If page doesn't exist, create it with empty first block
3. **Navigation**: Support prev/next day navigation

## Implementation for Evo

### Simplified Approach

Since Evo uses flat markdown files (not `journals/` subdirectory), journal pages are regular pages with date-formatted titles.

**Title format**: `"MMM do, yyyy"` (matches Logseq default)
- Dec 11th, 2025
- Jan 1st, 2024
- Feb 2nd, 2025

**Filename**: Sanitized from title → `dec-11th-2025.md`

### Key Functions

```clojure
;; Get today's journal title
(journal-title) ; => "Dec 11th, 2025"

;; Navigate to journal (create if needed)
(go-to-journal! db today-title)

;; Check if page is a journal
(journal-page? title) ; => true if matches date pattern
```

### Startup Behavior

On app load:
1. Check if today's journal page exists
2. If exists → navigate to it
3. If not → create page with title "Dec 11th, 2025" + empty block, navigate

### Date Formatting Notes

Using JavaScript's `Intl.DateTimeFormat` for locale-aware formatting:

```javascript
// "Dec 11th, 2025" format
const date = new Date();
const month = date.toLocaleString('en-US', { month: 'short' }); // "Dec"
const day = date.getDate(); // 11
const ordinal = getOrdinal(day); // "th"
const year = date.getFullYear(); // 2025
// → `${month} ${day}${ordinal}, ${year}`
```

Ordinal suffixes:
- 1, 21, 31 → "st"
- 2, 22 → "nd"
- 3, 23 → "rd"
- Everything else → "th"

## Future Enhancements

- [ ] Configurable date format
- [ ] Journal navigation (prev/next day)
- [ ] Journal-specific queries
- [ ] Week/month views
