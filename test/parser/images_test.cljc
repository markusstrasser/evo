(ns parser.images-test
  "Tests for markdown image parser."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [parser.images :as images]))

;; ── Pattern Matching Tests ────────────────────────────────────────────────────

(deftest image-pattern-test
  (testing "basic image pattern matching"
    (is (re-find images/image-pattern "![alt](path.png)"))
    (is (re-find images/image-pattern "![](path.png)"))
    (is (re-find images/image-pattern "![A cat](../assets/cat.jpg)"))
    (is (re-find images/image-pattern "![](https://example.com/img.png)")))

  (testing "non-matching patterns"
    (is (nil? (re-find images/image-pattern "regular text")))
    (is (nil? (re-find images/image-pattern "[not an image](link)")))
    (is (nil? (re-find images/image-pattern "![incomplete")))
    (is (nil? (re-find images/image-pattern "")))))

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
