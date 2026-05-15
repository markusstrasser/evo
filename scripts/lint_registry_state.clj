#!/usr/bin/env bb

;; bb lint:registry-state — static verifier over keymap.bindings-data plus
;; grep-discovered intent and render registrations.
;;
;; Checks:
;;   1. Every keybinding's target intent type exists (declared somewhere via
;;      register-intent! in src/plugins/* or src/kernel/*).
;;   2. Normalized key tuples are unique inside each :global / :editing /
;;      :non-editing context.
;;   3. Block-owned editing-mode keys (Enter, Escape, Backspace, Delete, plain
;;      Arrow keys, Shift+Arrow keys) do not appear in the :editing keymap.
;;   4. Render tags registered under src/shell/render/ are unique.
;;
;; A runtime idempotency check is intentionally NOT here — see
;; test/plugins/registry_state_idempotency_test.cljc which boots the
;; ClojureScript runtime.

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (System/getProperty "user.dir"))

(defn read-file [path]
  (slurp (io/file root path)))

;; ── bindings-data loader ──────────────────────────────────────────────────────
;; The file is plain data after the ns form. Parse forms until we hit `def data`.

(defn read-forms [source]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. source))]
    (loop [acc []]
      (let [form (edn/read {:eof ::eof
                            :readers {'inst (fn [_] nil)}}
                           reader)]
        (if (= form ::eof)
          acc
          (recur (conj acc form)))))))

(defn load-bindings-data []
  (let [src (read-file "src/keymap/bindings_data.cljc")
        forms (read-forms src)
        def-form (some (fn [f]
                         (when (and (list? f)
                                    (= 'def (first f))
                                    (= 'data (second f)))
                           f))
                       forms)]
    (when-not def-form
      (throw (ex-info "Could not find `(def data ...)` in bindings_data.cljc" {})))
    (nth def-form 2)))

;; ── intent registry (static discovery) ────────────────────────────────────────

(def intent-scan-roots ["src/plugins" "src/kernel"])

(defn cljc-files [root-path]
  (->> (file-seq (io/file root root-path))
       (filter #(.isFile %))
       (filter #(re-find #"\.(clj|cljs|cljc)$" (.getName %)))))

;; Matches `register-intent! :foo` and `intent/register-intent! :foo` etc.
(def register-intent-regex
  #"\((?:[a-zA-Z0-9.\-]+/)?register-intent!\s+(:[a-zA-Z0-9\-/?.!*+_]+)")

(defn discover-registered-intents []
  (set
    (for [root intent-scan-roots
          file (cljc-files root)
          match (re-seq register-intent-regex (slurp file))]
      (keyword (subs (second match) 1)))))

;; ── render registry (static discovery) ────────────────────────────────────────

(def render-scan-root "src/shell/render")

(def register-render-regex
  #"\((?:[a-zA-Z0-9.\-]+/)?register-render!\s+(:[a-zA-Z0-9\-/?.!*+_]+)")

(defn discover-render-registrations []
  ;; Vector of {:tag :file :line} so duplicates report both sites.
  (for [file (cljc-files render-scan-root)
        :let [text (slurp file)]
        [_ tag] (re-seq register-render-regex text)]
    {:tag (keyword (subs tag 1))
     :file (str (.getPath file))}))

;; ── normalization helpers ─────────────────────────────────────────────────────

(defn normalized-spec [spec]
  ;; Drop falsy modifier keys so {:key "X"} ≡ {:key "X" :mod false}.
  (let [base (select-keys spec [:key])]
    (cond-> base
      (:mod spec) (assoc :mod true)
      (:shift spec) (assoc :shift true)
      (:alt spec) (assoc :alt true))))

(defn intent-key [target]
  (cond
    (keyword? target) target
    (map? target) (:type target)
    :else nil))

(defn flatten-bindings [bindings-map]
  (for [[context rows] bindings-map
        [spec target] rows]
    {:context context
     :spec spec
     :normalized (normalized-spec spec)
     :target target
     :intent (intent-key target)}))

;; ── checks ────────────────────────────────────────────────────────────────────

(def block-owned-editing-specs
  ;; Mirror of test/keymap/ownership_test.cljc :block-owned-editing-key-specs.
  #{{:key "Enter"}
    {:key "Enter" :shift true}
    {:key "Escape"}
    {:key "Backspace"}
    {:key "Delete"}
    {:key "ArrowUp"}
    {:key "ArrowDown"}
    {:key "ArrowLeft"}
    {:key "ArrowRight"}
    {:key "ArrowUp" :shift true}
    {:key "ArrowDown" :shift true}})

;; Intents handled by the shell without going through register-intent!.
;; Each one has a citation to the dispatch site so we can re-evaluate the
;; carve-out if the runtime changes.
(def shell-bypass-intents
  {:undo "src/shell/global_keyboard.cljs (slog/undo! — replays session log)"
   :redo "src/shell/global_keyboard.cljs (slog/redo! — replays session log)"})

(defn check-intents-exist [rows registered-intents]
  (for [{:keys [context spec intent]} rows
        :when (and intent
                   (not (registered-intents intent))
                   (not (contains? shell-bypass-intents intent)))]
    (format "  %s %s → target intent %s is not registered via register-intent!"
            context (pr-str spec) (pr-str intent))))

(defn check-keytuple-uniqueness [rows]
  (let [groups (group-by (juxt :context :normalized) rows)]
    (for [[[context norm] hits] groups
          :when (> (count hits) 1)]
      (format "  %s %s appears %d times — keys: %s"
              context (pr-str norm) (count hits)
              (pr-str (mapv :intent hits))))))

(defn check-no-block-owned-in-editing [rows]
  (let [editing-rows (filter #(= :editing (:context %)) rows)]
    (for [{:keys [spec normalized intent]} editing-rows
          :when (block-owned-editing-specs normalized)]
      (format "  :editing %s (target %s) collides with block-owned contenteditable key — see docs/KEYBOARD_OWNERSHIP.md"
              (pr-str spec) (pr-str intent)))))

(defn check-render-tag-uniqueness [render-regs]
  (let [groups (group-by :tag render-regs)]
    (for [[tag hits] groups
          :when (> (count hits) 1)]
      (format "  render tag %s registered %d times: %s"
              (pr-str tag) (count hits)
              (pr-str (mapv :file hits))))))

;; ── runner ────────────────────────────────────────────────────────────────────

(defn run []
  (let [bindings-data (load-bindings-data)
        registered-intents (discover-registered-intents)
        render-regs (discover-render-registrations)
        rows (flatten-bindings bindings-data)
        issues {:unknown-intents (check-intents-exist rows registered-intents)
                :duplicate-keys (check-keytuple-uniqueness rows)
                :block-owned-in-editing (check-no-block-owned-in-editing rows)
                :duplicate-render-tags (check-render-tag-uniqueness render-regs)}
        any? (some seq (vals issues))]
    (println "Verifying registry state…")
    (println (format "  • %d shell keybinding rows across %d contexts"
                     (count rows)
                     (count (group-by :context rows))))
    (println (format "  • %d intent types declared via register-intent!"
                     (count registered-intents)))
    (println (format "  • %d render tags declared via register-render!"
                     (count render-regs)))
    (println (format "  • %d shell-bypass intents allowlisted (replay log etc.): %s"
                     (count shell-bypass-intents)
                     (pr-str (sort (keys shell-bypass-intents)))))
    (println)
    (doseq [[label msgs] issues
            :when (seq msgs)]
      (println (str "✗ " (name label) ":"))
      (doseq [m msgs] (println m))
      (println))
    (if any?
      (do (println "✗ registry-state lint failed") (System/exit 1))
      (println "✓ registry-state lint passed"))))

(run)
