(ns spec-registry
  "Functional Requirements (FR) registry - Single Source of Truth.

   Design: Spec as Database Pattern
   - Registry stored as EDN data (resources/specs.edn)
   - Validated with Malli schema
   - Queryable at runtime for coverage auditing
   - Intents cite FR IDs; enforcement at registration time

   Philosophy: Requirements as data, not documentation.
   - Markdown generated FROM registry (not vice versa)
   - REPL-first introspection
   - Many-to-many mapping (intent ↔ FRs)
   - Dual coverage: implementation (intents) + verification (tests)

   Usage:
     (require '[spec-registry :as fr])
     (fr/load-registry!)           ; Load and validate specs.edn
     (fr/fr-exists? :fr.nav/vertical-cursor-memory)  ; Check FR
     (fr/get-fr :fr.nav/vertical-cursor-memory)      ; Get FR metadata
     (fr/audit-coverage)           ; Show implementation coverage"
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

;; ── Malli Schema for FR Registry ─────────────────────────────────────────────

(def fr-schema
  "Schema for a single Functional Requirement entry."
  [:map {:closed true}
   [:desc :string]
   [:priority [:enum :critical :high :medium :low]]
   [:type [:enum :intent-level :invariant :scenario]]
   [:status [:enum :active :deprecated]]
   [:version :int]
   [:tags [:vector :keyword]]
   [:spec-ref :string]])

(def registry-schema
  "Schema for the entire specs.edn registry.
   Map of namespaced keyword → FR metadata."
  [:map-of :keyword fr-schema])

;; ── Registry State ────────────────────────────────────────────────────────────

;; Embedded registry (loaded from resources/specs.edn at compile time)
;; This approach works in both CLJ and CLJS environments
(def embedded-registry
  {:fr.nav/vertical-cursor-memory
   {:desc "Vertical navigation preserves horizontal grapheme column across blocks"
    :priority :critical
    :type :intent-level
    :status :active
    :version 1
    :tags [:navigation :editing :cursor]
    :spec-ref "LOGSEQ_SPEC.md §2.1"}

   :fr.nav/horizontal-boundary
   {:desc "ArrowLeft at column 0 / ArrowRight at end jumps to adjacent block"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:navigation :editing]
    :spec-ref "LOGSEQ_SPEC.md §2.3"}

   :fr.nav/view-arrows
   {:desc "ArrowUp/Down in view mode moves focus through visible siblings, skipping folded blocks"
    :priority :critical
    :type :intent-level
    :status :active
    :version 1
    :tags [:navigation :selection]
    :spec-ref "LOGSEQ_SPEC.md §2.2"}

   :fr.nav/idle-first-last
   {:desc "ArrowDown/Up from idle state selects first/last visible block"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:navigation :idle]
    :spec-ref "LOGSEQ_SPEC.md §2.2"}

   :fr.selection/extend-boundary
   {:desc "Shift+Arrow extends selection incrementally with direction tracking"
    :priority :critical
    :type :intent-level
    :status :active
    :version 1
    :tags [:selection :keyboard]
    :spec-ref "LOGSEQ_SPEC.md §3"}

   :fr.selection/edit-view-exclusive
   {:desc "Edit mode and selection state never coexist; transitions clear opposite state"
    :priority :critical
    :type :invariant
    :status :active
    :version 1
    :tags [:state-machine :editing :selection]
    :spec-ref "LOGSEQ_SPEC.md §1.1"}

   :fr.edit/smart-split
   {:desc "Enter performs context-aware split; empty list items unformat + create peer in single step"
    :priority :critical
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :smart-editing]
    :spec-ref "LOGSEQ_SPEC.md §4"}

   :fr.edit/backspace-merge
   {:desc "Backspace at start merges into previous sibling, re-parents children, preserves caret"
    :priority :critical
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :merge]
    :spec-ref "LOGSEQ_SPEC.md §4"}

   :fr.struct/indent-outdent
   {:desc "Tab/Shift+Tab moves blocks under previous sibling / after parent; guards zoom roots"
    :priority :critical
    :type :intent-level
    :status :active
    :version 1
    :tags [:structural :tree]
    :spec-ref "LOGSEQ_SPEC.md §5.1"}

   :fr.struct/climb-descend
   {:desc "Cmd+Shift+Up/Down at boundaries climbs out to parent level / descends into next sibling"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:structural :move]
    :spec-ref "LOGSEQ_SPEC.md §5.2"}

   :fr.kernel/undo-restores-all
   {:desc "Undo/redo restores block content + caret position + selection state"
    :priority :critical
    :type :invariant
    :status :active
    :version 1
    :tags [:kernel :undo]
    :spec-ref "LOGSEQ_SPEC.md §7.8"}

   :fr.kernel/derive-indexes
   {:desc "All operations trigger automatic index derivation; never mutate derived state directly"
    :priority :critical
    :type :invariant
    :status :active
    :version 1
    :tags [:kernel :transaction]
    :spec-ref "CLAUDE.md - Canonical DB Shape"}

   ;; Editing (additional behaviors)
   :fr.edit/arrow-nav-mode
   {:desc "Arrow keys move cursor within block in Edit Mode"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :navigation]
    :spec-ref "LOGSEQ_SPEC.md §2.1 FR-NavEdit-01"}

   :fr.edit/shift-arrow-text-select
   {:desc "Shift+Arrow extends text selection within block (seeds multi-block selection)"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :selection]
    :spec-ref "LOGSEQ_SPEC.md §2.1 FR-NavEdit-02"}

   :fr.edit/delete-forward
   {:desc "Delete key removes character at cursor or merges with next block at end"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing]
    :spec-ref "LOGSEQ_SPEC.md §4 FR-Edit-03"}

   :fr.edit/word-navigation
   {:desc "Option+Arrow moves cursor by word boundaries"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :navigation]
    :spec-ref "LOGSEQ_SPEC.md §4 FR-Edit-04"}

   :fr.edit/kill-operations
   {:desc "Ctrl+K/U kill to end/beginning of line"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :emacs]
    :spec-ref "LOGSEQ_SPEC.md §4 FR-Edit-05"}

   :fr.edit/newline-no-split
   {:desc "Shift+Enter inserts newline without splitting block"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing]
    :spec-ref "LOGSEQ_SPEC.md §4 FR-Edit-06"}

   :fr.edit/paired-char-insertion
   {:desc "Auto-insert closing bracket/quote and place cursor inside"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:editing :smart-editing]
    :spec-ref "LOGSEQ_SPEC.md §4 FR-Edit-07"}

   ;; Clipboard
   :fr.clipboard/copy-block
   {:desc "Cmd+C copies selected blocks with metadata"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:clipboard]
    :spec-ref "LOGSEQ_SPEC.md §7.6 FR-Clipboard-01"}

   :fr.clipboard/paste-multiline
   {:desc "Pasting multi-line text creates multiple blocks"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:clipboard]
    :spec-ref "LOGSEQ_SPEC.md §7.7 FR-Clipboard-02"}

   :fr.clipboard/block-reference
   {:desc "Cmd+Shift+C copies block reference ((id))"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:clipboard :references]
    :spec-ref "LOGSEQ_SPEC.md §7.6 FR-Clipboard-03"}

   ;; Pointer/Mouse
   :fr.pointer/shift-click-range
   {:desc "Shift+Click extends selection range from current to clicked block"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:pointer :selection]
    :spec-ref "LOGSEQ_SPEC.md §7.3 FR-Pointer-01"}

   :fr.pointer/alt-click-fold
   {:desc "Alt+Click toggles fold state of subtree"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:pointer :folding]
    :spec-ref "LOGSEQ_SPEC.md §7.3 FR-Pointer-02"}

   ;; UI/Commands
   :fr.ui/slash-palette
   {:desc "Slash (/) opens inline command menu"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:ui :commands]
    :spec-ref "LOGSEQ_SPEC.md §7.4 FR-Slash-01"}

   :fr.ui/quick-switcher
   {:desc "Cmd+K opens global search overlay"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:ui :navigation]
    :spec-ref "LOGSEQ_SPEC.md §7.5 FR-QuickSwitch-01"}

   ;; State Machine
   :fr.state/idle-guard
   {:desc "Idle state requires explicit action to enter edit/selection; prevents accidental state changes"
    :priority :high
    :type :invariant
    :status :active
    :version 1
    :tags [:state-machine :idle]
    :spec-ref "LOGSEQ_SPEC.md §1.1 FR-Idle-01"}

   :fr.state/type-to-edit
   {:desc "Typing from idle/selection enters edit mode on first/selected block"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:state-machine :editing]
    :spec-ref "LOGSEQ_SPEC.md §1.1 FR-Idle-02"}

   :fr.state/selection-clears-edit
   {:desc "Entering selection mode clears editing state"
    :priority :high
    :type :invariant
    :status :active
    :version 1
    :tags [:state-machine]
    :spec-ref "LOGSEQ_SPEC.md §1.1 FR-State-02"}

   ;; Scope/Boundaries
   :fr.scope/visible-outline
   {:desc "Navigation stays within visible outline (respects page, zoom, folding)"
    :priority :high
    :type :invariant
    :status :active
    :version 1
    :tags [:navigation :scope]
    :spec-ref "LOGSEQ_SPEC.md §5, §6 FR-Scope-01"}

   :fr.scope/zoom-guards
   {:desc "Structural operations blocked by zoom boundary (can't indent/outdent zoom root)"
    :priority :medium
    :type :invariant
    :status :active
    :version 1
    :tags [:structural :zoom]
    :spec-ref "LOGSEQ_SPEC.md §6 FR-Scope-02"}

   ;; Folding/Zoom
   :fr.fold/toggle-block
   {:desc "Cmd+Up/Down toggles fold state of current block"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:folding]
    :spec-ref "LOGSEQ_SPEC.md §6"}

   :fr.fold/expand-collapse-all
   {:desc "Cmd+Shift+Up/Down expands/collapses all blocks in scope"
    :priority :low
    :type :intent-level
    :status :active
    :version 1
    :tags [:folding]
    :spec-ref "LOGSEQ_SPEC.md §6"}

   :fr.zoom/focus-subtree
   {:desc "Cmd+. zooms into block as temporary root"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:zoom :navigation]
    :spec-ref "LOGSEQ_SPEC.md §6"}

   :fr.zoom/restore-scope
   {:desc "Escape from zoom restores normal scope"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:zoom]
    :spec-ref "LOGSEQ_SPEC.md §6 FR-Scope-03"}

   ;; Structural (additional)
   :fr.struct/delete-block
   {:desc "Delete selected blocks and re-parent children to parent"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:structural]
    :spec-ref "LOGSEQ_SPEC.md §5"}

   :fr.struct/create-sibling
   {:desc "Create new block as sibling of current/selected"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:structural]
    :spec-ref "LOGSEQ_SPEC.md §5"}

   ;; Text Formatting
   :fr.format/bold-italic
   {:desc "Cmd+B/I wraps selection in **bold** or *italic* markers"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:formatting]
    :spec-ref "LOGSEQ_SPEC.md §7.9"}

   :fr.format/highlight-strikethrough
   {:desc "Cmd+H/Shift+H wraps selection in ^^highlight^^ or ~~strikethrough~~"
    :priority :low
    :type :intent-level
    :status :active
    :version 1
    :tags [:formatting]
    :spec-ref "LOGSEQ_SPEC.md §7.9"}

   ;; Pages/Links
   :fr.pages/switch-page
   {:desc "Navigate to different page (clears zoom, preserves page state)"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:pages :navigation]
    :spec-ref "LOGSEQ_SPEC.md §7.1"}

   :fr.pages/follow-link
   {:desc "Cmd+Click or Cmd+O on page reference navigates to that page"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:pages :links]
    :spec-ref "LOGSEQ_SPEC.md §7.2"}

   ;; Smart Editing (additional)
   :fr.smart/checkbox-toggle
   {:desc "Cmd+Enter toggles TODO/DOING/DONE markers"
    :priority :medium
    :type :intent-level
    :status :active
    :version 1
    :tags [:smart-editing]
    :spec-ref "LOGSEQ_SPEC.md §7.10"}

   :fr.smart/list-unformat
   {:desc "Enter on empty list item removes marker and creates peer at parent level"
    :priority :high
    :type :intent-level
    :status :active
    :version 1
    :tags [:smart-editing :lists]
    :spec-ref "LOGSEQ_SPEC.md §4 (part of FR-Edit-01)"}

   :fr.smart/auto-increment
   {:desc "Enter on numbered list auto-increments next item"
    :priority :low
    :type :intent-level
    :status :active
    :version 1
    :tags [:smart-editing :lists]
    :spec-ref "LOGSEQ_SPEC.md §7.10"}})

