(ns plugins.clipboard
  "Clipboard operations: copy, cut, paste with Logseq parity.

   Paste semantics (FR-Clipboard-03):
   - Single newlines → stay inline as literal \\n
   - Blank lines (\\n\\n) → split into multiple blocks
   - Preserve list markers, checkboxes, formatting"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [clojure.string :as str]))

;; Sentinel for DCE prevention - referenced by spec.runner
(def loaded? true)

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

#_{:clj-kondo/ignore [:unused-private-var]} ; Scaffolded for smart paste
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

(defn- indent-string
  "Generate indentation string of N tabs."
  [n]
  (str/join (repeat n "\t")))

#_{:clj-kondo/ignore [:unused-private-var]} ; Scaffolded for hierarchical copy
(defn- block-depth
  "Get depth of a block relative to a given root.
   Returns 0 for immediate children of root, 1 for grandchildren, etc."
  [db block-id root-id]
  (loop [current-id block-id
         depth 0]
    (let [parent-id (get-in db [:derived :parent-of current-id])]
      (cond
        (= current-id root-id) depth
        (nil? parent-id) depth
        (= parent-id root-id) 0
        :else (recur parent-id (inc depth))))))

(defn- collect-block-tree
  "Collect a block and all its descendants in pre-order.
   Returns lazy seq of {:id :depth} maps."
  [db block-id base-depth]
  (let [children (get-in db [:children-by-parent block-id] [])]
    (cons {:id block-id :depth base-depth}
          (mapcat #(collect-block-tree db % (inc base-depth)) children))))

#_{:clj-kondo/ignore [:unused-private-var]} ; Scaffolded for multi-block copy
(defn- find-common-parent
  "Find the common parent of a set of block IDs."
  [db block-ids]
  (when (seq block-ids)
    (let [first-id (first block-ids)
          first-parent (get-in db [:derived :parent-of first-id])]
      ;; Check if all blocks have the same parent
      (if (every? #(= first-parent (get-in db [:derived :parent-of %])) block-ids)
        first-parent
        ;; Fall back to doc root if different parents
        :doc))))

(defn- blocks-to-text-with-hierarchy
  "Convert blocks to text preserving hierarchy.
   Each level of indentation uses a tab character.

   Logseq parity: Multi-block copy preserves relative indentation."
  [db block-ids]
  (let [id-set (set block-ids)
        ;; Collect trees, skipping blocks whose parents are also selected
        all-blocks (->> block-ids
                        (sort-by #(get-in db [:derived :pre %] 0))
                        (remove #(contains? id-set (get-in db [:derived :parent-of %])))
                        (mapcat #(collect-block-tree db % 0))
                        vec)
        ;; Normalize depths to start at 0
        min-depth (apply min (map :depth all-blocks))]
    ;; Build indented text
    (->> all-blocks
         (map (fn [{:keys [id depth]}]
                (str (indent-string (- depth min-depth)) "- " (get-block-text db id))))
         (str/join "\n"))))

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

                          :fr/ids #{:fr.clipboard/paste-multiline}

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
                         {:doc "Copy single block content to clipboard.

         Returns nil (actual clipboard operation must be handled by UI layer
         using browser Clipboard API)."
                          :fr/ids #{:fr.clipboard/copy-block}
                          :spec [:map [:type [:= :copy-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     ;; Return block text in clipboard-compatible format
                                     ;; UI layer will handle navigator.clipboard.writeText()
                                     (let [text (get-block-text db block-id)]
                                       {:session-updates {:ui {:clipboard-text text}}}))})

(intent/register-intent! :copy-selected
                         {:doc "Copy selected blocks with hierarchy preservation.

         Logseq parity:
         - Multi-block selection copies all selected blocks + descendants
         - Preserves relative indentation using tabs
         - Format: each block prefixed with '- ' (list format)

         If no selection, copies current editing block.
         UI layer handles navigator.clipboard.writeText()."

                          :fr/ids #{:fr.clipboard/copy-block}

                          :handler (fn [db session _intent]
                                     (let [selection (get-in session [:selection :nodes])
                                           editing-id (get-in session [:ui :editing-block-id])
                                           block-ids (cond
                                                       (seq selection) (vec selection)
                                                       editing-id [editing-id]
                                                       :else [])]
                                       (if (seq block-ids)
                                         (let [text (if (= 1 (count block-ids))
                                                      ;; Single block: just the text (no formatting)
                                                      (get-block-text db (first block-ids))
                                                      ;; Multiple blocks: formatted with hierarchy
                                                      (blocks-to-text-with-hierarchy db block-ids))]
                                           {:session-updates {:ui {:clipboard-text text}}})
                                         {:session-updates {}})))})

(intent/register-intent! :cut-block
                         {:doc "Cut single block (copy + delete)."
                          :fr/ids #{:fr.clipboard/copy-block}
                          :spec [:map [:type [:= :cut-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [text (get-block-text db block-id)]
                                       {:ops [{:op :place
                                               :id block-id
                                               :under const/root-trash
                                               :at :last}]
                                        :session-updates {:ui {:clipboard-text text}}}))})

(intent/register-intent! :cut-selected
                         {:doc "Cut selected blocks with hierarchy preservation.

         Logseq parity:
         - Copies selected blocks + descendants with hierarchy
         - Moves all selected blocks to trash
         - Clears selection after cut

         If no selection, cuts current editing block."

                          :fr/ids #{:fr.clipboard/copy-block}

                          :handler (fn [db session _intent]
                                     (let [selection (get-in session [:selection :nodes])
                                           editing-id (get-in session [:ui :editing-block-id])
                                           block-ids (cond
                                                       (seq selection) (vec selection)
                                                       editing-id [editing-id]
                                                       :else [])]
                                       (if (seq block-ids)
                                         (let [;; Copy text with hierarchy
                                               text (if (= 1 (count block-ids))
                                                      (get-block-text db (first block-ids))
                                                      (blocks-to-text-with-hierarchy db block-ids))
                                               ;; Delete ops for all selected blocks
                                               delete-ops (mapv (fn [id]
                                                                  {:op :place
                                                                   :id id
                                                                   :under const/root-trash
                                                                   :at :last})
                                                                block-ids)]
                                           {:ops delete-ops
                                            :session-updates {:ui {:clipboard-text text
                                                                   :editing-block-id nil}
                                                              :selection {:nodes #{}
                                                                          :focus nil
                                                                          :anchor nil}}})
                                         {:session-updates {}})))})
