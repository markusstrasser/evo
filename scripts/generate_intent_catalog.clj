#!/usr/bin/env bb
;; Generate intent catalog markdown from source files
;; Usage: bb scripts/generate_intent_catalog.clj [--update]
;;   --update: Update CODING_GOTCHAS.md in place

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs])

(def intent-files
  (->> (fs/glob "src/plugins" "*.cljc")
       (map str)
       sort))

(def domain-labels
  {"autocomplete.cljc" "Autocomplete"
   "clipboard.cljc" "Clipboard"
   "context_editing.cljc" "Smart Editing"
   "editing.cljc" "Editing"
   "folding.cljc" "Folding"
   "navigation.cljc" "Navigation"
   "pages.cljc" "Pages"
   "selection.cljc" "Selection"
   "structural.cljc" "Structure"
   "text_formatting.cljc" "Text Formatting"})

(defn titleize [s]
  (->> (str/split s #"[_-]+")
       (map str/capitalize)
       (str/join " ")))

(defn extract-intents [file-path]
  "Extract intent registrations from a file."
  (when (fs/exists? file-path)
    (let [content (slurp file-path)
          ;; Match register-intent! calls - capture intent keyword and first line of doc
          pattern #"register-intent!\s+:([a-z][a-z0-9-]*(?:/[a-z][a-z0-9-]*)?)\s+\{:doc\s+\"([^\"]*)"
          matches (re-seq pattern content)]
      (for [[_ intent-name doc-first-line] matches]
        {:intent (keyword intent-name)
         :doc (-> doc-first-line
                  (str/replace #"\s+" " ")
                  (str/trim)
                  (subs 0 (min 50 (count doc-first-line)))
                  (str (when (> (count doc-first-line) 50) "...")))}))))

(defn domain-from-file [path]
  (let [fname (fs/file-name path)]
    (or (get domain-labels fname)
        (-> fname
            (str/replace #"\.cljc$" "")
            titleize))))

(defn generate-catalog []
  (let [all-intents (for [f intent-files
                          :let [domain (domain-from-file f)
                                intents (extract-intents f)]
                          :when (seq intents)]
                      {:domain domain :intents intents})]
    (str "### Intent Catalog\n\n"
         "_Auto-generated from source. Run `bb lint:intents` to regenerate._\n\n"
         "Intents are dispatched via `api/dispatch`. Grouped by plugin:\n\n"
         (str/join "\n"
           (for [{:keys [domain intents]} all-intents]
             (str "**" domain ":**\n"
                  "| Intent | Description |\n"
                  "|--------|-------------|\n"
                  (str/join ""
                    (for [{:keys [intent doc]} intents]
                      (str "| `" intent "` | " doc " |\n")))
                  "\n"))))))

(defn update-gotchas! [catalog]
  (let [gotchas-path "docs/CODING_GOTCHAS.md"
        content (slurp gotchas-path)
        ;; Find the Intent Catalog section and replace it
        start-marker "### Intent Catalog"
        end-marker "---\n\n### Session State Shape"
        start-idx (str/index-of content start-marker)
        end-idx (str/index-of content end-marker)]
    (if (and start-idx end-idx)
      (let [new-content (str (subs content 0 start-idx)
                             catalog
                             "\n"
                             (subs content end-idx))]
        (spit gotchas-path new-content)
        (println "✓ Updated docs/CODING_GOTCHAS.md"))
      (println "⚠ Could not find Intent Catalog section markers"))))

(let [args (set *command-line-args*)
      catalog (generate-catalog)]
  (if (contains? args "--update")
    (update-gotchas! catalog)
    (println catalog)))
