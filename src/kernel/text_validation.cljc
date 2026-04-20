(ns kernel.text-validation
  "Write-side tripwire for block text. Rejects strings that cannot
   possibly represent legitimate user content — they can only have
   entered the buffer by way of a DOM scanner (MathJax, Prism, future
   scanners) having mutated the rendered glyphs and something downstream
   reading textContent back into the buffer.

   This is the architectural safety net for the 2026-04-19 incident
   (commit 0fb73022 and follow-ups): the parser + math-ignore contract
   holds today, but any regression or new scanner bypass would silently
   persist glyph bytes into the DB. This predicate turns silent
   corruption into a loud rejection at the single chokepoint every
   text write flows through (:create-node / :update-node).

   Three categories of reject:

   1. Unicode private-use-area codepoints (U+E000..U+F8FF). MathJax
      CHTML glyphs live here; legitimate user text never contains them.
   2. Embedded scanner markup: the literal substrings `<mjx-`,
      `<script`, `<style`. No legitimate text block contains these;
      their presence means HTML leaked into the extraction path.
   3. Control characters other than `\\n` and `\\t`. U+0000..U+001F
      except LF/HT/CR indicate binary leakage; DEL (U+007F) same.

   The predicate returns nil on success or a `{:reason :hint}` map on
   failure so the transaction layer can surface both the signal and
   the offending substring."
  (:require [clojure.string :as str]))

(def ^:private private-use-re
  "Matches any Unicode private-use-area codepoint U+E000..U+F8FF.
   CLJS needs /u for codepoint escapes; the JVM handles \\uE000 natively."
  #?(:clj  #"[\uE000-\uF8FF]"
     :cljs (js/RegExp. "[\\uE000-\\uF8FF]" "u")))

(def ^:private control-char-re
  "Matches control chars U+0000..U+001F except LF (U+000A), HT (U+0009),
   and CR (U+000D); plus DEL (U+007F)."
  #?(:clj  #"[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"
     :cljs (js/RegExp. "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]" "u")))

(def ^:private scanner-markup-substrings
  ["<mjx-" "<script" "<style"])

(defn- first-scanner-markup
  "Return the first scanner-markup substring found in text, or nil."
  [text]
  (some #(when (str/includes? text %) %) scanner-markup-substrings))

(defn invalid-text-reason
  "Return a `{:reason :hint}` map describing why `text` is invalid, or
   nil if the text is valid. Non-string inputs (nil, number, etc.) are
   treated as valid — schema validation elsewhere handles type shape.

   Predicate ordering matches confidence: private-use chars are the
   highest-signal MathJax leak indicator, scanner markup is the
   highest-signal HTML leak indicator, control chars are the fallback
   binary-leak indicator."
  [text]
  (when (string? text)
    (cond
      (re-find private-use-re text)
      {:reason :private-use-char
       :hint "Text contains Unicode private-use-area codepoint (U+E000..U+F8FF). This indicates MathJax/CHTML glyph bytes leaked into the buffer."}

      (first-scanner-markup text)
      {:reason :scanner-markup
       :hint (str "Text contains embedded scanner markup: "
                  (first-scanner-markup text)
                  ". HTML leaked into the text-extraction path.")}

      (re-find control-char-re text)
      {:reason :control-char
       :hint "Text contains disallowed control character (U+0000..U+001F except LF/HT/CR, or DEL)."})))

(defn valid-text?
  "True iff `text` passes the tripwire."
  [text]
  (nil? (invalid-text-reason text)))
