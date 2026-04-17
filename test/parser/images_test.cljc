(ns parser.images-test
  "Tests for markdown image parser."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [parser.images :as images]))

;; ── Pattern Matching Tests ────────────────────────────────────────────────────

(deftest image?-basic-shapes
  (testing "basic image shapes are detected"
    (is (images/image? "![alt](path.png)"))
    (is (images/image? "![](path.png)"))
    (is (images/image? "![A cat](../assets/cat.jpg)"))
    (is (images/image? "![](https://example.com/img.png)")))

  (testing "images with width attribute"
    (is (images/image? "![alt](path.png){width=400}"))
    (is (images/image? "![](path.png){width=200}"))
    (is (images/image? "![cat](cat.jpg){width=1024}")))

  (testing "non-image strings are rejected"
    (is (not (images/image? "regular text")))
    (is (not (images/image? "[not an image](link)")))
    (is (not (images/image? "![incomplete")))
    (is (not (images/image? "")))))

;; ── Image Detection Tests ─────────────────────────────────────────────────────

(deftest image?-test
  (testing "detects images in text"
    (is (images/image? "![cat](cat.png)"))
    (is (images/image? "Some text ![img](path.png) more text"))
    (is (images/image? "![](empty-alt.png)")))

  (testing "returns falsy for non-images"
    (is (not (images/image? "regular text")))
    (is (not (images/image? "[link](url)")))
    (is (not (images/image? nil)))
    (is (not (images/image? "")))))

;; ── Extract Images Tests ──────────────────────────────────────────────────────

(deftest extract-images-test
  (testing "extracts single image"
    (is (= [{:alt "cat" :path "cat.png"}]
           (images/extract-images "![cat](cat.png)"))))

  (testing "extracts image with width"
    (is (= [{:alt "cat" :path "cat.png" :width 400}]
           (images/extract-images "![cat](cat.png){width=400}"))))

  (testing "extracts multiple images"
    (is (= [{:alt "first" :path "a.png"}
            {:alt "second" :path "b.jpg"}]
           (images/extract-images "![first](a.png) text ![second](b.jpg)"))))

  (testing "handles empty alt text"
    (is (= [{:alt "" :path "no-alt.png"}]
           (images/extract-images "![](no-alt.png)"))))

  (testing "returns nil for no images"
    (is (nil? (images/extract-images "no images here")))
    (is (nil? (images/extract-images nil)))))

;; ── Split With Images Tests ───────────────────────────────────────────────────

(deftest split-with-images-test
  (testing "splits text with single image"
    (is (= [{:type :text :value "Before "}
            {:type :image :alt "cat" :path "cat.png"}
            {:type :text :value " after"}]
           (images/split-with-images "Before ![cat](cat.png) after"))))

  (testing "handles text-only"
    (is (= [{:type :text :value "no images"}]
           (images/split-with-images "no images"))))

  (testing "handles image at start"
    (is (= [{:type :image :alt "img" :path "x.png"}
            {:type :text :value " after"}]
           (images/split-with-images "![img](x.png) after"))))

  (testing "handles image at end"
    (is (= [{:type :text :value "before "}
            {:type :image :alt "" :path "y.png"}]
           (images/split-with-images "before ![](y.png)"))))

  (testing "handles multiple images"
    (let [result (images/split-with-images "A ![1](a.png) B ![2](b.png) C")]
      (is (= 5 (count result)))
      (is (= :text (:type (first result))))
      (is (= :image (:type (second result))))
      (is (= "a.png" (:path (second result))))))

  (testing "handles image-only text"
    (is (= [{:type :image :alt "only" :path "single.png"}]
           (images/split-with-images "![only](single.png)"))))

  (testing "handles image with width"
    (is (= [{:type :image :alt "cat" :path "cat.png" :width 200}]
           (images/split-with-images "![cat](cat.png){width=200}"))))

  (testing "handles mixed text and image with width"
    (is (= [{:type :text :value "Before "}
            {:type :image :alt "cat" :path "cat.png" :width 300}
            {:type :text :value " after"}]
           (images/split-with-images "Before ![cat](cat.png){width=300} after"))))

  (testing "handles nil and empty"
    (is (= [{:type :text :value nil}]
           (images/split-with-images nil)))
    (is (= [{:type :text :value ""}]
           (images/split-with-images "")))))

