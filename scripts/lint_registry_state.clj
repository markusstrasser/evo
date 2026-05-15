#!/usr/bin/env bb

;; bb lint:registry-state — static verifier over keymap.bindings-data plus
;; comment/string-stripped intent and render registration discovery.
;;
;; Checks:
;;   1. Every keybinding's target intent type exists (declared via
;;      register-intent! somewhere in src/plugins or src/kernel) OR is
;;      explicitly allowlisted in shell-bypass-intents with a verified
;;      citation to its dispatch site.
;;   2. Normalized key tuples are unique inside each :global / :editing /
;;      :non-editing context.
;;   3. Block-owned editing-mode keys (Enter, Escape, Backspace, Delete,
;;      plain / Shift Arrow keys) do not appear in the :editing keymap.
;;   4. Render tags registered under src/shell/render/ are unique.
;;   5. Modifier values in keybinding specs are booleans — the lint
;;      normalizes truthy→true, but the runtime uses `=` against the DOM
;;      event modifiers, so a non-boolean would silently break matching.
;;   6. shell-bypass-intents entries name a real dispatch site whose file
;;      contains the cited intent name (defensive: prevents the allowlist
;;      drifting into a quiet escape hatch).
;;
;; Limitations:
;;   - Regex-based discovery means `(comment (register-intent! :x ...))`
;;     forms are NOT counted as registrations because the stripper drops
;;     line comments and string contents, but a non-line `(comment …)` form
;;     is structurally indistinguishable from a real call without a full
;;     reader. This is a known gap; the runtime integration test
;;     `test/integration/registry_idempotency_test.cljc` catches drift
;;     between the two views.
;;
;; A runtime idempotency check is intentionally NOT here — see
;; test/integration/registry_idempotency_test.cljc which boots the
;; ClojureScript runtime.

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[keymap.ownership-data :as ownership-data])

(def root (System/getProperty "user.dir"))

(defn read-file [path]
  (slurp (io/file root path)))

;; ── Clojure comment / string stripper ────────────────────────────────────────
;; Crude pre-processor that suppresses regex matches inside `;` line comments
;; and inside string literals. Not a full reader — does not handle the
;; `(comment …)` macro form, `#_form` discards, or arbitrary metadata. Good
;; enough to eliminate the two large false-positive classes (docstring
;; examples and `;;` commented call sites) without dragging in a reader.

