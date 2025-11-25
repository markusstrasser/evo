(ns plugins.clipboard
  "Clipboard operations: copy, cut, paste with Logseq parity.

   Paste semantics (FR-Clipboard-03):
   - Single newlines → stay inline as literal \\n
   - Blank lines (\\n\\n) → split into multiple blocks
   - Preserve list markers, checkboxes, formatting"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [clojure.string :as str]))

;; ── Helper Functions ──────────────────────────────────────────────────────────

(defn- get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(defn- split-by-blank-lines
  "Split text by blank lines (two or more newlines).
   Returns vector of paragraphs (strings).

   Examples:
   'Hello\\nWorld' → ['Hello\\nWorld']
   'Para1\\n\\nPara2' → ['Para1' 'Para2']
   'A\\n\\nB\\n\\nC' → ['A' 'B' 'C']"
  [text]
  (vec (str/split text #"\n\n+")))

(defn- has-blank-lines?
  "Check if text contains blank lines (double newline or more)."
  [text]
  (boolean (re-find #"\n\n" text)))

(defn- detect-list-marker
  "Detect list marker at start of text.
   Returns {:type :bullet|:numbered|:checkbox :marker string :content string}
   or nil if no list marker."
  [text]
  (cond
    ;; Checkbox: [ ] or [x] or [X]
    (re-matches #"^(- \[[xX ]\] )(.*)$" text)
    (let [[_ marker content] (re-matches #"^(- \[[xX ]\] )(.*)$" text)]
      {:type :checkbox
       :marker marker
       :content content})

    ;; Numbered list: 1. 2. 3. etc.
    (re-matches #"^(\d+\. )(.*)$" text)
    (let [[_ marker content] (re-matches #"^(\d+\. )(.*)$" text)]
      {:type :numbered
       :marker marker
       :content content})

    ;; Bullet list: - or * or +
    (re-matches #"^([-*+] )(.*)$" text)
    (let [[_ marker content] (re-matches #"^([-*+] )(.*)$" text)]
      {:type :bullet
       :marker marker
       :content content})

    :else nil))

;; ── Intent Implementations ────────────────────────────────────────────────────

(intent/register-intent! :paste-text
                         {:doc "Paste text into editing block (Logseq parity).

         Behaviors (FR-Clipboard-03):
         - No blank lines → insert as-is (newlines become literal \\n)
         - Has blank lines → split into multiple blocks
         - Preserve list markers and checkboxes

         Algorithm:
         1. Split pasted text by blank lines (\\n\\n+)
         2. First paragraph → update current block
         3. Remaining paragraphs → create new blocks below
         4. Preserve list markers from pasted text"

                          :spec [:map
                                 [:type [:= :paste-text]]
                                 [:block-id :string]
                                 [:cursor-pos :int]
                                 [:pasted-text :string]]

                          :handler
                          (fn [db _session {:keys [block-id cursor-pos pasted-text]}]
                            (let [current-text (get-block-text db block-id)
                                  before (subs current-text 0 cursor-pos)
                                  after (subs current-text cursor-pos)
                                  parent (get-in db [:derived :parent-of block-id])]

                              (cond
                                ;; No blank lines → simple inline paste
                                (not (has-blank-lines? pasted-text))
                                (let [new-text (str before pasted-text after)
                                      new-cursor-pos (+ cursor-pos (count pasted-text))]
                                  {:ops [{:op :update-node
                                          :id block-id
                                          :props {:text new-text}}]
                                   :session-updates {:ui {:editing-block-id block-id
                                                          :cursor-position new-cursor-pos}}})

                                ;; Has blank lines → split into multiple blocks
                                :else
                                (let [paragraphs (split-by-blank-lines pasted-text)
                                      first-para (first paragraphs)
                                      remaining-paras (rest paragraphs)

                                      ;; Update current block with before + first paragraph
                                      first-block-text (str before first-para)

                                      ;; Create ops for remaining paragraphs
                                      new-block-ids (repeatedly (count remaining-paras)
                                                               #(str "block-" (random-uuid)))

                                      ;; Build operations
                                      update-current-op {:op :update-node
                                                        :id block-id
                                                        :props {:text first-block-text}}

                                      ;; Create new blocks with preserved markers
                                      create-ops (mapv (fn [para new-id]
                                                        {:op :create-node
                                                         :id new-id
                                                         :type :block
                                                         :props {:text (str/trim para)}})
                                                      remaining-paras
                                                      new-block-ids)

                                      ;; Place new blocks after current block
                                      place-ops (mapv (fn [new-id prev-id]
                                                       {:op :place
                                                        :id new-id
                                                        :under parent
                                                        :at {:after prev-id}})
                                                     new-block-ids
                                                     (cons block-id (drop-last new-block-ids)))

                                      ;; Position cursor after paste
                                      ;; If there's text after cursor, keep editing current block
                                      ;; Otherwise, move to last created block
                                      final-block-id (if (empty? after)
                                                      (last new-block-ids)
                                                      block-id)
                                      final-cursor-pos (if (empty? after)
                                                        (count (str/trim (last remaining-paras)))
                                                        (count first-block-text))]

                                  {:ops (vec (concat [update-current-op] create-ops place-ops))
                                   :session-updates {:ui {:editing-block-id final-block-id
                                                          :cursor-position final-cursor-pos}}}))))})

(intent/register-intent! :copy-block
                         {:doc "Copy block content to clipboard.

         Returns nil (actual clipboard operation must be handled by UI layer
         using browser Clipboard API)."
                          :spec [:map [:type [:= :copy-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     ;; Return block text in clipboard-compatible format
                                     ;; UI layer will handle navigator.clipboard.writeText()
                                     (let [text (get-block-text db block-id)]
                                       {:session-updates {:ui {:clipboard-text text}}}))})

(intent/register-intent! :copy-block-ref
                         {:doc "Copy block reference ((block-id)) to clipboard (Cmd+Option+C).

         UI layer must handle actual clipboard operation."
                          :spec [:map [:type [:= :copy-block-ref]] [:block-id :string]]
                          :handler (fn [_db _session {:keys [block-id]}]
                                     (let [ref-text (str "((" block-id "))")]
                                       {:session-updates {:ui {:clipboard-text ref-text}}}))})

(intent/register-intent! :cut-block
                         {:doc "Cut block (copy + delete).

         Moves block to trash after copying to clipboard."
                          :spec [:map [:type [:= :cut-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [text (get-block-text db block-id)]
                                       {:ops [{:op :place
                                               :id block-id
                                               :under const/root-trash
                                               :at :last}]
                                        :session-updates {:ui {:clipboard-text text}}}))})
