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

;; Regex patterns for block format detection
(def ^:private tweet-pattern #"^\{\{tweet\s+(.*?)\}\}$")
(def ^:private video-pattern #"^\{\{video\s+(.*?)\}\}$")
(def ^:private heading-pattern #"^(#{1,6}) ")
(def ^:private quote-prefix "> ")

(defn parse
  "Parse text to detect formatting prefix.

   Returns {:format :quote/:heading/:tweet/:video/:plain
            :level n  (1-6 for headings, nil for others)
            :content \"text after prefix\"
            :url \"embed URL\" (for tweet/video)}"
  [text]
  (let [safe-text (or text "")
        ;; Format matchers: try each in order, return first match
        try-match
        (fn [[pattern-fn result-fn]]
          (when-let [match (pattern-fn safe-text)]
            (result-fn match)))]

    (or (some try-match
              [[#(re-find tweet-pattern %)
                (fn [match] {:format :tweet
                             :level nil
                             :url (str/trim (second match))
                             :content safe-text})]

               [#(re-find video-pattern %)
                (fn [match] {:format :video
                             :level nil
                             :url (str/trim (second match))
                             :content safe-text})]

               [#(str/starts-with? % quote-prefix)
                (fn [_] {:format :quote
                         :level nil
                         :content (subs safe-text (count quote-prefix))})]

               [#(re-find heading-pattern %)
                (fn [match]
                  (let [[full-match hashes] match]
                    {:format :heading
                     :level (count hashes)
                     :content (subs safe-text (count full-match))}))]])

        {:format :plain
         :level nil
         :content safe-text})))
