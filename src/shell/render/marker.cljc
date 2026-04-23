(ns shell.render.marker
  "Visually-hidden span that carries a markdown marker (`**`, `_`, `==`,
   `~~`) into the DOM so native partial-text selection + clipboard copy
   sees the marker and preserves round-trippable source.

   Hidden from screen readers via aria-hidden — the semantic
   <strong>/<em>/<mark>/<del> tags already communicate intent.

   CSS class `.marker` is defined in public/styles.css with the standard
   clip/position:absolute pattern so the span takes zero visual space
   but is still part of selection and clipboard output.")

(defn marker-span
  "Return hiccup for a zero-visual-space marker carrying TEXT."
  [text]
  [:span.marker {:aria-hidden "true"} text])
