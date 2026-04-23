(ns parser.parse
  "Top-level text → AST composer.

   Walks the four block-content parsers in the same order the legacy
   nested renderer in `components.block` did, so layering (page-refs >
   images > markdown-links > inline-format) is preserved:

     text
       → page-refs (wraps `[[…]]`)
       → images    (wraps `![alt](path){width=N}` inside remaining :text)
       → md-links  (wraps `[label](url)` inside remaining :text)
       → inline    (bold/italic/highlight/strike/math inside :text)

   `mapcat-text` expands only `:text` leaves through the next parser;
   non-text nodes pass through untouched. Every parser in the chain
   returns a vector of AST nodes (see `parser.ast`).

   Emits a single `[:doc {} children]` root."
  (:require [parser.ast :as ast]
            [parser.page-refs :as page-refs]
            [parser.images :as images]
            [parser.markdown-links :as md-links]
            [parser.inline-format :as inline]))

(defn- mapcat-text
  "For each node in NODES: if the node is a :text leaf, replace it with
   the result of (expand-fn (:content node)). Otherwise pass through."
  [nodes expand-fn]
  (vec
    (mapcat (fn [node]
              (if (ast/text? node)
                (expand-fn (ast/content node))
                [node]))
            nodes)))

(defn parse
  "Parse TEXT into a fully-tagged AST rooted at `[:doc {} children]`."
  [text]
  (let [seeded [(ast/text-node text)]]
    [:doc {}
     (-> seeded
         (mapcat-text page-refs/parse)
         (mapcat-text images/parse)
         (mapcat-text md-links/parse)
         (mapcat-text inline/parse))]))

(defn render-as-source
  "Render an AST (or node) back to source markdown.
   Alias to `parser.ast/render-as-source`."
  [node-or-nodes]
  (ast/render-as-source node-or-nodes))
