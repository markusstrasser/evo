(ns parser.markdown-links
  "Parser for simple Markdown links and Evo-owned link targets.

   Supported shape:
   - [label](target)

   This parser is intentionally narrow:
   - labels cannot contain nested `]`
   - targets cannot contain whitespace
   - nested parentheses in targets are not supported

   That is sufficient for the initial `evo://page/<url-encoded-title>` flow."
  (:require [clojure.string :as str]))

(def ^:private link-pattern
  #"\[([^\]]+)\]\(([^)\s]+)\)")

(def ^:private link-only-pattern
  #"^\s*\[([^\]]+)\]\(([^)\s]+)\)\s*$")

(defn- decode-url-component
  [s]
  (when s
    #?(:clj (java.net.URLDecoder/decode s "UTF-8")
       :cljs (js/decodeURIComponent s))))

(defn split-with-links
  "Split text into markdown text/link segments.

   Example:
   (split-with-links \"See [Page](evo://page/My%20Page) now\")
   => [{:type :text :value \"See \"}
       {:type :link :label \"Page\" :target \"evo://page/My%20Page\"}
       {:type :text :value \" now\"}]"
  [text]
  (if (or (nil? text) (= "" text))
    [{:type :text :value (or text "")}]
    (loop [remaining text
           segments []]
      (if-let [match (re-find link-pattern remaining)]
        (let [[full label target] match
              start (str/index-of remaining full)
              before (subs remaining 0 start)
              after (subs remaining (+ start (count full)))
              next-segments (cond-> segments
                              (not= before "")
                              (conj {:type :text :value before})
                              true
                              (conj {:type :link
                                     :label label
                                     :target target}))]
          (recur after next-segments))
        (cond-> segments
          true (conj {:type :text :value remaining}))))))

(defn link-only?
  "If text is exactly one markdown link (ignoring surrounding whitespace),
   return {:label :target}. Else nil."
  [text]
  (when (string? text)
    (when-let [[_ label target] (re-matches link-only-pattern text)]
      {:label label
       :target target})))

(defn parse-evo-target
  "Parse an Evo-owned link target.

   Supported shapes:
   - evo://page/<url-encoded-page-title>      → {:type :page    :page-name s}
   - evo://block/<uuid-ish-id>                → {:type :block   :block-id s}
   - evo://journal/<yyyy-mm-dd>               → {:type :journal :iso-date s}"
  [target]
  (when (string? target)
    (cond
      (re-matches #"^evo://page/(.+)$" target)
      (let [[_ encoded-title] (re-matches #"^evo://page/(.+)$" target)]
        {:type :page :page-name (decode-url-component encoded-title)})

      (re-matches #"^evo://block/([^/\s]+)$" target)
      (let [[_ id] (re-matches #"^evo://block/([^/\s]+)$" target)]
        {:type :block :block-id id})

      (re-matches #"^evo://journal/(\d{4}-\d{2}-\d{2})$" target)
      (let [[_ iso] (re-matches #"^evo://journal/(\d{4}-\d{2}-\d{2})$" target)]
        {:type :journal :iso-date iso})

      :else nil)))

;; ── AST adapter ──────────────────────────────────────────────────────────────

(defn- segment->ast-node
  [{:keys [type value label target]}]
  (case type
    :text [:text {} (or value "")]
    :link [:link {:target target} [[:text {} (or label "")]]]))

(defn parse
  "Parse TEXT into a vector of AST nodes, extracting markdown links.

   See `parser.ast` for the node shape."
  [text]
  (mapv segment->ast-node (split-with-links text)))
