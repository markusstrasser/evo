(ns util.html-to-markdown
  "Convert HTML to Markdown for paste operations.

   Uses the turndown.js library for robust HTML→Markdown conversion.
   Handles common formatting: links, bold, italic, code, lists,
   blockquotes, headings, images, and tables.

   Based on Logseq's approach but using turndown instead of hickory."
  (:require [clojure.string :as str]
            ["turndown" :as TurndownService]))

(defonce turndown-instance
  (let [td (TurndownService. #js {:headingStyle "atx"
                                   :codeBlockStyle "fenced"
                                   :bulletListMarker "-"})]
    ;; Keep strikethrough handling (del, s, strike)
    (.addRule td "strikethrough"
              #js {:filter #js ["del" "s" "strike"]
                   :replacement (fn [content _node _options]
                                  (str "~~" content "~~"))})
    td))

(defn convert
  "Convert HTML string to markdown.

   Returns nil if HTML is blank or couldn't be converted.
   Returns the converted markdown string otherwise.

   Uses turndown.js for reliable HTML→Markdown conversion."
  [html]
  (when (and html (not (str/blank? html)))
    (try
      (let [result (.turndown turndown-instance html)]
        (when (and result (not (str/blank? result)))
          ;; Clean up excessive newlines
          (-> result
              (str/replace #"\n{3,}" "\n\n")
              str/trim)))
      (catch :default e
        (js/console.warn "HTML→Markdown conversion error:" e)
        nil))))

(defn better-than-plain?
  "Check if HTML-converted markdown is better than plain text.

   Returns true if the HTML conversion adds value (formatting, links, etc).
   This helps avoid using HTML conversion when it's just wrapped text."
  [html plain-text]
  (when-let [converted (convert html)]
    ;; HTML adds value if converted result differs significantly from plain text
    ;; (i.e., contains markdown formatting characters)
    (and (not= (str/trim converted) (str/trim plain-text))
         (or (str/includes? converted "[")    ; Links
             (str/includes? converted "**")   ; Bold
             (str/includes? converted "_")    ; Italic
             (str/includes? converted "`")    ; Code
             (str/includes? converted "#")    ; Headings
             (str/includes? converted "- ")   ; Lists
             (str/includes? converted "> ")   ; Blockquotes
             (str/includes? converted "~~")   ; Strikethrough
             (str/includes? converted "![")   ; Images
             (str/includes? converted "```")  ; Code blocks
             (str/includes? converted "|")))));; Tables
