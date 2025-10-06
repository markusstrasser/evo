(ns lab.srs.markdown
  "Markdown file parsing and frontmatter extraction.
   Converts markdown files → SRS intents."
  (:require [clojure.string :as str]
            #?(:clj [clj-yaml.core :as yaml])))

;; ============================================================================
;; Frontmatter Parsing
;; ============================================================================

(defn parse-frontmatter
  "Extract YAML frontmatter from markdown.
   Returns: {:frontmatter {parsed-yaml}, :body 'markdown body'}"
  [markdown-text]
  (let [lines (str/split-lines markdown-text)]
    (if (and (str/starts-with? (first lines) "---")
             (> (count lines) 2))
      (let [;; Find closing ---
            end-idx (some #(when (str/starts-with? (nth lines %) "---") %)
                          (range 1 (count lines)))
            body-lines (if end-idx
                         (subvec (vec lines) (inc end-idx))
                         lines)
            body-str (str/join "\n" body-lines)]
        #?(:clj (let [frontmatter-str (str/join "\n" (subvec (vec lines) 1 (or end-idx 1)))]
                  {:frontmatter (yaml/parse-string frontmatter-str)
                   :body body-str})
           :cljs {:frontmatter {} ;; In CLJS, would need js-yaml
                  :body body-str}))
      ;; No frontmatter
      {:frontmatter {}
       :body markdown-text})))

(defn extract-sections
  "Extract # Front and # Back sections from markdown body.
   Returns: {:front 'text', :back 'text'}"
  [body]
  (let [lines (str/split-lines body)
        sections (reduce
                  (fn [acc line]
                    (cond
                      (str/starts-with? line "# Front")
                      (assoc acc :current-section :front)

                      (str/starts-with? line "# Back")
                      (assoc acc :current-section :back)

                      (str/starts-with? line "# Question")
                      (assoc acc :current-section :question)

                      (str/starts-with? line "# Notes")
                      (assoc acc :current-section :notes)

                      (str/starts-with? line "# Extra")
                      (assoc acc :current-section :extra)

                      :else
                      (if-let [section (:current-section acc)]
                        (update acc section
                                #(str % (when % "\n") line))
                        acc)))
                  {}
                  lines)]
    (-> sections
        (dissoc :current-section)
        (update :front str/trim)
        (update :back str/trim))))

;; ============================================================================
;; Markdown → Intent Conversion
;; ============================================================================

(defn markdown->create-card-intent
  "Convert a markdown file to a :srs/create-card intent.
   
   Example:
   (markdown->create-card-intent 
     'path/to/card.md'
     '---\\nid: card-1\\ntype: basic\\n...\\n# Front\\nWhat?\\n# Back\\nAnswer')"
  [file-path markdown-text]
  (let [{:keys [frontmatter body]} (parse-frontmatter markdown-text)
        sections (extract-sections body)
        card-type (keyword (get frontmatter "type" "basic"))
        card-id (get frontmatter "id" (str "card-" (hash file-path)))
        deck-id (get frontmatter "deck")
        tags (get frontmatter "tags" [])]

    (cond-> {:op :srs/create-card
             :card-id card-id
             :deck-id deck-id
             :card-type card-type
             :markdown-file file-path
             :front (get sections :front "")
             :back (get sections :back "")
             :tags tags}

      ;; Image occlusion specific
      (= card-type :image-occlusion)
      (assoc :props {:image-url (get-in frontmatter ["image" "url"])
                     :question (get sections :question "")
                     :answer (get sections :back "")
                     :occlusions (get frontmatter "occlusions" [])})

      ;; Add any extra frontmatter as props
      true
      (update :props merge (select-keys frontmatter ["difficulty" "created"])))))

;; ============================================================================
;; File Watching / Reconciliation
;; ============================================================================

(comment
  ;; Example usage:

  (def markdown-text
    "---
id: card-dna
type: basic
deck: biology
tags:
  - genetics
---

# Front
What is DNA?

# Back
Deoxyribonucleic acid")

  (parse-frontmatter markdown-text)
  ;; => {:frontmatter {"id" "card-dna", "type" "basic", ...}
  ;;     :body "# Front\n..."}

  (markdown->create-card-intent "cards/dna.md" markdown-text)
  ;; => {:op :srs/create-card
  ;;     :card-id "card-dna"
  ;;     :deck-id "biology"
  ;;     :card-type :basic
  ;;     :front "What is DNA?"
  ;;     :back "Deoxyribonucleic acid"
  ;;     ...}

  ;; In production, file watcher would:
  ;; 1. Detect markdown file change
  ;; 2. Parse frontmatter + body
  ;; 3. Generate create-card or update-card intent
  ;; 4. Compile to kernel ops
  ;; 5. Log transaction
  )
