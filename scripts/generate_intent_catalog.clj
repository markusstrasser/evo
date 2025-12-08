#!/usr/bin/env bb
;; Generate intent catalog markdown from source files
;; Usage: bb scripts/generate_intent_catalog.clj [--update]
;;   --update: Update CODING_GOTCHAS.md in place

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs])

(def intent-files
  ["src/plugins/editing.cljc"
   "src/plugins/navigation.cljc"
   "src/plugins/selection.cljc"
   "src/plugins/structural.cljc"
   "src/plugins/context_editing.cljc"
   "src/plugins/folding.cljc"
   "src/plugins/zoom.cljc"
   "src/plugins/clipboard.cljc"
   "src/plugins/formatting.cljc"
   "src/plugins/page_navigation.cljc"
   "src/plugins/slash_commands.cljc"])

(defn extract-intents [file-path]
  "Extract intent registrations from a file."
  (when (fs/exists? file-path)
    (let [content (slurp file-path)
          ;; Match register-intent! calls - capture intent keyword and first line of doc
          pattern #"register-intent!\s+:([a-z-]+)\s+\{:doc\s+\"([^\"]*)"
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
    (case fname
      "editing.cljc" "Editing"
      "navigation.cljc" "Navigation"
      "selection.cljc" "Selection"
      "struct.cljc" "Structure"
      "context_editing.cljc" "Smart Editing"
      "folding.cljc" "Folding"
      "zoom.cljc" "Zoom"
      "clipboard.cljc" "Clipboard"
      "formatting.cljc" "Formatting"
      "page_navigation.cljc" "Page Navigation"
      "slash_commands.cljc" "Slash Commands"
      "Other")))

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
