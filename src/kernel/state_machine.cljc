(ns kernel.state-machine
  "UI interaction state derivation and intent gating for Logseq parity.

   LOGSEQ_SPEC.md §1.1 defines mutually exclusive interaction states:
   - :background - No block focused, selected, or editing
   - :focused   - A current block exists, but no block is selected or editing
   - :selection - One or more blocks selected (blue highlight)
   - :editing   - One block in edit mode (caret visible)

   The state is DERIVED from session shape (see `current-state`), never stored
   as its own field. There is no transition table: state is a pure function of
   {editing-block-id, selection, focus}. This module's job is two things:
     1. Label the current interaction state from the session.
     2. Decide whether an intent is allowed to run in that state, via each
        intent's registered :allowed-states plus the `idle-guard` deny-list
        (Enter/Backspace/Tab/etc. no-op in true :background, matching Logseq).

   Usage:
     (require '[kernel.state-machine :as sm])

     ;; Get current state from session
     (sm/current-state session)
     ;=> :background | :focused | :selection | :editing

     ;; Validate an intent is allowed in current state
     (sm/intent-allowed? session {:type :enter-edit :block-id \"a\"})
     ;=> true | false

     ;; Get allowed intents for current state
     (sm/allowed-intents session)
     ;=> #{:selection :arrow-up :arrow-down ...}"
  (:require [kernel.intent :as intent]))

;; ── State Definitions ────────────────────────────────────────────────────────

(def states
  "All valid UI states."
  #{:background :focused :selection :editing})

;; ── Intent → State Requirements ─────────────────────────────────────────────
;;
;; State requirements are now ONLY defined via :allowed-states in register-intent!
;; This is the single source of truth for state constraints.

(defn get-intent-requirements
  "Get state requirements for an intent type.

   Single source of truth: :allowed-states from register-intent!

   Returns:
   - nil if intent allows any state (universal, or not specified)
   - Set of allowed state keywords (e.g., #{:editing :selection})"
  [intent-type]
  (let [registry-states (intent/intent-allowed-states intent-type)]
    (case registry-states
      ;; Intent not registered - allow any state (caller can validate separately)
      :not-registered nil
      ;; Intent registered but :allowed-states not specified - allow any state
      :not-specified nil
      ;; Intent has explicit :allowed-states (may be nil for universal or a set)
      registry-states)))

;; ── State Query Functions ───────────────────────────────────────────────────

(defn current-state
  "Determine current UI state from session.

   Returns :background | :focused | :selection | :editing

   Logic:
   - If editing-block-id is set → :editing
   - If selection has nodes → :selection
   - If focus is set → :focused
   - Otherwise → :background"
  [session]
  (let [editing-id (get-in session [:ui :editing-block-id])
        selection-nodes (get-in session [:selection :nodes] #{})
        focus-id (get-in session [:selection :focus])]
    (cond
      (some? editing-id) :editing
      (seq selection-nodes) :selection
      (some? focus-id) :focused
      :else :background)))

(defn in-editing-state?
  "Check if currently in editing state."
  [session]
  (= :editing (current-state session)))

(defn in-selection-state?
  "Check if currently in selection state."
  [session]
  (= :selection (current-state session)))

(defn in-background-state?
  "Check if currently in background state (nothing focused, selected, or editing)."
  [session]
  (= :background (current-state session)))

(defn in-focused-state?
  "Check if currently in focus-only state."
  [session]
  (= :focused (current-state session)))

;; ── Intent Validation ───────────────────────────────────────────────────────

(defn intent-allowed?
  "Check if an intent is allowed in the current state.

   Uses :allowed-states from intent registration (single source of truth).

   Returns true if:
   - Intent has no state requirements (nil = any state)
   - Current state is in the intent's allowed states set

   Example:
     (intent-allowed? session {:type :enter-edit :block-id \"a\"})
     ;=> true (if in :selection state)
     ;=> false (if in :background or :editing state)"
  [session {:keys [type] :as _intent}]
  (let [requirements (get-intent-requirements type)
        state (current-state session)]
    (or (nil? requirements)
        (contains? requirements state))))

(defn allowed-intents
  "Get set of intent types allowed in current state.

   Queries all registered intents and filters by :allowed-states.

   Example:
     (allowed-intents session)
     ;=> #{:selection :enter-edit ...}"
  [session]
  (let [state (current-state session)
        all-intents (intent/list-intents)]
    (set
     (for [[intent-type _config] all-intents
           ;; Use get-intent-requirements to handle :not-specified case
           :let [requirements (get-intent-requirements intent-type)]
           :when (or (nil? requirements)
                     (contains? requirements state))]
       intent-type))))

(defn validate-intent-state
  "Validate intent is allowed in current state.

   Returns nil if valid, or error map if invalid:
   {:error :invalid-state-for-intent
    :intent-type <type>
    :current-state <state>
    :allowed-states <set>}"
  [session intent]
  (when-not (intent-allowed? session intent)
    (let [intent-type (:type intent)
          requirements (get-intent-requirements intent-type)]
      {:error :invalid-state-for-intent
       :intent-type intent-type
       :current-state (current-state session)
       :allowed-states requirements})))

;; ── Background State Guards ──────────────────────────────────────────────────

(def idle-blocked-intents
  "Intents that should be no-ops when in :background state.

   LOGSEQ_SPEC.md §1.1: In true background state,
   Enter/Backspace/Tab/Shift+Enter/Shift+Arrow do nothing - Logseq never
   creates or deletes blocks from background."
  #{:enter-edit ; No block to edit
    :enter-edit-selected
    :enter-edit-with-char
    :context-aware-enter ; No block to split
    :delete ; No block to delete
    :indent ; No block to indent
    :outdent ; No block to outdent
    :merge-with-prev ; No block to merge
    :merge-with-next
    :insert-newline
    :exit-edit}) ; Not editing

(defn idle-guard
  "Check if intent should be blocked due to :background state.

   Returns true if intent should be blocked (no-op).

   LOGSEQ PARITY: In background state, most editing intents are no-ops."
  [session intent]
  (and (in-background-state? session)
       (contains? idle-blocked-intents (:type intent))))

;; ── REPL Helpers ────────────────────────────────────────────────────────────

(defn describe-state
  "Human-readable description of current state for debugging."
  [session]
  (let [state (current-state session)
        editing-id (get-in session [:ui :editing-block-id])
        selection-nodes (get-in session [:selection :nodes] #{})
        focus-id (get-in session [:selection :focus])]
    {:state state
     :description (case state
                    :background "No block selected, focused, or editing"
                    :focused (str "Focused block: " focus-id)
                    :selection (str "Selection: " (count selection-nodes)
                                    " block(s), focus: " focus-id)
                    :editing (str "Editing block: " editing-id))
     :details {:editing-block-id editing-id
               :selection-count (count selection-nodes)
               :focus-id focus-id}}))

(defn print-state
  "Print current state to console (for REPL debugging)."
  [session]
  (let [{:keys [state description details]} (describe-state session)]
    (println "State:" state)
    (println description)
    (println "Details:" details)))
