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

(defn- normalize-depths
  "Normalize parsed block depths to be sequential (0, 1, 2...).
   Closes gaps like [0, 2, 4] → [0, 1, 2].
   
   This handles cases where whitespace parsing produces non-sequential depths:
   - 4 spaces parsed as depth 2 (instead of depth 1)
   - Mixed tabs/spaces creating gaps"
  [parsed-blocks]
  (if (empty? parsed-blocks)
    parsed-blocks
    (let [;; Get unique depths in sorted order
          unique-depths (->> parsed-blocks
                             (map :depth)
                             distinct
                             sort
                             vec)
          ;; Map each raw depth to its normalized index
          depth-mapping (zipmap unique-depths (range))]
      (mapv #(update % :depth depth-mapping) parsed-blocks))))

(defn- parse-markdown-blocks
  "Parse markdown text into block tree structure.
   Returns vector of {:depth :text} maps with normalized sequential depths.

   Input format (Logseq-style):
   - Block 1
   \t- Child 1.1
   \t- Child 1.2
   - Block 2

   Lines without list markers are treated as continuations (appended to previous).
   Depths are normalized to close gaps (e.g., [0, 2, 4] → [0, 1, 2])."
  [text]
  (let [lines (str/split-lines text)]
    (->> lines
         (filter #(re-find #"^\s*[-*+]\s+" %)) ; Only process list items
         (mapv parse-markdown-line)
         normalize-depths)))

(defn- blocks-to-ops
  "Convert parsed blocks into kernel operations.
   
   Uses a map to track the last block at each depth level.
   When placing a block at depth N:
   - Parent is the last block at depth N-1 (or parent-id if N=0)
   - Place after the last sibling at depth N (if any under same parent)

   Optional first-block-depth: when the first parsed block was placed into an 
   existing block (the editing block), pass its depth here so children can 
   find their parent. The after-id block will be registered at this depth.

   Returns {:ops [...] :last-block-id string}"
  ([parsed-blocks parent-id after-id]
   (blocks-to-ops parsed-blocks parent-id after-id nil))
  ([parsed-blocks parent-id after-id first-block-depth]
   (if (empty? parsed-blocks)
     {:ops [] :last-block-id after-id}
     (loop [blocks parsed-blocks
            ops []
            ;; Map from depth -> {:id block-id :parent parent-id}
            ;; If first-block-depth provided, after-id is registered at that depth
            depth-map (cond-> {}
                        first-block-depth (assoc first-block-depth {:id after-id :parent parent-id}))
            last-id after-id]
       (if (empty? blocks)
         {:ops ops :last-block-id last-id}
         (let [{:keys [depth text]} (first blocks)
               new-id (str "block-" (random-uuid))

               ;; Find parent: 
               ;; - For depth 0: always use parent-id (they're top-level in paste context)
               ;; - For depth > 0: use the block at depth-1, or fallback to parent-id
               parent-depth (dec depth)
               actual-parent (if (neg? parent-depth)
                               parent-id
                               (if-let [parent-entry (get depth-map parent-depth)]
                                 (:id parent-entry)
                                 parent-id))

               ;; Find sibling: last block at same depth WITH same parent
               sibling-entry (get depth-map depth)
               place-after (cond
                             ;; Same depth AND same parent = sibling
                             (and sibling-entry (= (:parent sibling-entry) actual-parent))
                             (:id sibling-entry)

                             ;; Depth 0 with no sibling in depth-map, but we have after-id
                             ;; This handles the case where first block went to editing block
                             (and (zero? depth) (= first-block-depth 0))
                             after-id

                             ;; No sibling found
                             :else nil)

               create-op {:op :create-node
                          :id new-id
                          :type :block
                          :props {:text text}}

               place-op {:op :place
                         :id new-id
                         :under actual-parent
                         :at (if place-after
                               {:after place-after}
                               :first)}

               ;; Update depth map: record this block at its depth
               ;; Also clear deeper depths (they're no longer valid siblings)
               new-depth-map (-> depth-map
                                 (assoc depth {:id new-id :parent actual-parent})
                                 ;; Clear entries deeper than current
                                 (as-> m (reduce dissoc m
                                                 (filter #(> % depth) (keys m)))))]

           (recur (rest blocks)
                  (conj ops create-op place-op)
                  new-depth-map
                  new-id)))))))

;; ── Intent Implementations ────────────────────────────────────────────────────

(intent/register-intent! :paste-text
                         {:doc "Paste text into editing block (Logseq parity).

         Detection order:
         0. If clipboard-blocks provided → use internal format (preserves exact hierarchy)
         1. If has blank lines (\\n\\n) → split into sibling blocks (preserves raw text)
         2. If text looks like markdown blocks → parse as hierarchy
         3. Otherwise → inline paste

         Internal format takes precedence because it preserves the exact structure
         from copy/cut operations within the app."

                          :fr/ids #{:fr.clipboard/paste-multiline}

                          :spec [:map
                                 [:type [:= :paste-text]]
                                 [:block-id :string]
                                 [:cursor-pos :int]
                                 [:selection-end {:optional true} :int]
                                 [:pasted-text :string]
                                 [:clipboard-blocks {:optional true} [:vector :map]]]

                          :handler
                          (fn [db _session {:keys [block-id cursor-pos selection-end pasted-text clipboard-blocks]}]
                            (let [current-text (get-block-text db block-id)
                                  sel-end (or selection-end cursor-pos)
                                  before (subs current-text 0 cursor-pos)
                                  after (subs current-text sel-end)
                                  parent (get-in db [:derived :parent-of block-id])]

                              (cond
                                ;; Case 0: Internal clipboard-blocks format → use directly (preserves structure)
                                ;; This is the highest priority because it preserves the exact hierarchy from copy
                                (seq clipboard-blocks)
                                (let [;; First block's text goes into current block
                                      first-block (first clipboard-blocks)
                                      first-block-text (str before (:text first-block) after)
                                      remaining-blocks (rest clipboard-blocks)
                                      update-op {:op :update-node
                                                 :id block-id
                                                 :props {:text first-block-text}}
                                      ;; Convert remaining blocks to ops using their preserved depths
                                      ;; Pass first block's depth so children can find their parent
                                      {:keys [ops last-block-id]} (blocks-to-ops remaining-blocks parent block-id (:depth first-block))
                                      final-id (or last-block-id block-id)
                                      final-cursor-pos (if last-block-id
                                                         (count (:text (last remaining-blocks)))
                                                         (+ cursor-pos (count (:text (first clipboard-blocks)))))]
                                  {:ops (vec (cons update-op ops))
                                   :session-updates {:ui {:editing-block-id final-id
                                                          :cursor-position final-cursor-pos}}})

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
                                      first-parsed (first parsed)
                                      ;; If there's text before cursor, append first block to it
                                      ;; Otherwise replace current block content
                                      first-block-text (if (str/blank? before)
                                                         (:text first-parsed)
                                                         (str before (:text first-parsed)))
                                      remaining-parsed (rest parsed)
                                      ;; The text that will be in the current block after update
                                      current-block-final-text (str first-block-text after)
                                      ;; Update current block with first paragraph + any trailing text
                                      update-op {:op :update-node
                                                 :id block-id
                                                 :props {:text current-block-final-text}}
                                      ;; Create remaining blocks after current
                                      ;; Pass first block's depth so children can find their parent
                                      {:keys [ops last-block-id]} (blocks-to-ops remaining-parsed parent block-id (:depth first-parsed))
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
                                         ;; Always use blocks-to-markdown to include children
                                         ;; Single leaf blocks will still produce simple text
                                         (let [{:keys [text blocks]} (blocks-to-markdown db block-ids)]
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
         - Moves root selected blocks to trash (children follow automatically)
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
                                         ;; Always use blocks-to-markdown to include children
                                         (let [{:keys [text blocks]} (blocks-to-markdown db block-ids)
                                               ;; Find the topmost block (earliest in DOM order)
                                               first-block-id (->> block-ids
                                                                   (sort-by #(get-in db [:derived :pre %] 0))
                                                                   first)
                                               ;; Get previous block in visible order (Logseq parity)
                                               prev-block-id (nav/prev-visible-block db session first-block-id)
                                               ;; Only delete root blocks (those whose parents aren't selected)
                                               ;; Children will move to trash along with their parents
                                               id-set (set block-ids)
                                               root-ids (remove #(contains? id-set (get-in db [:derived :parent-of %]))
                                                                block-ids)
                                               delete-ops (mapv (fn [id]
                                                                  {:op :place
                                                                   :id id
                                                                   :under const/root-trash
                                                                   :at :last})
                                                                root-ids)]
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