(defn strip-noise [^String src]
  (let [sb (StringBuilder.)
        n (count src)]
    (loop [i 0
           in-string? false
           in-line-comment? false]
      (if (>= i n)
        (str sb)
        (let [c (.charAt src i)]
          (cond
            in-line-comment?
            (if (= c \newline)
              (do (.append sb \newline)
                  (recur (inc i) false false))
              (recur (inc i) false true))

            in-string?
            (cond
              (and (= c \\) (< (inc i) n))
              (recur (+ i 2) true false)
              (= c \")
              (do (.append sb \")
                  (recur (inc i) false false))
              :else
              (recur (inc i) true false))

            :else
            (cond
              (= c \;) (recur (inc i) false true)
              (= c \") (do (.append sb \")
                           (recur (inc i) true false))
              :else (do (.append sb c)
                        (recur (inc i) false false)))))))))

;; ── bindings-data loader ─────────────────────────────────────────────────────

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

;; ── intent registry (static discovery) ───────────────────────────────────────

(def intent-scan-roots ["src/plugins" "src/kernel"])

(defn cljc-files [root-path]
  (->> (file-seq (io/file root root-path))
       (filter #(.isFile %))
       (filter #(re-find #"\.(clj|cljs|cljc)$" (.getName %)))))

(def register-intent-regex
  #"\((?:[a-zA-Z0-9.\-]+/)?register-intent!\s+(:[a-zA-Z0-9\-/?.!*+_]+)")

(defn discover-registered-intents []
  (set
    (for [root intent-scan-roots
          file (cljc-files root)
          :let [stripped (strip-noise (slurp file))]
          match (re-seq register-intent-regex stripped)]
      (keyword (subs (second match) 1)))))

;; ── render registry (static discovery) ───────────────────────────────────────

(def render-scan-root "src/shell/render")

(def register-render-regex
  #"\((?:[a-zA-Z0-9.\-]+/)?register-render!\s+(:[a-zA-Z0-9\-/?.!*+_]+)")

(defn discover-render-registrations []
  (for [file (cljc-files render-scan-root)
        :let [stripped (strip-noise (slurp file))]
        [_ tag] (re-seq register-render-regex stripped)]
    {:tag (keyword (subs tag 1))
     :file (str (.getPath file))}))

;; ── normalization helpers ────────────────────────────────────────────────────

(defn normalized-spec [spec]
  (let [base (select-keys spec [:key])]
    (cond-> base
      (true? (:mod spec)) (assoc :mod true)
      (true? (:shift spec)) (assoc :shift true)
      (true? (:alt spec)) (assoc :alt true))))

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

;; ── shell-bypass allowlist (self-validating) ────────────────────────────────
;; Each entry must (a) cite an existing file, and (b) that file must literally
;; contain the intent keyword. If either fails the lint reports the bypass as
;; stale so the allowlist cannot rot into a silent escape hatch.

(def shell-bypass-intents
  {:undo  {:file "src/shell/global_keyboard.cljs"
           :why  "slog/undo! — replays session log directly, bypasses transaction pipeline"}
   :redo  {:file "src/shell/global_keyboard.cljs"
           :why  "slog/redo! — replays session log directly, bypasses transaction pipeline"}})

(defn check-shell-bypass-citations []
  (for [[intent {:keys [file]}] shell-bypass-intents
        :let [f (io/file root file)]
        msg (cond
              (not (.exists f))
              [(format "  bypass %s cites missing file %s"
                       (pr-str intent) file)]

              (not (str/includes? (slurp f) (pr-str intent)))
              [(format "  bypass %s cites %s but the file does not contain %s"
                       (pr-str intent) file (pr-str intent))]

              :else nil)]
    msg))

;; ── checks ───────────────────────────────────────────────────────────────────

(def block-owned-editing-specs
  ownership-data/block-owned-editing-key-specs)

(defn check-modifier-types [rows]
  (for [{:keys [context spec]} rows
        modifier [:mod :shift :alt]
        :let [v (get spec modifier)]
        :when (and (some? v) (not (boolean? v)))]
    (format "  %s %s has non-boolean %s = %s — runtime matches with `=`, not truthiness"
            context (pr-str spec) modifier (pr-str v))))

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

;; ── runner ───────────────────────────────────────────────────────────────────

(defn run []
  (let [bindings-data (load-bindings-data)
        registered-intents (discover-registered-intents)
        render-regs (discover-render-registrations)
        rows (flatten-bindings bindings-data)
        issues {:modifier-types        (check-modifier-types rows)
                :unknown-intents       (check-intents-exist rows registered-intents)
                :duplicate-keys        (check-keytuple-uniqueness rows)
                :block-owned-in-editing (check-no-block-owned-in-editing rows)
                :duplicate-render-tags (check-render-tag-uniqueness render-regs)
                :stale-bypass-citations (check-shell-bypass-citations)}
        any? (some seq (vals issues))]
    (println "Verifying registry state…")
    (println (format "  • %d shell keybinding rows across %d contexts"
                     (count rows)
                     (count (group-by :context rows))))
    (println (format "  • %d intent types declared via register-intent! (comments + strings stripped)"
                     (count registered-intents)))
    (println (format "  • %d render tags declared via register-render!"
                     (count render-regs)))
    (println (format "  • %d shell-bypass intents allowlisted (self-validated): %s"
                     (count shell-bypass-intents)
                     (pr-str (sort (keys shell-bypass-intents)))))
    (println (format "  • %d block-owned editing keys (from keymap.ownership-data)"
                     (count block-owned-editing-specs)))
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
