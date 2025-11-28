(ns kernel.state-machine
  "Explicit UI state machine for Logseq parity.

   LOGSEQ_SPEC.md §1.1 defines three mutually exclusive states:
   - :idle      - No block selected, no block editing (background)
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
     ;=> :idle | :selection | :editing

     ;; Validate an intent is allowed in current state
     (sm/intent-allowed? session {:type :enter-edit :block-id \"a\"})
     ;=> true | false

     ;; Get allowed intents for current state
     (sm/allowed-intents session)
     ;=> #{:selection :arrow-up :arrow-down ...}")

;; ── State Definitions ────────────────────────────────────────────────────────

(def states
  "All valid UI states."
  #{:idle :selection :editing})

;; ── Intent → State Requirements ─────────────────────────────────────────────
;;
;; Maps intent types to the states in which they're allowed.
;; nil means allowed in any state.

(def intent-state-requirements
  "Map of intent type → set of allowed states (nil = any state)."
  {;; ─── IDLE STATE ONLY ───
   ;; From idle, only these can start interaction
   ;; (Most intents shouldn't fire from idle - that's the Logseq contract)

   ;; ─── SELECTION STATE ONLY ───
   :enter-edit              #{:selection}       ; Enter key on selection
   :open-in-sidebar         #{:selection}       ; Shift+Enter on selection

   ;; ─── EDITING STATE ONLY ───
   :navigate-with-cursor-memory #{:editing}     ; Arrow up/down while editing
   :navigate-to-adjacent        #{:editing}     ; Arrow left/right at boundary
   :context-aware-enter         #{:editing}     ; Enter while editing
   :smart-split                 #{:editing}     ; Enter (alias)
   :insert-newline              #{:editing}     ; Shift+Enter
   :delete                      #{:editing}     ; Backspace/Delete
   :merge-with-prev             #{:editing}     ; Backspace at start
   :merge-with-next             #{:editing}     ; Delete at end
   :update-content              #{:editing}     ; Typing
   :paste-text                  #{:editing}     ; Paste

   ;; ─── EDITING OR SELECTION ───
   :exit-edit               #{:editing :selection} ; Escape
   :indent                  #{:editing :selection}
   :outdent                 #{:editing :selection}
   :move-selected-up        #{:editing :selection}
   :move-selected-down      #{:editing :selection}
   :toggle-subtree          #{:editing :selection}
   :copy-blocks             #{:editing :selection}
   :cut-blocks              #{:editing :selection}

   ;; ─── ANY STATE (nil = universal) ───
   :selection               nil                 ; Can select from any state
   :toggle-fold             nil                 ; Can fold from anywhere (bullet click)
   :zoom-in                 nil
   :zoom-out                nil
   :undo                    nil
   :redo                    nil})

;; ── State Transitions ───────────────────────────────────────────────────────

(def transitions
  "Valid state transitions: {from-state {trigger-intent to-state}}.

   Used to validate that state changes follow the Logseq contract."
  {:idle
   {:selection        :selection    ; Click block
    :enter-edit       :editing      ; Double-click or type-to-edit
    :arrow-up         :selection    ; Select last visible block
    :arrow-down       :selection}   ; Select first visible block

   :selection
   {:selection        :selection    ; Extend/change selection
    :enter-edit       :editing      ; Enter on selected block
    :exit-edit        :idle         ; Escape clears selection → idle
    :background-click :idle}        ; Click on empty space

   :editing
   {:exit-edit        :selection    ; Escape → select the edited block
    :selection        :selection    ; Shift+Arrow extends selection
    :blur             :idle}})      ; Lose focus → idle

;; ── State Query Functions ───────────────────────────────────────────────────

(defn current-state
  "Determine current UI state from session.

   Returns :idle | :selection | :editing

   Logic:
   - If editing-block-id is set → :editing
   - If selection has nodes → :selection
   - Otherwise → :idle"
  [session]
  (let [editing-id (get-in session [:ui :editing-block-id])
        selection-nodes (get-in session [:selection :nodes] #{})]
    (cond
      (some? editing-id)       :editing
      (seq selection-nodes)    :selection
      :else                    :idle)))

(defn in-editing-state?
  "Check if currently in editing state."
  [session]
  (= :editing (current-state session)))

(defn in-selection-state?
  "Check if currently in selection state."
  [session]
  (= :selection (current-state session)))

(defn in-idle-state?
  "Check if currently in idle state."
  [session]
  (= :idle (current-state session)))

;; ── Intent Validation ───────────────────────────────────────────────────────

(defn intent-allowed?
  "Check if an intent is allowed in the current state.

   Returns true if:
   - Intent has no state requirements (nil = any state)
   - Current state is in the intent's allowed states set

   Example:
     (intent-allowed? session {:type :enter-edit :block-id \"a\"})
     ;=> true (if in :selection state)
     ;=> false (if in :idle or :editing state)"
  [session {:keys [type] :as _intent}]
  (let [requirements (get intent-state-requirements type)
        state (current-state session)]
    (or (nil? requirements)
        (contains? requirements state))))

(defn allowed-intents
  "Get set of intent types allowed in current state.

   Example:
     (allowed-intents session)
     ;=> #{:selection :enter-edit ...}"
  [session]
  (let [state (current-state session)]
    (set
     (for [[intent-type requirements] intent-state-requirements
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
          requirements (get intent-state-requirements intent-type)]
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

   LOGSEQ_SPEC.md §1.1: In idle state, Enter/Backspace/Tab/Shift+Enter/Shift+Arrow
   do nothing - Logseq never creates or deletes blocks from idle state."
  #{:enter-edit          ; No block to edit
    :context-aware-enter ; No block to split
    :delete              ; No block to delete
    :indent              ; No block to indent
    :outdent             ; No block to outdent
    :merge-with-prev     ; No block to merge
    :merge-with-next
    :insert-newline
    :exit-edit})         ; Not editing

(defn idle-guard
  "Check if intent should be blocked due to idle state.

   Returns true if intent should be blocked (no-op).

   LOGSEQ PARITY: In idle state, most editing intents are no-ops."
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
