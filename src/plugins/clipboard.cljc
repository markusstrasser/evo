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
            [kernel.query :as q]
            [clojure.string :as str]
            [medley.core :as m]))

;; ── Helper Functions ──────────────────────────────────────────────────────────

(defn- url?
  "Check if text looks like a URL.
   Matches http://, https://, and www. prefixes."
  [text]
  (when (and text (not (str/blank? text)))
    (boolean (re-matches #"^(https?://|www\.)[^\s]+$" (str/trim text)))))

;; ── Smart URL Detection ─────────────────────────────────────────────────────────

(defn- youtube-url?
  "Check if URL is a YouTube video."
  [url]
  (when url
    (boolean (re-find #"(?i)(youtube\.com/(watch|embed|v|shorts)|youtu\.be/)" url))))

(defn- vimeo-url?
  "Check if URL is a Vimeo video."
  [url]
  (when url
    (boolean (re-find #"(?i)vimeo\.com/" url))))

(defn- twitter-url?
  "Check if URL is a Twitter/X tweet."
  [url]
  (when url
    (boolean (re-find #"(?i)(twitter\.com|x\.com)/[^/]+/status/" url))))

(defn- image-url?
  "Check if URL points to an image."
  [url]
  (when url
    (boolean (re-find #"(?i)\.(png|jpg|jpeg|gif|webp|svg|bmp)(\?|$)" url))))

(defn- classify-url
  "Classify a URL into its media type.
   Returns :youtube, :vimeo, :twitter, :image, or nil for regular URLs."
  [url]
  (cond
    (youtube-url? url) :youtube
    (vimeo-url? url) :vimeo
    (twitter-url? url) :twitter
    (image-url? url) :image
    :else nil))

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
        min-depth (when (seq all-blocks)
                    (apply min (map :depth all-blocks)))
        normalize-depth #(- % (or min-depth 0))
        ;; Build markdown text
        markdown (->> all-blocks
                      (map (fn [{:keys [id depth]}]
                             (str (indent-string (normalize-depth depth))
                                  "- "
                                  (q/block-text db id))))
                      (str/join "\n"))
        ;; Build block data for internal format
        block-data (mapv (fn [{:keys [id depth]}]
                           {:id id
                            :depth (normalize-depth depth)
                            :text (q/block-text db id)})
                         all-blocks)]
    {:text markdown
     :blocks block-data}))

;; ── Markdown Parsing ──────────────────────────────────────────────────────────

(defn- resolve-parent
  "Find the parent block for a block at the given depth.

   For depth 0: always use parent-id (top-level in paste context).
   For depth > 0: use the block at depth-1 from depth-map, or fallback to parent-id."
  [depth depth-map parent-id]
  (if (zero? depth)
    parent-id
    (or (get-in depth-map [(dec depth) :id])
        parent-id)))

(defn- resolve-sibling
  "Find the sibling block to place after, if any.

   Returns the ID of the last block at the same depth with the same parent,
   or nil if no such sibling exists.

   Special case: For depth 0 when first-block-depth is 0, use after-id
   (handles case where first block merged with editing block)."
  [depth actual-parent depth-map first-block-depth after-id]
  (let [sibling-entry (get depth-map depth)]
    (cond
      ;; Same depth AND same parent = sibling
      (and sibling-entry (= (:parent sibling-entry) actual-parent))
      (:id sibling-entry)

      ;; Depth 0 with no sibling in depth-map, but we have after-id
      ;; This handles the case where first block went to editing block
      (and (zero? depth) (= first-block-depth 0))
      after-id

      ;; No sibling found
      :else nil)))

(defn- update-depth-map
  "Update depth-map with the new block, clearing stale deeper entries.

   Records the new block at its depth, then removes all entries for depths
   greater than the current depth (they're no longer valid siblings)."
  [depth-map depth block-id parent-id]
  (->> depth-map
       (m/filter-keys #(<= % depth))
       (#(assoc % depth {:id block-id :parent parent-id}))))

(defn- make-block-ops
  "Create kernel operations for a single block.

   Returns [create-op place-op] where:
   - create-op creates the block with given text
   - place-op positions it under parent, after sibling (if any)"
  [block-id text parent-id place-after]
  (let [create-op {:op :create-node
                   :id block-id
                   :type :block
                   :props {:text text}}
        place-op {:op :place
                  :id block-id
                  :under parent-id
                  :at (if place-after
                        {:after place-after}
                        :first)}]
    [create-op place-op]))

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
  (when (seq parsed-blocks)
    (let [;; Get unique depths in sorted order, then map to sequential indexes
          depth-mapping (->> parsed-blocks
                             (map :depth)
                             distinct
                             sort
                             (m/indexed)
                             (into {} (map (fn [[idx depth]] [depth idx]))))]
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

   Algorithm:
   - Maintains a depth-map tracking the last block ID at each depth level
   - For each block at depth N:
     * Parent is the block at depth N-1 (or parent-id if N=0)
     * Sibling is the last block at depth N with the same parent
     * Clear depth-map entries deeper than N (no longer valid)

   Optional first-block-depth: when the first parsed block was merged into an
   existing block (the editing block), pass its depth here so subsequent children
   can find their parent. The after-id block will be registered at this depth.

   Returns {:ops [...] :last-block-id string}"
  ([parsed-blocks parent-id after-id]
   (blocks-to-ops parsed-blocks parent-id after-id nil))
  ([parsed-blocks parent-id after-id first-block-depth]
   (if (empty? parsed-blocks)
     {:ops [] :last-block-id after-id}
     (let [initial-depth-map (cond-> {}
                               first-block-depth (assoc first-block-depth
                                                        {:id after-id :parent parent-id}))]
       (loop [remaining-blocks parsed-blocks
              ops []
              depth-map initial-depth-map
              last-id after-id]
         (if (empty? remaining-blocks)
           {:ops ops :last-block-id last-id}
           (let [{:keys [depth text]} (first remaining-blocks)
                 new-id (str "block-" (random-uuid))

                 ;; Resolve parent and sibling for placement
                 actual-parent (resolve-parent depth depth-map parent-id)
                 place-after (resolve-sibling depth actual-parent depth-map
                                              first-block-depth after-id)

                 ;; Create operations for this block
                 [create-op place-op] (make-block-ops new-id text actual-parent place-after)

                 ;; Update depth map and continue
                 new-depth-map (update-depth-map depth-map depth new-id actual-parent)]

             (recur (rest remaining-blocks)
                    (conj ops create-op place-op)
                    new-depth-map
                    new-id))))))))

;; ── Paste Strategy Helpers ────────────────────────────────────────────────────

(defn- paste-strategy-inline
  "Simple inline paste: before + pasted + after."
  [before pasted after cursor-pos]
  {:first-text (str before pasted after)
   :remaining []
   :final-cursor (+ cursor-pos (count pasted))})

(defn- paste-strategy-url-link
  "Smart URL paste: creates markdown link [label](url)."
  [before after _cursor-pos selection pasted-url? pasted-text]
  (let [[label url-text] (if pasted-url?
                           [selection pasted-text]
                           [pasted-text selection])
        link-text (str "[" label "](" url-text ")")]
    {:first-text (str before link-text after)
     :remaining []
     :final-cursor (+ (count before) (count link-text))}))

(defn- paste-strategy-blank-lines
  "Blank line split: paragraphs become sibling blocks."
  [before pasted _after]
  (let [paragraphs (split-by-blank-lines pasted)
        first-para (first paragraphs)
        remaining (rest paragraphs)
        final-text (if (seq remaining)
                     (last remaining)
                     (str before first-para))]
    {:first-text (str before first-para)
     :remaining (mapv (fn [para] {:depth 0 :text (str/trim para)}) remaining)
     :first-depth 0  ;; Current block is at depth 0, so siblings chain after it
     :final-cursor (count (str/trim final-text))}))

(defn- paste-strategy-markdown
  "Markdown blocks: parse hierarchy, first block merges with before/after."
  [before pasted after]
  (let [parsed (parse-markdown-blocks pasted)
        first-parsed (first parsed)
        first-text (if (str/blank? before)
                     (:text first-parsed)
                     (str before (:text first-parsed)))
        remaining (rest parsed)]
    {:first-text (str first-text after)
     :remaining (vec remaining)
     :first-depth (:depth first-parsed)  ;; Pass depth so children can find parent
     :final-cursor (count first-text)}))

(defn- paste-strategy-clipboard-blocks
  "Internal clipboard format: uses exact block structure with depths."
  [before clipboard-blocks after cursor-pos]
  (let [first-block (first clipboard-blocks)
        remaining (rest clipboard-blocks)]
    {:first-text (str before (:text first-block) after)
     :remaining (vec remaining)
     :first-depth (:depth first-block)
     :final-cursor (if (seq remaining)
                     (count (:text (last remaining)))
                     (+ cursor-pos (count (:text first-block))))}))

(defn- paste-strategy-embed-url
  "Embeddable URL: create an :embed block for the URL.
   Returns :embed-block info that will be handled specially."
  [url url-type block-id]
  {:embed-block {:url (str/trim url)
                 :embed-type url-type
                 :after-id block-id}})

(defn- apply-paste-strategy
  "Apply paste strategy: update current block + create remaining blocks.

   Strategy map should contain:
   - :first-text - text for current block
   - :remaining - vector of {:depth :text} maps for new blocks
   - :final-cursor - cursor position in final block
   - :first-depth - (optional) depth of first block for clipboard format"
  [block-id parent {:keys [first-text remaining final-cursor first-depth]}]
  (let [update-op {:op :update-node
                   :id block-id
                   :props {:text first-text}}
        {:keys [ops last-block-id]} (blocks-to-ops remaining parent block-id first-depth)
        final-id (or last-block-id block-id)]
    {:ops (vec (cons update-op ops))
     :session-updates {:ui {:editing-block-id final-id
                            :cursor-position final-cursor}}}))

;; ── Intent Implementations ────────────────────────────────────────────────────

(intent/register-intent! :paste-text
                         {:doc "Paste text into editing block (Logseq parity).

         Detection order:
         0. If clipboard-blocks provided → use internal format (preserves exact hierarchy)
         0.5 Smart URL paste: If selection + URL pasted → [selection](URL)
                              If URL selected + text pasted → [text](URL)
         0.6 Embed URL paste: If pasting YouTube/Vimeo/Twitter URL into empty block → :embed block
         1. If has blank lines (\\n\\n) → split into sibling blocks (preserves raw text)
         2. If text looks like markdown blocks → parse as hierarchy
         3. Otherwise → inline paste

         Internal format takes precedence because it preserves the exact structure
         from copy/cut operations within the app."

                          :fr/ids #{:fr.clipboard/paste-multiline}
                          :allowed-states #{:editing}

                          :spec [:map
                                 [:type [:= :paste-text]]
                                 [:block-id :string]
                                 [:cursor-pos :int]
                                 [:selection-end {:optional true} :int]
                                 [:pasted-text :string]
                                 [:clipboard-blocks {:optional true} [:vector :map]]]

                          :handler
                          (fn [db _session {:keys [block-id cursor-pos selection-end pasted-text clipboard-blocks]}]
                            (let [current-text (q/block-text db block-id)
                                  sel-end (or selection-end cursor-pos)
                                  [before after] [(subs current-text 0 cursor-pos)
                                                  (subs current-text sel-end)]
                                  selection (when (not= cursor-pos sel-end)
                                              (subs current-text cursor-pos sel-end))
                                  parent (get-in db [:derived :parent-of block-id])
                                  [pasted-url? selection-url?] [(url? pasted-text) (url? selection)]
                                  smart-url? (and selection
                                                  (or (and pasted-url? (not selection-url?))
                                                      (and selection-url? (not pasted-url?))))
                                  ;; Check for embeddable URL on empty/nearly-empty block
                                  trimmed-paste (str/trim pasted-text)
                                  url-type (when (and pasted-url?
                                                      (str/blank? before)
                                                      (str/blank? after)
                                                      (not selection))
                                             (classify-url trimmed-paste))

                                  ;; Determine paste strategy based on content
                                  strategy (cond
                                             ;; Internal clipboard format (highest priority)
                                             (seq clipboard-blocks)
                                             (paste-strategy-clipboard-blocks before clipboard-blocks after cursor-pos)

                                             ;; Smart URL paste (selection + URL)
                                             smart-url?
                                             (paste-strategy-url-link before after cursor-pos selection pasted-url? pasted-text)

                                             ;; Embeddable URL on empty block → create :embed block
                                             url-type
                                             (paste-strategy-embed-url trimmed-paste url-type block-id)

                                             ;; Blank lines → paragraph split
                                             (has-blank-lines? pasted-text)
                                             (paste-strategy-blank-lines before pasted-text after)

                                             ;; Markdown blocks → hierarchy
                                             (markdown-blocks? pasted-text)
                                             (paste-strategy-markdown before pasted-text after)

                                             ;; Simple inline paste
                                             :else
                                             (paste-strategy-inline before pasted-text after cursor-pos))]

                              ;; Handle embed-block strategy specially
                              (if-let [{:keys [url embed-type after-id]} (:embed-block strategy)]
                                ;; Create an :embed block
                                (let [new-id (str "block-" (random-uuid))]
                                  {:ops [{:op :create-node
                                          :id new-id
                                          :type :embed
                                          :props {:url url :embed-type embed-type}}
                                         {:op :place
                                          :id new-id
                                          :under parent
                                          :at {:after after-id}}]
                                   :session-updates {:ui {:editing-block-id nil}
                                                     :selection {:nodes #{new-id}
                                                                 :focus new-id
                                                                 :anchor new-id}}})
                                ;; Normal paste strategy
                                (apply-paste-strategy block-id parent strategy))))})

(intent/register-intent! :copy-block
                         {:doc "Copy single block content to clipboard."
                          :fr/ids #{:fr.clipboard/copy-block}
                          :allowed-states #{:editing :selection}
                          :spec [:map [:type [:= :copy-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [text (q/block-text db block-id)]
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
                          :allowed-states #{:editing :selection}

                          :handler (fn [db session _intent]
                                     (let [selection (get-in session [:selection :nodes])
                                           editing-id (get-in session [:ui :editing-block-id])
                                           block-ids (cond
                                                       (seq selection) (vec selection)
                                                       editing-id [editing-id]
                                                       :else [])]
                                       (when (seq block-ids)
                                         ;; Always use blocks-to-markdown to include children
                                         ;; Single leaf blocks will still produce simple text
                                         (let [{:keys [text blocks]} (blocks-to-markdown db block-ids)]
                                           {:session-updates {:ui {:clipboard-text text
                                                                   :clipboard-blocks blocks}}}))))})

(intent/register-intent! :cut-block
                         {:doc "Cut single block (copy + move to trash)."
                          :fr/ids #{:fr.clipboard/copy-block}
                          :allowed-states #{:editing :selection}
                          :spec [:map [:type [:= :cut-block]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [text (q/block-text db block-id)]
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
                          :allowed-states #{:editing :selection}

                          :handler (fn [db session _intent]
                                     (let [selection (get-in session [:selection :nodes])
                                           editing-id (get-in session [:ui :editing-block-id])
                                           block-ids (cond
                                                       (seq selection) (vec selection)
                                                       editing-id [editing-id]
                                                       :else [])]
                                       (when (seq block-ids)
                                         ;; Always use blocks-to-markdown to include children
                                         (let [{:keys [text blocks]} (blocks-to-markdown db block-ids)
                                               ;; Find the topmost block (earliest in DOM order)
                                               first-block-id (apply min-key #(get-in db [:derived :pre %] 0) block-ids)
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
                                                                root-ids)
                                               selection-update (if prev-block-id
                                                                  ;; Select previous block (Logseq parity)
                                                                  {:nodes #{prev-block-id}
                                                                   :focus prev-block-id
                                                                   :anchor prev-block-id}
                                                                  ;; No previous block - clear selection
                                                                  {:nodes #{}
                                                                   :focus nil
                                                                   :anchor nil})]
                                           {:ops delete-ops
                                            :session-updates {:ui {:clipboard-text text
                                                                   :clipboard-blocks blocks
                                                                   :editing-block-id nil}
                                                              :selection selection-update}}))))})

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
