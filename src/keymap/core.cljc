(ns keymap.core
  "Central keyboard shortcut resolver with declarative bindings table.

   Single file, zero divergence. Bindings defined as data, resolver maps events to full intent maps.

   Design:
   - Declarative bindings table (visible on one screen)
   - Context-aware (editing vs non-editing mode)
   - Returns full intent maps (not just types)
   - Simple 30-line resolver"
  (:require [kernel.query :as q]))

;; ── Bindings Table (Single Source of Truth) ──────────────────────────────────

(def bindings
  "Declarative keyboard bindings.

   Format: context -> [[key-spec intent-map] ...]

   Key spec: {:key \"Enter\" :shift true :mod true :alt true}
   Intent map: Full intent (e.g., {:type :selection :mode :next})

   Contexts:
   - :non-editing - Block selection/navigation (not editing text)
   - :editing - Text editing mode
   - :global - Active in all contexts (checked last)"
  {:non-editing [[{:key "ArrowDown"} {:type :selection :mode :next}]
                 [{:key "ArrowUp"} {:type :selection :mode :prev}]
                 [{:key "Tab"} {:type :indent-selected}]
                 [{:key "Tab" :shift true} {:type :outdent-selected}]
                 [{:key "Backspace"} {:type :delete-selected}]
                 [{:key "Enter"} {:type :create-new-block-after-focus}]]

   :editing [[{:key "Escape"} {:type :exit-edit}]
             [{:key "Backspace" :mod true} {:type :merge-with-prev}]]

   :global [[{:key "ArrowUp" :shift true :mod true} {:type :move-selected-up}]
            [{:key "ArrowDown" :shift true :mod true} {:type :move-selected-down}]]})

;; ── Event Parsing ─────────────────────────────────────────────────────────────

(defn parse-dom-event
  "Parse DOM KeyboardEvent to normalized event map.

   Args:
     e - DOM KeyboardEvent

   Returns:
     {:key \"Enter\" :mod true :shift false :alt false}"
  [e]
  {:key #?(:clj nil :cljs (.-key e))
   :mod #?(:clj false :cljs (or (.-metaKey e) (.-ctrlKey e)))
   :shift #?(:clj false :cljs (.-shiftKey e))
   :alt #?(:clj false :cljs (.-altKey e))})

;; ── Context Resolution ────────────────────────────────────────────────────────

(defn- resolve-context
  "Determine current input context from app state.

   Returns :editing or :non-editing"
  [db]
  (if (q/editing? db) :editing :non-editing))

;; ── Resolver (30 lines) ──────────────────────────────────────────────────────

(defn- modifier-match?
  "Check if event modifiers match key-spec modifiers."
  [event-mods spec-mods]
  (and (= (:mod event-mods false) (:mod spec-mods false))
       (= (:shift event-mods false) (:shift spec-mods false))
       (= (:alt event-mods false) (:alt spec-mods false))))

(defn- key-matches?
  "Check if keyboard event matches key specification."
  [event key-spec]
  (and (= (:key event) (:key key-spec))
       (modifier-match? event key-spec)))

(defn- find-binding
  "Find matching binding in context bindings.

   Returns full intent map if found, nil otherwise."
  [bindings-vec event]
  (some (fn [[key-spec intent-map]]
          (when (key-matches? event key-spec)
            intent-map))
        bindings-vec))

(defn resolve-event
  "Resolve keyboard event to full intent map based on current context.

   Args:
     event - Event map with :key, :mod, :shift, :alt
     db - Current database (for context detection)

   Returns:
     - Full intent map if binding found (e.g., {:type :selection :mode :next})
     - nil if no binding matches

   Priority order:
     1. Context-specific bindings (:editing or :non-editing)
     2. Global bindings (:global)"
  [event db]
  (let [context (resolve-context db)
        context-bindings (get bindings context)
        global-bindings (get bindings :global)]
    (or (find-binding context-bindings event)
        (find-binding global-bindings event))))

;; ── Legacy Compatibility ──────────────────────────────────────────────────────

(defn resolve-intent-type
  "DEPRECATED: Use resolve-event instead (returns full intent map).

   Resolve keyboard event to intent type only (for backwards compatibility).

   Args:
     event - Event map
     db - Current database

   Returns:
     - intent-type keyword if binding found
     - nil if no binding matches"
  [event db]
  (when-let [intent-map (resolve-event event db)]
    (:type intent-map)))
