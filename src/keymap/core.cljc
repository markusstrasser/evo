(ns keymap.core
  "Central keyboard shortcut resolver.

   Single source of truth for all key bindings. Plugins register bindings
   via `register!`, resolver handles dispatch.

   Design:
   - One declarative table, zero divergence
   - Context-aware (editing vs non-editing mode)
   - Hot-reloadable
   - Extensible via plugin registration"
  (:require [kernel.query :as q]))

;; ── Keymap Registry ───────────────────────────────────────────────────────────

(defonce !keymap-registry
  (atom {}))

(defn register!
  "Register key bindings for a context.

   Args:
     context - :global, :editing, :non-editing
     bindings - Vector of [key-spec intent-type] pairs

   Key spec format:
     {:key \"Enter\"} - plain key
     {:key \"ArrowDown\" :shift true} - with modifiers
     {:key \"Tab\" :mod true} - cmd/ctrl
     {:key \"Backspace\" :mod true :shift true} - multiple modifiers

   Example:
     (register! :non-editing
       [[{:key \"Enter\"} :create-new-block-after-focus]
        [{:key \"ArrowDown\"} :select-next-sibling]
        [{:key \"Tab\" :shift true} :outdent-selected]])"
  [context bindings]
  (swap! !keymap-registry update context
         (fn [existing]
           (into (or existing []) bindings))))

(defn clear-bindings!
  "Clear all bindings for a context (useful for hot reload)."
  [context]
  (swap! !keymap-registry dissoc context))

(defn reset-all!
  "Reset entire keymap registry (useful for tests)."
  []
  (reset! !keymap-registry {}))

;; ── Key Matching ──────────────────────────────────────────────────────────────

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
  "Find matching binding in context bindings."
  [bindings event]
  (some (fn [[key-spec intent-type]]
          (when (key-matches? event key-spec)
            intent-type))
        bindings))

;; ── Context Resolution ────────────────────────────────────────────────────────

(defn- resolve-context
  "Determine current input context from app state."
  [db]
  (if (q/editing? db) :editing :non-editing))

;; ── Resolver ──────────────────────────────────────────────────────────────────

(defn resolve-intent-type
  "Resolve keyboard event to intent type based on current context.

   Args:
     event - Event map with :key, :mod, :shift, :alt
     db - Current database (for context detection)

   Returns:
     - intent-type keyword if binding found
     - nil if no binding matches

   Priority order:
     1. Context-specific bindings (:editing or :non-editing)
     2. Global bindings (:global)"
  [event db]
  (let [context (resolve-context db)
        registry @!keymap-registry
        context-bindings (get registry context)
        global-bindings (get registry :global)]
    (or (find-binding context-bindings event)
        (find-binding global-bindings event))))

;; ── Event Parsing ─────────────────────────────────────────────────────────────

(defn parse-dom-event
  "Parse DOM KeyboardEvent to event map.

   Args:
     e - DOM KeyboardEvent

   Returns:
     {:key \"Enter\" :mod true :shift false :alt false}"
  [e]
  {:key #?(:clj nil :cljs (.-key e))
   :mod #?(:clj false :cljs (or (.-metaKey e) (.-ctrlKey e)))
   :shift #?(:clj false :cljs (.-shiftKey e))
   :alt #?(:clj false :cljs (.-altKey e))})