(defonce !registry
  (atom embedded-registry))

;; ── Loading & Validation ──────────────────────────────────────────────────────

(defn validate-registry!
  "Validate embedded registry against Malli schema.
   Throws if schema validation fails.

   Called automatically on namespace load."
  []
  (let [validator (m/validator registry-schema)]
    (when-not (validator embedded-registry)
      (let [explanation (m/explain registry-schema embedded-registry)
            humanized (me/humanize explanation)]
        (throw (ex-info "Invalid FR registry (embedded data)"
                        {:errors humanized
                         :explanation explanation
                         :hint "Check spec-registry.cljc embedded-registry definition"}))))
    embedded-registry))

(defn load-registry!
  "Validate and load embedded registry.
   Returns: Loaded registry map."
  []
  (let [validated (validate-registry!)]
    (reset! !registry validated)
    validated))

;; ── Query API ─────────────────────────────────────────────────────────────────

(defn fr-exists?
  "Check if an FR ID exists in the registry.

   Example:
     (fr-exists? :fr.nav/vertical-cursor-memory)  ;=> true
     (fr-exists? :fr.unknown/fake)                ;=> false"
  [fr-id]
  (contains? @!registry fr-id))

(defn get-fr
  "Get metadata for a specific FR.

   Returns: {:desc ... :priority :critical ...} or nil if not found.

   Example:
     (get-fr :fr.nav/vertical-cursor-memory)
     ;=> {:desc \"Vertical navigation preserves...\" :priority :critical ...}"
  [fr-id]
  (get @!registry fr-id))

(defn list-frs
  "List all registered FR IDs.

   Optional filters:
   - :priority  - Filter by priority (:critical, :high, :medium, :low)
   - :type      - Filter by type (:intent-level, :invariant, :scenario)
   - :status    - Filter by status (:active, :deprecated)
   - :tag       - Filter by tag presence (keyword)

   Examples:
     (list-frs)                        ; All FRs
     (list-frs {:priority :critical})  ; Critical FRs only
     (list-frs {:type :invariant})     ; Cross-cutting invariants
     (list-frs {:tag :navigation})     ; Navigation-related FRs"
  ([] (keys @!registry))
  ([{:keys [priority type status tag]}]
   (cond->> @!registry
     priority (filter (fn [[_ fr]] (= (:priority fr) priority)))
     type     (filter (fn [[_ fr]] (= (:type fr) type)))
     status   (filter (fn [[_ fr]] (= (:status fr) status)))
     tag      (filter (fn [[_ fr]] (contains? (set (:tags fr)) tag)))
     true     (map first)
     true     (into []))))

(defn critical-frs
  "Shortcut: List all critical FRs.

   Example:
     (critical-frs)
     ;=> [:fr.nav/vertical-cursor-memory :fr.selection/edit-view-exclusive ...]"
  []
  (list-frs {:priority :critical}))

;; ── Validation Helpers ────────────────────────────────────────────────────────

(defn validate-fr-ids!
  "Validate a set of FR IDs against the registry.
   Throws if any ID is unknown.

   Used by intent registration to enforce FR linkage.

   Example:
     (validate-fr-ids! #{:fr.nav/vertical-cursor-memory})  ; OK
     (validate-fr-ids! #{:fr.unknown/fake})                ; Throws"
  [fr-ids]
  (doseq [id fr-ids]
    (when-not (fr-exists? id)
      (throw (ex-info "Unknown FR ID"
                      {:fr-id id
                       :available-frs (keys @!registry)
                       :hint "Check resources/specs.edn for valid FR IDs"})))))

;; ── Auto-Load on Namespace Require ────────────────────────────────────────────

;; Load registry when namespace is required (dev/test workflow)
;; Catches errors gracefully for hot reload
(try
  (load-registry!)
  (catch #?(:clj Exception :cljs js/Error) e
    (println "⚠️ WARNING: Failed to load FR registry:" (ex-message e))
    (println "   Run (spec-registry/load-registry!) to retry")))
