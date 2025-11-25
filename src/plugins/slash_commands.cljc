(ns plugins.slash-commands
  "Slash command palette plugin.
   
   Provides Logseq-style / command menu with:
   - TODO/DOING/DONE markers
   - Text formatting (bold, italic, highlight)
   - Block utilities (query, embed)"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Command Definitions
;; ══════════════════════════════════════════════════════════════════════════════

(def slash-commands
  "Available slash commands with their actions."
  [{:id :todo
    :cmd-name "TODO"
    :doc "Mark block as TODO"
    :icon "☐"
    :action :prepend-marker
    :value "TODO "}

   {:id :doing
    :cmd-name "DOING"
    :doc "Mark block as DOING"
    :icon "⋯"
    :action :prepend-marker
    :value "DOING "}

   {:id :done
    :cmd-name "DONE"
    :doc "Mark block as DONE"
    :icon "✓"
    :action :prepend-marker
    :value "DONE "}

   {:id :later
    :cmd-name "LATER"
    :doc "Mark block as LATER"
    :icon "📅"
    :action :prepend-marker
    :value "LATER "}

   {:id :now
    :cmd-name "NOW"
    :doc "Mark block as NOW"
    :icon "⚡"
    :action :prepend-marker
    :value "NOW "}

   {:id :bold
    :cmd-name "Bold"
    :doc "Wrap text with bold formatting"
    :icon "B"
    :action :insert-text
    :value "****"
    :cursor-offset -2}

   {:id :italic
    :cmd-name "Italic"
    :doc "Wrap text with italic formatting"
    :icon "I"
    :action :insert-text
    :value "____"
    :cursor-offset -2}

   {:id :highlight
    :cmd-name "Highlight"
    :doc "Wrap text with highlight formatting"
    :icon "◐"
    :action :insert-text
    :value "^^^^"
    :cursor-offset -2}

   {:id :strikethrough
    :cmd-name "Strikethrough"
    :doc "Wrap text with strikethrough"
    :icon "S̶"
    :action :insert-text
    :value "~~~~"
    :cursor-offset -2}

   {:id :query
    :cmd-name "Query"
    :doc "Insert query block"
    :icon "?"
    :action :insert-text
    :value "{{query }}"
    :cursor-offset -2}

   {:id :embed-page
    :cmd-name "Embed page"
    :doc "Embed a page reference"
    :icon "📄"
    :action :insert-text
    :value "{{embed [[]]}}"
    :cursor-offset -4}

   {:id :embed-block
    :cmd-name "Embed block"
    :doc "Embed a block reference"
    :icon "📦"
    :action :insert-text
    :value "{{embed (())}}"
    :cursor-offset -4}])

;; ══════════════════════════════════════════════════════════════════════════════
;; Trigger Detection
;; ══════════════════════════════════════════════════════════════════════════════

(defn detect-slash-trigger
  "Detect if cursor is after / at start of line or after whitespace.
   Returns {:trigger-pos N} if slash command should activate, nil otherwise."
  [text cursor-pos]
  (when (and (pos? cursor-pos)
             (= (get text (dec cursor-pos)) \/))
    (let [before-slash (subs text 0 (dec cursor-pos))
          at-start? (empty? before-slash)
          after-space? (and (pos? (count before-slash))
                            (= (last before-slash) \space))]
      (when (or at-start? after-space?)
        {:trigger-pos (dec cursor-pos)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Search & Filter
;; ══════════════════════════════════════════════════════════════════════════════

(defn filter-commands
  "Filter commands by search query (fuzzy prefix match on name)."
  [query commands]
  (if (str/blank? query)
    commands
    (let [query-lower (str/lower-case query)]
      (->> commands
           (filter (fn [{:keys [cmd-name]}]
                     (str/starts-with? (str/lower-case cmd-name) query-lower)))
           (take 10)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Command Execution
;; ══════════════════════════════════════════════════════════════════════════════

(defn execute-command
  "Execute a slash command, returning operations and session updates.

   Args:
   - db: Current database
   - command: Command map from slash-commands
   - block-id: ID of block being edited
   - trigger-pos: Position where / was typed
   - cursor-pos: Current cursor position

   Returns:
   - Map with :ops and :session-updates"
  [db command block-id trigger-pos cursor-pos]
  (let [text (get-in db [:nodes block-id :props :text] "")
        before (subs text 0 trigger-pos)
        after (subs text cursor-pos)
        {:keys [action value cursor-offset]} command]

    (case action
      ;; Prepend marker (TODO, DOING, etc.) - remove slash, add marker at start
      :prepend-marker
      (let [new-text (str value before after)
            new-cursor-pos (count value)]
        {:ops [{:op :update-node
                :id block-id
                :props {:text new-text}}]
         :session-updates {:ui {:slash-menu nil
                                :cursor-position new-cursor-pos}}})

      ;; Insert text (bold, italic, etc.) - remove slash, insert markers
      :insert-text
      (let [new-text (str before value after)
            new-cursor-pos (+ (count before) (count value) (or cursor-offset 0))]
        {:ops [{:op :update-node
                :id block-id
                :props {:text new-text}}]
         :session-updates {:ui {:slash-menu nil
                                :cursor-position new-cursor-pos}}}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Intent Handlers
;; ══════════════════════════════════════════════════════════════════════════════

(intent/register-intent! :slash-menu/open
                         {:doc "Open slash command menu.

         Triggered when / is typed at start or after whitespace."
                          :fr/ids #{:fr.ui/slash-palette}
                          :spec [:map
                                 [:type [:= :slash-menu/open]]
                                 [:block-id :string]
                                 [:trigger-pos :int]]
                          :handler
                          (fn [_db _session {:keys [block-id trigger-pos]}]
                            {:session-updates
                             {:ui {:slash-menu {:block-id block-id
                                                :trigger-pos trigger-pos
                                                :search-text ""
                                                :selected-idx 0
                                                :results slash-commands}}}})})

(intent/register-intent! :slash-menu/update-search
                         {:doc "Update slash menu search query and filter results."
                          :fr/ids #{:fr.ui/slash-filter}
                          :spec [:map
                                 [:type [:= :slash-menu/update-search]]
                                 [:block-id :string]
                                 [:cursor-pos :int]]
                          :handler
                          (fn [db session {:keys [block-id cursor-pos]}]
                            (when-let [menu (get-in session [:ui :slash-menu])]
                              (let [text (get-in db [:nodes block-id :props :text] "")
                                    trigger-pos (:trigger-pos menu)
             ;; Search text is everything after the /
                                    search-text (subs text (inc trigger-pos) cursor-pos)
                                    results (filter-commands search-text slash-commands)]
                                {:session-updates
                                 {:ui {:slash-menu (assoc menu
                                                          :search-text search-text
                                                          :results results
                                                          :selected-idx 0)}}})))})

(intent/register-intent! :slash-menu/next
                         {:doc "Select next command in menu (Down / Ctrl+N)."
                          :fr/ids #{:fr.ui/slash-navigate}
                          :spec [:map [:type [:= :slash-menu/next]]]
                          :handler
                          (fn [_db session _intent]
                            (when-let [menu (get-in session [:ui :slash-menu])]
                              (let [idx (:selected-idx menu)
                                    total-count (count (:results menu))
                                    next-idx (if (< idx (dec total-count)) (inc idx) 0)]
                                {:session-updates
                                 {:ui {:slash-menu (assoc menu :selected-idx next-idx)}}})))})

(intent/register-intent! :slash-menu/prev
                         {:doc "Select previous command in menu (Up / Ctrl+P)."
                          :fr/ids #{:fr.ui/slash-navigate}
                          :spec [:map [:type [:= :slash-menu/prev]]]
                          :handler
                          (fn [_db session _intent]
                            (when-let [menu (get-in session [:ui :slash-menu])]
                              (let [idx (:selected-idx menu)
                                    total-count (count (:results menu))
                                    prev-idx (if (pos? idx) (dec idx) (dec total-count))]
                                {:session-updates
                                 {:ui {:slash-menu (assoc menu :selected-idx prev-idx)}}})))})

(intent/register-intent! :slash-menu/select
                         {:doc "Select current command and execute it (Enter)."
                          :fr/ids #{:fr.ui/slash-select}
                          :spec [:map [:type [:= :slash-menu/select]]]
                          :handler
                          (fn [db session _intent]
                            (when-let [menu (get-in session [:ui :slash-menu])]
                              (let [{:keys [results selected-idx block-id trigger-pos]} menu
                                    command (nth results selected-idx nil)]
                                (when command
                                  (let [cursor-pos (get-in session [:ui :cursor-position] 0)]
                                    (execute-command db command block-id trigger-pos cursor-pos))))))})

(intent/register-intent! :slash-menu/close
                         {:doc "Close slash menu without selecting (Esc)."
                          :fr/ids #{:fr.ui/slash-close}
                          :spec [:map [:type [:= :slash-menu/close]]]
                          :handler
                          (fn [_db _session _intent]
                            {:session-updates {:ui {:slash-menu nil}}})})
