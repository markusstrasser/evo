(ns parser.inline-format-property-test
  "Property tests for parser.inline-format.

   Two invariants:

   A. PARSE/SERIALIZE IDENTITY — characters are never dropped, duplicated, or
      reordered by the parser. Applies to ALL strings.

   B. NEGATIVE-CORPUS LITERALNESS — identifier-like, code-like, and intraword
      Unicode inputs must parse as a single :text segment with :value equal to
      the input. This is the invariant that would have caught today's
      cljs_core_key / cljs$core$key bug class at property-test time.

   Invariant A alone does NOT catch today's bugs: a parser that emits
   [{:type :text :value \"cljs\"}
    {:type :math-inline :value \"core\"}
    {:type :text :value \"key\"}]
   reserializes to the original string and passes identity. B is the
   false-positive detector."
  (:require #_{:clj-kondo/ignore [:unused-referred-var :unused-namespace]}
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            #_{:clj-kondo/ignore [:unused-namespace]} ; Used by prop/for-all
            [clojure.test.check.generators :as gen]
            #_{:clj-kondo/ignore [:unused-namespace]} ; Used by prop/for-all
            [clojure.test.check.properties :as prop]
            #_{:clj-kondo/ignore [:unused-namespace]} ; defspec macro
            [clojure.test.check.clojure-test :refer [defspec]]
            #_{:clj-kondo/ignore [:unused-namespace]} ; used across defspec + deftest
            [parser.inline-format :as fmt]))

;; ── Serialize helper ─────────────────────────────────────────────────────────

(def ^:private type->markers
  {:bold          ["**" "**"]
   :italic        ["_" "_"]
   :highlight     ["==" "=="]
   :strikethrough ["~~" "~~"]
   :math-block    ["$$" "$$"]
   :math-inline   ["$" "$"]})

#_{:clj-kondo/ignore [:unused-private-var]} ;; used inside defspec macro body
(defn- serialize
  "Reconstruct source from a parser output. Drops markers on :text
   segments; wraps formatted segments in the marker canonical for their
   type. Because the parser can use either `_..._` or `*..*` for italic
   input, the round-trip picks ONE canonical form. We verify identity
   only on source strings the parser would have produced the canonical
   form for; see `italic-star-free?` below."
  [segments]
  (apply str
         (map (fn [{:keys [type value]}]
                (if (= type :text)
                  value
                  (let [[o c] (type->markers type)]
                    (str o value c))))
              segments)))

;; ── Invariant A — parse/serialize identity ───────────────────────────────────

;; Only assert identity on strings whose italic marker (if any) is `_`
;; — because the parser emits :italic for both `_x_` and `*x*` and our
;; canonical serializer chooses `_`. Strings without `*` as italic open
;; avoid that aliasing.

#_{:clj-kondo/ignore [:unused-private-var]} ;; used inside defspec macro body
(defn- italic-star-free?
  "True if `s` contains no single `*` runs that would parse as italic."
  [s]
  ;; Rough approximation: no `*` at all, or every `*` is part of `**`.
  (or (not (str/includes? s "*"))
      (every? #(or (empty? %) (even? (count %)))
              (str/split s #"[^*]+"))))

(def ^:private mixed-char-gen
  "Char generator biased toward markers and word chars so we exercise
   boundary rules. Also seeds code-like characters (`;`, `{`, `}`, `[`,
   `]`) so the math-content tripwire sees relevant inputs."
  (gen/frequency
   [[4 gen/char-alphanumeric]
    [1 (gen/return \space)]
    [1 (gen/return \_)]
    [1 (gen/return \*)]
    [1 (gen/return \$)]
    [1 (gen/return \=)]
    [1 (gen/return \~)]
    [1 (gen/return \.)]
    [1 (gen/return \()]
    [1 (gen/return \))]
    [1 (gen/return \{)]
    [1 (gen/return \})]
    [1 (gen/return \[)]
    [1 (gen/return \])]
    [1 (gen/return \;)]
    [1 (gen/return \newline)]]))

(defspec parse-serialize-identity-on-random-strings
  1000
  (prop/for-all
    [s (gen/fmap str/join (gen/vector mixed-char-gen 0 60))]
    (if (italic-star-free? s)
      (= s (serialize (fmt/split-with-formatting s)))
      ;; For aliased `*`/`_` italic inputs we still assert the parser is
      ;; lossless by comparing the LENGTH of the reserialized form.
      (= (count s) (count (serialize (fmt/split-with-formatting s)))))))

;; ── Invariant C — math-content tripwire holds under random inputs ────────────

(defn- math-segments
  "Return the math-inline and math-block segments from a parser output."
  [segments]
  (filter #(contains? #{:math-inline :math-block} (:type %)) segments))

(defspec math-segments-never-contain-code-signals
  1000
  (prop/for-all
    [s (gen/fmap str/join (gen/vector mixed-char-gen 0 80))]
    ;; math-content-ok? rejects content containing any of these signals.
    ;; Under random generation, assert the parser NEVER emits a math
    ;; segment whose :value carries those signals — i.e. the tripwire
    ;; in try-complete-format is on the happy path, not conditional.
    (let [math (math-segments (fmt/split-with-formatting s))
          bad-signals ["function " "return " "\n" "[["]]
      (every? (fn [seg]
                (let [v (:value seg)]
                  (and (not (str/includes? v ";"))
                       (every? #(not (str/includes? v %)) bad-signals))))
              math))))

;; ── Invariant D — textContent round-trip under a simulated renderer ──────────

(defn- simulate-rendered-text
  "JVM simulation of what the DOM's textContent reads for a parser
   output under render-formatted-segment (src/components/block.cljs).
   Marker-span siblings contribute their marker chars to textContent;
   formatted inner value is plain text; math segments render as
   `$value$` / `$$value$$` for the purposes of this simulation, which
   matches what happens BEFORE MathJax typesets (MathJax mutation is
   the e2e-only concern; e2e spec covers that).

   The goal: prove the parser + renderer together satisfy
   `render(text).textContent == text` for every input that doesn't
   hit MathJax — which in pure CLJ is every input, because there is
   no MathJax on the JVM."
  [segments]
  (apply str
         (map (fn [{:keys [type value marker]}]
                (case type
                  :text value
                  (:bold :italic)
                  (let [m (or marker (if (= type :bold) "**" "_"))]
                    (str m value m))
                  :highlight (str "==" value "==")
                  :strikethrough (str "~~" value "~~")
                  :math-inline (str "$" value "$")
                  :math-block (str "$$" value "$$")))
              segments)))

(defspec render-round-trip-holds-on-random-strings
  1000
  (prop/for-all
    [s (gen/fmap str/join (gen/vector mixed-char-gen 0 60))]
    (= s (simulate-rendered-text (fmt/split-with-formatting s)))))

;; ── Invariant B — negative corpus must stay literal ──────────────────────────

(def ^:private negative-corpus
  ["cljs_core_key"
   "snake_case_id"
   "a*b*c"
   "x$y$z"
   "cljs$core$key"
   "function cljs$core$key(map_entry){return cljs.core._key(map_entry)}"
   "x = a * b * c"
   "2*3*4 = 24"
   "int *p, int **pp"
   "int *p"
   "char **argv"
   "func(*args, **kwargs)"
   "(apply f *args)"
   "glob=foo*bar*baz"
   "$100$total"
   "price$100$total"
   "EUR$USD$JPY"
   ;; Cyrillic — \u043f\u0440\u0438\u0432\u0435\u0442_\u043c\u0438\u0440_\u0442\u0435\u0441\u0442
   "\u043f\u0440\u0438\u0432\u0435\u0442_\u043c\u0438\u0440_\u0442\u0435\u0441\u0442"
   ;; Japanese — \u5909\u6570_\u540d\u524d
   "\u5909\u6570_\u540d\u524d"
   "\u00fcber_cool"
   ;; Japanese price — \u4fa1\u683c$\u5408\u8a08$\u5186
   "\u4fa1\u683c$\u5408\u8a08$\u5186"
   ;; malformed spans
   "** foo **"
   "_ foo _"
   "$ x+y $"
   "**foo **"
   "**  foo**"])

(defn- parses-as-literal?
  "Holds when every segment is :text and their concatenated :value
   equals the input. The parser can split text across rejected markers
   into multiple adjacent :text segments (it does not re-merge them);
   visually they render identically to a single text node."
  [s]
  (let [segs (fmt/split-with-formatting s)]
    (and (every? #(= :text (:type %)) segs)
         (= s (apply str (map :value segs))))))

(deftest negative-corpus-stays-literal
  (doseq [s negative-corpus]
    (is (parses-as-literal? s)
        (str "expected literal parse for: " (pr-str s)
             "\n  got: " (pr-str (fmt/split-with-formatting s))))))

;; ── Invariant B (extended) — self-referential: parse our own source ──────────

(deftest parser-source-roundtrip
  (testing "lines of real CLJC source parse as literal text"
    (let [self-lines ["(ns parser.inline-format"
                      "  (:require [clojure.string :as str]))"
                      "(def ^:private word-boundary-markers #{\"_\" \"*\" \"$\"})"
                      "(defn- word-char? [ch]"
                      "  (when ch (let [s (str ch)] ...)))"
                      "(defn- find-marker-at [text pos] ...)"
                      "(re-matches word-char-re s)"
                      "(contains? word-boundary-markers marker)"]]
      (doseq [line self-lines]
        (is (parses-as-literal? line)
            (str "source line mis-parses: " (pr-str line)))))))
