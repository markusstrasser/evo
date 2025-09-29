(ns labs.refs
  "Reference system implementation as policy over core kernel.
   
   Refs are implemented as derived/indexed data over node props,
   not as kernel operations. This maintains the core algebra
   while providing graph powers.")

;; Placeholder for ref implementation
;; This would maintain {:backrefs-of {target #{source...}}} derived index
;; and provide ref-specific constraints and scrub rules