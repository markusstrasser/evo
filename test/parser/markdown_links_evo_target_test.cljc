(ns parser.markdown-links-evo-target-test
  "Targeted tests for `parse-evo-target` and `decode-url-component`.

   These cover the hardening landed after the /critique close review
   of the AST + render-registry refactor:
     - parse-evo-target must reject `evo://page/Foo/` (trailing slash
       ghost-page risk found by Gemini/GPT)
     - decode-url-component must not throw on malformed percent-encoding
       (would crash the render path for the whole block)"
  (:require [clojure.test :refer [deftest is testing]]
            [parser.markdown-links :as md-links]))

(deftest parse-evo-target-shapes
  (testing "page targets"
    (is (= {:type :page :page-name "Foo"}
           (md-links/parse-evo-target "evo://page/Foo")))
    (is (= {:type :page :page-name "Foo Bar"}
           (md-links/parse-evo-target "evo://page/Foo%20Bar"))
        "URL-encoded spaces decode to literal spaces"))

  (testing "journal targets"
    (is (= {:type :journal :iso-date "2026-04-22"}
           (md-links/parse-evo-target "evo://journal/2026-04-22"))))

  (testing "unrelated schemes"
    (is (nil? (md-links/parse-evo-target "https://example.com")))
    (is (nil? (md-links/parse-evo-target "evo://unknown/x")))
    (is (nil? (md-links/parse-evo-target nil)))))

(deftest parse-evo-target-rejects-trailing-slash
  (testing "trailing slash in page target is rejected — caught by review"
    ;; Before the fix, `#\"^evo://page/(.+)$\"` accepted this and parsed
    ;; page-name as \"Foo/\", which the navigate handler would have
    ;; created as a ghost page. Tightened regex must refuse it.
    (is (nil? (md-links/parse-evo-target "evo://page/Foo/"))))
  (testing "journal already regex-bound, no trailing-slash case"
    (is (nil? (md-links/parse-evo-target "evo://journal/2026-04-22/")))))

(deftest decode-survives-malformed-percent-encoding
  (testing "malformed %-encoding does not throw on parse-evo-target — caught by review"
    ;; Before the try/catch, js/decodeURIComponent on %ZZ threw URIError
    ;; that unwound through render-node and unmounted the block.
    (doseq [bad ["evo://page/%"
                 "evo://page/%ZZ"
                 "evo://page/%E0%A4%A"
                 "evo://page/abc%"]]
      (is (some? (md-links/parse-evo-target bad))
          (str "parse must not throw on malformed input: " bad)))))
