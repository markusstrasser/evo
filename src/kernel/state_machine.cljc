(ns kernel.state-machine
  "Explicit UI state machine for Logseq parity.

   LOGSEQ_SPEC.md §1.1 defines mutually exclusive interaction states:
   - :background - No block focused, selected, or editing
   - :focused   - A current block exists, but no block is selected or editing
   - :selection - One or more blocks selected (blue highlight)
   - :editing   - One block in edit mode (caret visible)

   This module enforces state transitions and validates intents against current state.

   State Machine:
   ┌─────────────┐   click-block    ┌─────────────────┐
   │   :idle     │ ───────────────▶ │   :selection    │
   │ • no cursor │ ◀─────────────── │ • blue frames   │
   │ • no blue   │  escape/bg-click │ • focus block   │
   └──────┬──────┘                  └────────┬────────┘
          │                                  │
          │ type-to-edit / Cmd+Enter         │ Enter / start typing
          ▼                                  ▼
   ┌──────────────┐   Escape / blur   ┌─────────────────┐
   │  :editing    │ ─────────────────▶│   :selection    │
   │ • caret      │                   │                 │
   │ • contentEdit│                   │                 │
   └──────────────┘                   └─────────────────┘

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

;; ── State Transitions ───────────────────────────────────────────────────────

(def transitions
  "Valid state transitions: {from-state {trigger-intent to-state}}.

   Used to validate that state changes follow the Logseq contract."
  {:background
   {:selection :selection ; Click block
    :arrow-up :selection ; Select last visible block
    :arrow-down :selection} ; Select first visible block

   :focused
   {:selection :selection
    :enter-edit :editing
    :enter-edit-selected :editing
    :enter-edit-with-char :editing
    :background-click :background}

   :selection
   {:selection :selection ; Extend/change selection
    :enter-edit :editing ; Enter on selected block
    :exit-edit :idle ; Escape clears selection → idle
    :background-click :idle} ; Click on empty space

   :editing
   {:exit-edit :selection ; Escape → select the edited block
    :selection :selection ; Shift+Arrow extends selection
    :blur :idle}}) ; Lose focus → idle

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

(defn in-idle-state?
  "Check if currently in true background/idle state."
  [session]
  (= :background (current-state session)))

(defn in-background-state?
  "Check if currently in background state."
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
     ;=> false (if in :idle or :editing state)"
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

;; ── Transition Helpers ──────────────────────────────────────────────────────

(defn get-next-state
  "Get the state that would result from an intent/trigger.

   Returns nil if no transition defined (intent doesn't change state)."
  [from-state trigger]
  (get-in transitions [from-state trigger]))

(defn transition-valid?
  "Check if a state transition is valid."
  [from-state trigger]
  (contains? (get transitions from-state) trigger))

;; ── Idle State Guards ───────────────────────────────────────────────────────

(def idle-blocked-intents
  "Intents that should be no-ops when in idle state.

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
  "Check if intent should be blocked due to idle state.

   Returns true if intent should be blocked (no-op).

   LOGSEQ PARITY: In background state, most editing intents are no-ops."
  [session intent]
  (and (in-idle-state? session)
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
                    :idle "No block selected or editing"
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
