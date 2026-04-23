(ns parser.ast
  "AST shape + walking helpers + source-rendering inverse.

   Every node is a 3-element hiccup-style vector:
     [:tag {attrs} content]

   - Position 0: keyword tag (from `known-tags`)
   - Position 1: attrs map (always present, possibly `{}`)
   - Position 2: content — either a string (for `:text`, `:math-inline`,
     `:math-block`) or a vector of child nodes (for everything else). The
     shape is uniform so `(nth node 1)` is always attrs and `(nth node 2)`
     always content — no branching on arity in the walker.

   The `:marker` attribute on `:bold`, `:italic`, `:highlight`,
   `:strikethrough` preserves the exact marker the user typed (`**` vs
   `__`, `*` vs `_`) so clipboard round-trip keeps source stable. Never
   canonicalize."
  (:require [clojure.string :as str]))

(def known-tags
  #{:text :bold :italic :highlight :strikethrough
    :math-inline :math-block :link :page-ref :image :doc})

(defn tag      [node] (nth node 0))
(defn attrs    [node] (nth node 1))
(defn content  [node] (nth node 2))

(defn text?    [node] (= :text (tag node)))

(defn leaf?
  "True for nodes whose `content` is a raw string or empty children vector —
   i.e. nothing to recurse into."
  [node]
  (let [t (tag node)]
    (or (= :text t)
        (#{:math-inline :math-block :image} t))))

(defn children
  "Return the vector of child nodes, or [] for leaves."
  [node]
  (if (leaf? node)
    []
    (content node)))

(defn node?
  "Shape predicate. Used in tests, not on the render hot path."
  [x]
  (and (vector? x)
       (= 3 (count x))
       (contains? known-tags (nth x 0))
       (map? (nth x 1))
       (or (string? (nth x 2))
           (and (vector? (nth x 2))
                (every? node? (nth x 2))))))

;; ── Source rendering (inverse of parse) ──────────────────────────────────────

(declare render-as-source)

(defn- render-children [nodes]
  (apply str (map render-as-source nodes)))

(defn render-as-source
  "Emit source markdown from an AST node (or a vector of nodes).

   Inverse of `parser.parse/parse`: `(parse (render-as-source (parse s)))`
   must equal `(parse s)` for every source `s` the parser produces. The
   inverse holds on parser output, not on every arbitrary AST — e.g. an
   italic node with `:marker \"*\"` wrapping text with leading whitespace
   won't round-trip because the parser rejects `* foo *`. Tests stay on
   parser outputs.

   Accepts either a single node or a vector of sibling nodes."
  [node-or-nodes]
  (cond
    ;; Sibling vector (untagged — caller passed (:content doc) etc.)
    (and (vector? node-or-nodes)
         (every? #(and (vector? %)
                       (contains? known-tags (first %)))
                 node-or-nodes))
    (render-children node-or-nodes)

    ;; Single node
    (vector? node-or-nodes)
    (let [[t a c] node-or-nodes]
      (case t
        :doc            (render-children c)
        :text           (str c)
        :bold           (let [m (or (:marker a) "**")] (str m (render-children c) m))
        :italic         (let [m (or (:marker a) "_")]  (str m (render-children c) m))
        :highlight      (str "==" (render-children c) "==")
        :strikethrough  (str "~~" (render-children c) "~~")
        :math-inline    (str "$" c "$")
        :math-block     (str "$$" c "$$")
        :link           (str "[" (render-children c) "](" (:target a) ")")
        :page-ref       (str "[[" (:name a) "]]")
        :image          (let [{:keys [alt path width]} a
                              base (str "![" (or alt "") "](" path ")")]
                          (if width (str base "{width=" width "}") base))))

    :else
    (str node-or-nodes)))

;; ── Construction helpers (internal convenience) ─────────────────────────────

(defn text-node
  "Build a :text leaf. Null-safe."
  [s]
  [:text {} (or s "")])

(defn blank?
  "True if a :text leaf has empty string content."
  [node]
  (and (text? node) (str/blank? (content node))))
