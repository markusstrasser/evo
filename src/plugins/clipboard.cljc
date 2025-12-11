(ns plugins.clipboard
  "Clipboard operations: copy, cut, paste with Logseq parity.

   Copy semantics (Logseq parity):
   - Single block: plain text content
   - Multiple blocks: markdown with '- ' prefixes and tab indentation
   - UI layer writes both text/plain and internal MIME type

   Paste semantics (Logseq parity):
   - Check for internal format first (preserves hierarchy)
   - If blank lines (\\n\\n) → split into sibling blocks (preserves raw text)
   - If markdown blocks detected (lines starting with '- ') → parse as block tree
   - Otherwise → inline paste"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.navigation :as nav]
            [clojure.string :as str]))

;; ── Helper Functions ──────────────────────────────────────────────────────────

(defn- get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(defn- split-by-blank-lines
  "Split text by blank lines (two or more newlines).
   Returns vector of paragraphs (strings)."
  [text]
  (vec (str/split text #"\n\n+")))

(defn- has-blank-lines?
  "Check if text contains blank lines (double newline or more)."
  [text]
  (boolean (re-find #"\n\n" text)))

(defn- indent-string
  "Generate indentation string of N tabs."
  [n]
  (str/join (repeat n "\t")))

(defn- collect-block-tree
  "Collect a block and all its descendants in pre-order.
   Returns lazy seq of {:id :depth} maps."
  [db block-id base-depth]
  (let [children (get-in db [:children-by-parent block-id] [])]
    (cons {:id block-id :depth base-depth}
          (mapcat #(collect-block-tree db % (inc base-depth)) children))))

(defn- blocks-to-markdown
  "Convert blocks to markdown text preserving hierarchy.
   Each level of indentation uses a tab character.
   Format: each block prefixed with '- ' (Logseq markdown format).

   Returns {:text string :blocks vector} where:
   - :text is markdown for external apps
   - :blocks is block data for internal paste"
  [db block-ids]
  (let [id-set (set block-ids)
        ;; Collect trees, skipping blocks whose parents are also selected
        all-blocks (->> block-ids
                        (sort-by #(get-in db [:derived :pre %] 0))
                        (remove #(contains? id-set (get-in db [:derived :parent-of %])))
                        (mapcat #(collect-block-tree db % 0))
                        vec)
        ;; Normalize depths to start at 0
        min-depth (if (seq all-blocks)
                    (apply min (map :depth all-blocks))
                    0)
        ;; Build markdown text
        markdown (->> all-blocks
                      (map (fn [{:keys [id depth]}]
                             (str (indent-string (- depth min-depth))
                                  "- "
                                  (get-block-text db id))))
                      (str/join "\n"))
        ;; Build block data for internal format
        block-data (mapv (fn [{:keys [id depth]}]
                           {:id id
                            :depth (- depth min-depth)
                            :text (get-block-text db id)})
                         all-blocks)]
    {:text markdown
     :blocks block-data}))

;; ── Markdown Parsing ──────────────────────────────────────────────────────────

(defn- markdown-blocks?
  "Check if text looks like markdown block list.
   Matches lines starting with optional whitespace + '- ' or '* ' or '+ '."
  [text]
  (boolean (re-find #"(?m)^\s*[-*+]\s+" text)))

(defn- parse-markdown-line
  "Parse a single markdown line into {:depth :text}.
   Counts leading tabs/spaces for depth, strips list marker."
  [line]
  (let [;; Count leading whitespace (tabs = 1 depth, 2 spaces = 1 depth)
        leading (re-find #"^[\t ]*" line)
        tabs (count (filter #(= % \tab) leading))
        spaces (count (filter #(= % \space) leading))
        depth (+ tabs (quot spaces 2))
        ;; Strip leading whitespace and list marker
        content (str/replace line #"^[\t ]*[-*+]\s*" "")]
    {:depth depth
     :text (str/trim content)}))

(defn- parse-markdown-blocks
  "Parse markdown text into block tree structure.
   Returns vector of {:depth :text} maps.

   Input format (Logseq-style):
   - Block 1
   \t- Child 1.1
   \t- Child 1.2
   - Block 2

   Lines without list markers are treated as continuations (appended to previous)."
  [text]
  (let [lines (str/split-lines text)]
    (->> lines
         (filter #(re-find #"^\s*[-*+]\s+" %)) ; Only process list items
         (mapv parse-markdown-line))))

(defn- blocks-to-ops
  "Convert parsed blocks into kernel operations.
   Handles hierarchy by tracking parent stack and last sibling at each depth.

   Returns {:ops [...] :last-block-id string}"
  [parsed-blocks parent-id after-id]
  (if (empty? parsed-blocks)
    {:ops [] :last-block-id after-id}
    (loop [blocks parsed-blocks
           ops []
           ;; Stack of {:depth :parent-id :last-sibling-id} maps for tracking nesting
           ;; last-sibling-id tracks the most recent block at this depth for :after placement
           parent-stack [{:depth 0 :parent-id parent-id :last-sibling-id after-id}]
           last-id after-id]
      (if (empty? blocks)
        {:ops ops :last-block-id last-id}
        (let [{:keys [depth text]} (first blocks)
              new-id (str "block-" (random-uuid))

              ;; Pop stack until we find a depth less than current
              stack' (loop [s parent-stack]
                       (if (and (> (count s) 1)
                                (>= (:depth (first s)) depth))
                         (recur (rest s))
                         s))

              current-frame (first stack')
              prev-depth (:depth current-frame)

              ;; Determine parent and placement
              [actual-parent place-after]
              (cond
                ;; Going deeper: parent is the last block we created, place as first child
                (> depth prev-depth)
                [last-id nil]

                ;; Same or shallower: use the stack frame's parent and last sibling
                :else
                [(:parent-id current-frame) (:last-sibling-id current-frame)])

              ;; Create the block
              create-op {:op :create-node
                         :id new-id
                         :type :block
                         :props {:text text}}

              ;; Place operation
              place-op {:op :place
                        :id new-id
                        :under actual-parent
                        :at (if place-after
                              {:after place-after}
                              :first)}

              ;; Update stack: push new frame if going deeper, update last-sibling otherwise
              new-stack (if (> depth prev-depth)
                          ;; Push new frame for this depth level
                          (cons {:depth depth :parent-id actual-parent :last-sibling-id new-id} stack')
                          ;; Update current frame's last-sibling
                          (cons (assoc current-frame :last-sibling-id new-id) (rest stack')))]

          (recur (rest blocks)
                 (conj ops create-op place-op)
                 new-stack
                 new-id))))))

;; ── Intent Implementations ────────────────────────────────────────────────────

(intent/register-intent! :paste-text
                         {:doc "Paste text into editing block (Logseq parity).

         Detection order:
         1. If has blank lines (\\n\\n) → split into sibling blocks (preserves raw text)
         2. If text looks like markdown blocks → parse as hierarchy
         3. Otherwise → inline paste

         Blank lines take precedence because they indicate explicit paragraph breaks.
         Markdown parsing (which strips list markers) only applies to continuous lists."

                          :fr/ids #{:fr.clipboard/paste-multiline}

                          :spec [:map
                                 [:type [:= :paste-text]]
                                 [:block-id :string]
                                 [:cursor-pos :int]
                                 [:selection-end {:optional true} :int]
                                 [:pasted-text :string]]

                          :handler
                          (fn [db _session {:keys [block-id cursor-pos selection-end pasted-text]}]
                            (let [current-text (get-block-text db block-id)
                                  sel-end (or selection-end cursor-pos)
                                  before (subs current-text 0 cursor-pos)
                                  after (subs current-text sel-end)
                                  parent (get-in db [:derived :parent-of block-id])]

                              (cond
                                ;; Case 1: Has blank lines → split into sibling blocks (preserves raw text)
                                ;; This takes precedence because blank lines indicate explicit paragraph breaks
                                (has-blank-lines? pasted-text)
                                (let [paragraphs (split-by-blank-lines pasted-text)
                                      first-para (first paragraphs)
                                      remaining-paras (rest paragraphs)
                                      first-block-text (str before first-para)
                                      new-block-ids (repeatedly (count remaining-paras)
                                                                #(str "block-" (random-uuid)))
                                      update-current-op {:op :update-node
                                                         :id block-id
                                                         :props {:text first-block-text}}
                                      create-ops (mapv (fn [para new-id]
                                                         {:op :create-node
                                                          :id new-id
                                                          :type :block
                                                          :props {:text (str/trim para)}})
                                                       remaining-paras
                                                       new-block-ids)
                                      place-ops (mapv (fn [new-id prev-id]
                                                        {:op :place
                                                         :id new-id
                                                         :under parent
                                                         :at {:after prev-id}})
                                                      new-block-ids
                                                      (cons block-id (drop-last new-block-ids)))
                                      ;; Compute final position from actual text, not DB
                                      final-block-id (if (seq new-block-ids)
                                                       (last new-block-ids)
                                                       block-id)
                                      final-cursor-pos (if (seq new-block-ids)
                                                         (count (str/trim (last remaining-paras)))
                                                         (count first-block-text))]
                                  {:ops (vec (concat [update-current-op] create-ops place-ops))
                                   :session-updates {:ui {:editing-block-id final-block-id
                                                          :cursor-position final-cursor-pos}}})

                                ;; Case 2: Markdown blocks detected → parse as hierarchy
                                ;; Only applies to continuous lists without blank line breaks
                                (markdown-blocks? pasted-text)
                                (let [parsed (parse-markdown-blocks pasted-text)
                                      ;; If there's text before cursor, append first block to it
                                      ;; Otherwise replace current block content
                                      first-block-text (if (str/blank? before)
                                                         (:text (first parsed))
                                                         (str before (:text (first parsed))))
                                      remaining-parsed (rest parsed)
                                      ;; The text that will be in the current block after update
                                      current-block-final-text (str first-block-text after)
                                      ;; Update current block with first paragraph + any trailing text
                                      update-op {:op :update-node
                                                 :id block-id
                                                 :props {:text current-block-final-text}}
                                      ;; Create remaining blocks after current
                                      {:keys [ops last-block-id]} (blocks-to-ops remaining-parsed parent block-id)
                                      ;; Determine final block and cursor position from COMPUTED text, not DB
                                      final-id (or last-block-id block-id)
                                      final-cursor-pos (if last-block-id
                                                         ;; New block: cursor at end of last parsed block's text
                                                         (count (:text (last remaining-parsed)))
                                                         ;; Same block: cursor at end of first-block-text (before 'after')
                                                         (count first-block-text))]
                                  {:ops (vec (cons update-op ops))
                                   :session-updates {:ui {:editing-block-id final-id
                                                          :cursor-position final-cursor-pos}}})

                                ;; Case 3: Simple inline paste
                                :else
                                (let [new-text (str before pasted-text after)
                                      new-cursor-pos (+ cursor-pos (count pasted-text))]
                                  {:ops [{:op :update-node
                                          :id block-id
                                          :props {:text new-text}}]
                                   :session-updates {:ui {:editing-block-id block-id
                                                          :cursor-position new-cursor-pos}}}))))})

(intent/register-intent! :copy-block
                         {:doc "Copy single block content to clipboard."
                          :fr/ids #{:fr.clipboard/copy-block}
                          :spec [:map [:type [:= :copy-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [text (get-block-text db block-id)]
                                       {:session-updates {:ui {:clipboard-text text
                                                               :clipboard-blocks [{:id block-id
                                                                                   :depth 0
                                                                                   :text text}]}}}))})

(intent/register-intent! :copy-selected
                         {:doc "Copy selected blocks with hierarchy preservation.

         Returns both:
         - :clipboard-text - Markdown text for external apps
         - :clipboard-blocks - Block data for internal paste

         Logseq parity:
         - Multi-block selection copies all selected blocks + descendants
         - Preserves relative indentation using tabs
         - Format: each block prefixed with '- ' (list format)"

                          :fr/ids #{:fr.clipboard/copy-block}

                          :handler (fn [db session _intent]
                                     (let [selection (get-in session [:selection :nodes])
                                           editing-id (get-in session [:ui :editing-block-id])
                                           block-ids (cond
                                                       (seq selection) (vec selection)
                                                       editing-id [editing-id]
                                                       :else [])]
                                       (if (seq block-ids)
                                         (let [{:keys [text blocks]} (if (= 1 (count block-ids))
                                                                       ;; Single block: just the text (no formatting)
                                                                       {:text (get-block-text db (first block-ids))
                                                                        :blocks [{:id (first block-ids)
                                                                                  :depth 0
                                                                                  :text (get-block-text db (first block-ids))}]}
                                                                       ;; Multiple blocks: formatted with hierarchy
                                                                       (blocks-to-markdown db block-ids))]
                                           {:session-updates {:ui {:clipboard-text text
                                                                   :clipboard-blocks blocks}}})
                                         {:session-updates {}})))})

(intent/register-intent! :cut-block
                         {:doc "Cut single block (copy + move to trash)."
                          :fr/ids #{:fr.clipboard/copy-block}
                          :spec [:map [:type [:= :cut-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [text (get-block-text db block-id)]
                                       {:ops [{:op :place
                                               :id block-id
                                               :under const/root-trash
                                               :at :last}]
                                        :session-updates {:ui {:clipboard-text text
                                                               :clipboard-blocks [{:id block-id
                                                                                   :depth 0
                                                                                   :text text}]}}}))})

(intent/register-intent! :cut-selected
                         {:doc "Cut selected blocks with hierarchy preservation.

         Logseq parity:
         - Copies selected blocks + descendants with hierarchy
         - Moves all selected blocks to trash
         - Clears selection after cut
         - Focus moves to previous block in DOM order (cursor at end)"

                          :fr/ids #{:fr.clipboard/copy-block}

                          :handler (fn [db session _intent]
                                     (let [selection (get-in session [:selection :nodes])
                                           editing-id (get-in session [:ui :editing-block-id])
                                           block-ids (cond
                                                       (seq selection) (vec selection)
                                                       editing-id [editing-id]
                                                       :else [])]
                                       (if (seq block-ids)
                                         (let [{:keys [text blocks]} (if (= 1 (count block-ids))
                                                                       {:text (get-block-text db (first block-ids))
                                                                        :blocks [{:id (first block-ids)
                                                                                  :depth 0
                                                                                  :text (get-block-text db (first block-ids))}]}
                                                                       (blocks-to-markdown db block-ids))
                                               ;; Find the topmost block (earliest in DOM order)
                                               first-block-id (->> block-ids
                                                                   (sort-by #(get-in db [:derived :pre %] 0))
                                                                   first)
                                               ;; Get previous block in visible order (Logseq parity)
                                               prev-block-id (nav/prev-visible-block db first-block-id)
                                               ;; Move all selected blocks to trash
                                               delete-ops (mapv (fn [id]
                                                                  {:op :place
                                                                   :id id
                                                                   :under const/root-trash
                                                                   :at :last})
                                                                block-ids)]
                                           {:ops delete-ops
                                            :session-updates {:ui {:clipboard-text text
                                                                   :clipboard-blocks blocks
                                                                   :editing-block-id nil}
                                                              :selection (if prev-block-id
                                                                           ;; Select previous block (Logseq parity)
                                                                           {:nodes #{prev-block-id}
                                                                            :focus prev-block-id
                                                                            :anchor prev-block-id}
                                                                           ;; No previous block - clear selection
                                                                           {:nodes #{}
                                                                            :focus nil
                                                                            :anchor nil})}})
                                         {:session-updates {}})))})

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
