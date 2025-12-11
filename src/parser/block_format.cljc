(ns parser.block-format
  "Parser for block formatting prefixes (headings, quotes, embeds).

   Detects markdown-style formatting from text prefix:
   - Quote: starts with '> ' (space required)
   - Heading: starts with 1-6 '#' followed by space
   - Tweet embed: {{tweet URL}}
   - Video embed: {{video URL}}
   - Plain: everything else

   In view mode, renders with semantic HTML; in edit mode, shows raw markdown."
  (:require [clojure.string :as str]))

(defn parse
  "Parse text to detect formatting prefix.

   Returns {:format :quote/:heading/:tweet/:video/:plain
            :level n  (1-6 for headings, nil for others)
            :content \"text after prefix\"
            :url \"embed URL\" (for tweet/video)}"
  [text]
  (cond
    ;; Tweet embed: {{tweet URL}}
    (re-find #"^\{\{tweet\s+(.*?)\}\}$" (or text ""))
    (let [[_ url] (re-find #"^\{\{tweet\s+(.*?)\}\}$" text)]
      {:format :tweet
       :level nil
       :url (str/trim url)
       :content text})

    ;; Video embed: {{video URL}}
    (re-find #"^\{\{video\s+(.*?)\}\}$" (or text ""))
    (let [[_ url] (re-find #"^\{\{video\s+(.*?)\}\}$" text)]
      {:format :video
       :level nil
       :url (str/trim url)
       :content text})

    ;; Quote: > followed by space
    (str/starts-with? (or text "") "> ")
    {:format :quote
     :level nil
     :content (subs text 2)}

    ;; Heading: match #+ followed by space
    (re-find #"^(#{1,6}) " (or text ""))
    (let [[match hashes] (re-find #"^(#{1,6}) " text)
          heading-level (count hashes)]
      {:format :heading
       :level heading-level
       :content (subs text (count match))})

    ;; Plain text
    :else
    {:format :plain
     :level nil
     :content text}))