;; ── Path Utilities Tests ──────────────────────────────────────────────────────

(deftest path->filename-test
  (testing "extracts filename from paths"
    (is (= "image.png" (images/path->filename "../assets/image.png")))
    (is (= "photo.jpg" (images/path->filename "./assets/photo.jpg")))
    (is (= "cat_123_0.png" (images/path->filename "assets/cat_123_0.png")))
    (is (= "simple.gif" (images/path->filename "simple.gif"))))

  (testing "handles URLs"
    (is (= "remote.png" (images/path->filename "https://example.com/images/remote.png"))))

  (testing "handles nil"
    (is (nil? (images/path->filename nil)))))

(deftest asset-path?-test
  (testing "recognizes asset paths"
    (is (images/asset-path? "../assets/image.png"))
    (is (images/asset-path? "./assets/photo.jpg"))
    (is (images/asset-path? "assets/file.png")))

  (testing "rejects non-asset paths"
    (is (not (images/asset-path? "https://example.com/image.png")))
    (is (not (images/asset-path? "http://cdn.com/img.jpg")))
    (is (not (images/asset-path? "/absolute/path.png")))
    (is (not (images/asset-path? "relative/other/path.png")))
    (is (not (images/asset-path? nil)))))

;; ── Format Image Tests ──────────────────────────────────────────────────────

(deftest format-image-test
  (testing "formats image without width"
    (is (= "![A cat](cat.png)" (images/format-image "cat.png" "A cat" nil)))
    (is (= "![](cat.png)" (images/format-image "cat.png" "" nil)))
    (is (= "![](cat.png)" (images/format-image "cat.png")))
    (is (= "![alt](cat.png)" (images/format-image "cat.png" "alt"))))

  (testing "formats image with width"
    (is (= "![](cat.png){width=400}" (images/format-image "cat.png" "" 400)))
    (is (= "![cat](cat.png){width=200}" (images/format-image "cat.png" "cat" 200)))))

;; ── Update Image Width Tests ────────────────────────────────────────────────

;; ── Balanced Parens in URL Path ─────────────────────────────────────────────

(deftest balanced-parens-in-path
  (testing "Wikipedia-style URL with one nested paren pair"
    (is (= [{:alt "" :path "https://en.wikipedia.org/wiki/Foo_(bar).png"}]
           (images/extract-images "![](https://en.wikipedia.org/wiki/Foo_(bar).png)"))))

  (testing "deeply nested balanced parens"
    (is (= [{:alt "d" :path "a(b)c/d(e(f)g)h.png" :width 400}]
           (images/extract-images "![d](a(b)c/d(e(f)g)h.png){width=400}"))))

  (testing "multiple images, each with nested parens"
    (let [r (images/extract-images
              "![a](one(ok).png) and ![b](two(x)(y).png)")]
      (is (= 2 (count r)))
      (is (= "one(ok).png" (get-in r [0 :path])))
      (is (= "two(x)(y).png" (get-in r [1 :path])))))

  (testing "unbalanced parens do not match"
    (is (nil? (images/extract-images "![broken](unclosed(paren.png"))))

  (testing "whitespace inside path terminates the match"
    (is (nil? (images/extract-images "![oops](has space.png)"))))

  (testing "split-with-images preserves context around nested-paren paths"
    (is (= [{:type :text :value "See "}
            {:type :image :alt "" :path "x/Foo_(bar).png"}
            {:type :text :value " here"}]
           (images/split-with-images "See ![](x/Foo_(bar).png) here")))))

(deftest update-image-width-test
  (testing "adds width to image without one"
    (is (= "![cat](cat.png){width=400}"
           (images/update-image-width "![cat](cat.png)" 400))))

  (testing "updates existing width"
    (is (= "![cat](cat.png){width=400}"
           (images/update-image-width "![cat](cat.png){width=200}" 400))))

  (testing "removes width when nil"
    (is (= "![cat](cat.png)"
           (images/update-image-width "![cat](cat.png){width=200}" nil))))

  (testing "returns non-image text unchanged"
    (is (= "regular text" (images/update-image-width "regular text" 400)))
    (is (= "" (images/update-image-width "" 400)))))
