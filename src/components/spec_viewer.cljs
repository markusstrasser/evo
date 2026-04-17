(ns components.spec-viewer
  "Spec-as-UI: browsable documentation for Functional Requirements.

   Renders specs.edn as a registry browser with row-aligned before/after
   scenario diffs (outline + raw DSL views). Activated by adding ?specs
   to the URL."
  (:require [spec.registry :as fr]
            [kernel.intent :as intent]
            [clojure.set :as set]
            [clojure.string :as str]
            [replicant.dom :as d]))

;; ══════════════════════════════════════════════════════════════════════════════
;; State
;; ══════════════════════════════════════════════════════════════════════════════

(defonce !ui-state
  (atom {:selected-fr nil
         :show-all? true ; true = handbook view, false = intent-linked subset
         :show-dsl? true ; false = outline view, true = raw DSL syntax
         :search-query ""}))

(defonce render-scheduled? (atom false))
(defonce syncing-url? (atom false))

(declare SpecViewer)

(defn- current-search-params []
  (js/URLSearchParams. (.-search js/location)))

(defn- specviewer-route? []
  (.has (current-search-params) "specs"))

(defn- essay-mode? []
  (.has (current-search-params) "essay"))

(defn- parse-bool-param [value]
  (contains? #{"1" "true" "yes"} (some-> value str/lower-case)))

(defn- parse-url-state []
  (let [params (current-search-params)
        fr-param (.get params "fr")
        q-param (.get params "q")
        view-param (.get params "view")
        all-param (.get params "all")
        fr-id (when (seq fr-param)
                (let [candidate (keyword fr-param)]
                  (or (when (fr/fr-exists? candidate) candidate)
                      (first (filter #(= (name %) fr-param) (fr/list-frs))))))]
    (cond-> {}
      fr-id (assoc :selected-fr fr-id)
      (some? all-param) (assoc :show-all? (parse-bool-param all-param))
      (some? view-param) (assoc :show-dsl? (= "dsl" (some-> view-param str/lower-case)))
      (some? q-param) (assoc :search-query q-param))))

(defn- visible-frs
  [{:keys [show-all? search-query]}]
  (let [all-frs (fr/list-frs)
        implemented-set (intent/implemented-frs)
        base-frs (if show-all?
                   all-frs
                   (filter #(contains? implemented-set %) all-frs))
        query (some-> search-query str/trim str/lower-case)]
    (->> base-frs
         (filter (fn [fr-id]
                   (if (str/blank? query)
                     true
                     (let [fr-data (fr/get-fr fr-id)
                           name-text (name fr-id)
                           desc-text (:desc fr-data)]
                       (or (str/includes? (str/lower-case name-text) query)
                           (and desc-text
                                (str/includes? (str/lower-case desc-text) query)))))))
         sort
         vec)))

(defn- safe-name [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    :else (str x)))

(defn- executable-scenarios
  [fr-data]
  (into {}
        (filter (fn [[_ scenario]] (fr/executable-scenario? scenario))
                (:scenarios fr-data))))

(defn- visible-scenario-count
  [fr-ids]
  (reduce
   (fn [total fr-id]
     (+ total (count (executable-scenarios (fr/get-fr fr-id)))))
   0
   fr-ids))

(defn- intent-audit-index []
  (into {} (map (juxt :id identity)) (intent/full-audit)))

(defn- related-frs
  [fr-id]
  (let [current-fr (fr/get-fr fr-id)
        current-tags (set (:tags current-fr))
        current-type (:type current-fr)
        current-namespace (namespace fr-id)]
    (->> (fr/list-frs)
         (remove #{fr-id})
         (map (fn [other-id]
                (let [other-fr (fr/get-fr other-id)
                      shared-tags (vec (sort (set/intersection current-tags (set (:tags other-fr)))))
                      score (+ (* 3 (count shared-tags))
                               (if (= current-namespace (namespace other-id)) 2 0)
                               (if (= current-type (:type other-fr)) 1 0))]
                  {:id other-id
                   :fr other-fr
                   :shared-tags shared-tags
                   :score score})))
         (filter #(pos? (:score %)))
         (sort-by (juxt (comp - :score) :id))
         (take 4)
         vec)))

(defn- behaviors-by-scenario
  [behaviors]
  (reduce (fn [acc behavior]
            (if-let [scenario-id (:scenario behavior)]
              (assoc acc scenario-id behavior)
              acc))
          {}
          behaviors))

(def essay-outline-snippet
  "{:tree [:doc [:a \"Hello\"] [:b \"World\"]]\n :selection {:nodes #{\"a\"} :focus \"a\"}\n :editing-block-id nil}")

(def essay-kernel-snippet
  "[{:op :create-node :id \"x\" :type :block :props {:text \"Hello\"}}\n {:op :place :id \"x\" :under :doc :at :last}\n {:op :update-node :id \"x\" :props {:text \"Hello world\"}}]")

(def essay-session-snippet
  "{:cursor {:block-id \"a\" :offset 0}\n :selection {:nodes #{\"a\"} :focus \"a\"}\n :ui {:editing-block-id nil}}")

(def essay-state-machine-snippet
  "Idle -> Selection -> Editing\n\nidle: no cursor, no selection\nselection: block focus and range\nediting: caret inside a specific block\n\nThe spec is mostly about moving between those states without ambiguity.")

(def essay-example-refs
  [{:fr-id :fr.nav/horizontal-boundary
    :scenario-id :LEFT-AT-START
    :title "Cursor movement becomes a tree operation"
    :body "ArrowLeft at column 0 does not just move within a string. It crosses a block boundary and lands on a different node in the tree."}
   {:fr-id :fr.struct/create-sibling
    :scenario-id :SPLIT-MID
    :title "Enter edits structure, not just text"
    :body "Splitting a block is not a newline insert. The document becomes two sibling nodes, with the cursor transferred into the new one."}
   {:fr-id :fr.clipboard/paste-multiline
    :scenario-id :PASTE-SPLIT
    :title "Pasting text can materialize new nodes"
    :body "Blank-line paste is interpreted structurally: one string becomes multiple sibling blocks with explicit placement and focus semantics."}])

(defn- essay-example-data []
  (keep (fn [{:keys [fr-id scenario-id] :as example}]
          (when-let [fr-data (fr/get-fr fr-id)]
            (let [scenario (get-in fr-data [:scenarios scenario-id])
                  behavior (get (behaviors-by-scenario (:behaviors fr-data)) scenario-id)]
              (when (and scenario (:setup scenario) (:expect scenario))
                (assoc example
                       :fr fr-data
                       :scenario scenario
                       :behavior behavior)))))
        essay-example-refs))

(def essay-demo-src "/?test=true&embed=1")
(def essay-demo-page-id "essay-demo-page")

(defn- tree-entry-attrs [entry]
  (first (filter map? (rest entry))))

(defn- tree-entry-text [entry]
  (or (first (filter string? (rest entry))) ""))

(defn- tree-entry-children [entry]
  (filter vector? (rest entry)))

(defn- tree-node-count [tree]
  (if (and (vector? tree) (seq tree))
    (reduce (fn [total child]
              (+ total (tree-node-count child)))
            1
            (tree-entry-children tree))
    0))

(defn- cursor->position [text cursor]
  (case cursor
    :end (count text)
    :start 0
    (if (number? cursor) cursor 0)))

(defn- collect-tree-session [entry acc]
  (let [id (safe-name (first entry))
        text (tree-entry-text entry)
        attrs (tree-entry-attrs entry)
        selected? (or (:selected attrs) (:selected? attrs))
        focus? (or (:focus attrs) (:focus? attrs))
        anchor? (or (:anchor attrs) (:anchor? attrs))
        folded? (:folded attrs)
        cursor (:cursor attrs)
        acc (cond-> acc
              (or selected? focus? anchor?)
              (update-in [:selection :nodes] (fnil conj #{}) id)

              focus?
              (assoc-in [:selection :focus] id)

              anchor?
              (assoc-in [:selection :anchor] id)

              folded?
              (update-in [:ui :folded] (fnil conj #{}) id)

              cursor
              (assoc :cursor-block {:id id
                                    :position (cursor->position text cursor)}))]
    (reduce (fn [state child]
              (collect-tree-session child state))
            acc
            (tree-entry-children entry))))

(defn- tree->fixture-ops [tree]
  (let [top-level (tree-entry-children tree)]
    (into [{:op :create-node :id essay-demo-page-id :type :page :props {:title "Essay Demo"}}
           {:op :place :id essay-demo-page-id :under :doc :at :last}]
          (mapcat
           (fn build-ops [entry parent-id]
             (let [id (safe-name (first entry))
                   text (tree-entry-text entry)
                   children (tree-entry-children entry)]
               (concat
                [{:op :create-node :id id :type :block :props {:text text}}
                 {:op :place :id id :under parent-id :at :last}]
                (mapcat #(build-ops % id) children))))
           top-level
           (repeat essay-demo-page-id)))))

(defn- scenario-state->fixture-payload [state]
  (let [tree (:tree state)
        base-session {:selection {:nodes #{} :focus nil :anchor nil}
                      :ui {:current-page essay-demo-page-id
                           :journals-view? false
                           :sidebar-visible? false
                           :hotkeys-visible? false
                           :editing-page-title? false
                           :folded #{}
                           :zoom-root nil}}
        tree-session (reduce (fn [acc entry]
                               (collect-tree-session entry acc))
                             base-session
                             (tree-entry-children tree))
        cursor-block (:cursor-block tree-session)
        ui-session (cond-> (:ui tree-session)
                     (contains? state :zoom-root)
                     (assoc :zoom-root (:zoom-root state))

                     (contains? state :folded)
                     (assoc :folded (set (map safe-name (:folded state))))

                     cursor-block
                     (assoc :editing-block-id (:id cursor-block)
                            :cursor-position (:position cursor-block)))
        selection-session (if cursor-block
                            {:nodes #{} :focus nil :anchor nil}
                            (:selection tree-session))]
    {:ops (tree->fixture-ops tree)
     :session {:selection (update selection-session :nodes vec)
               :ui (update ui-session :folded vec)}}))

(defn- ensure-valid-selection [state]
  (let [available-frs (visible-frs state)
        selected-fr (:selected-fr state)
        selected-visible? (some #{selected-fr} available-frs)]
    (assoc state :selected-fr (or (when selected-visible? selected-fr)
                                  (first available-frs)))))

(defn- ui-state->search []
  (let [{:keys [selected-fr show-all? show-dsl? search-query]} @!ui-state
        params (current-search-params)]
    (.set params "specs" "")
    (if selected-fr
      (.set params "fr" (if-let [fr-ns (namespace selected-fr)]
                          (str fr-ns "/" (name selected-fr))
                          (name selected-fr)))
      (.delete params "fr"))
    (if show-all?
      (.set params "all" "1")
      (.delete params "all"))
    (if show-dsl?
      (.set params "view" "dsl")
      (.delete params "view"))
    (if (str/blank? search-query)
      (.delete params "q")
      (.set params "q" (str/trim search-query)))
    (let [query (.toString params)]
      (str (.-pathname js/location)
           (when (seq query) (str "?" query))
           (.-hash js/location)))))

(defn- sync-url!
  []
  (when (and (specviewer-route?) (not (essay-mode?)))
    (let [next-url (ui-state->search)
          current-url (str (.-pathname js/location)
                           (.-search js/location)
                           (.-hash js/location))]
      (when (not= current-url next-url)
        (reset! syncing-url? true)
        (.replaceState js/history nil "" next-url)
        (reset! syncing-url? false)))))

(defn- set-ui-state!
  [f & args]
  (swap! !ui-state
         (fn [state]
           (ensure-valid-selection (apply f state args)))))

(defn- request-render!
  "Request a render on the next animation frame.
   Multiple calls in the same frame are coalesced into one render."
  []
  (when-not @render-scheduled?
    (reset! render-scheduled? true)
    (js/requestAnimationFrame
     (fn []
       (reset! render-scheduled? false)
       (d/render (js/document.getElementById "root")
                 (SpecViewer))))))

(defonce _watcher
  (add-watch !ui-state :render
             (fn [_ _ old-state new-state]
               (when (not= old-state new-state)
                 (when-not @syncing-url?
                   (sync-url!))
                 (request-render!)))))

(defonce _url-initializer
  (when (and (specviewer-route?) (not (essay-mode?)))
    (reset! !ui-state (ensure-valid-selection (merge @!ui-state (parse-url-state))))))

(defonce _popstate-listener
  (.addEventListener js/window "popstate"
                     (fn []
                       (reset! syncing-url? true)
                       (when (and (specviewer-route?) (not (essay-mode?)))
                         (reset! !ui-state
                                 (ensure-valid-selection
                                  (merge @!ui-state (parse-url-state)))))
                       (reset! syncing-url? false))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Styles
;; ══════════════════════════════════════════════════════════════════════════════

(def styles
  "Devtools-inspired aesthetic: dark panels, syntax highlighting, clear hierarchy."
  {;; Layout
   :container {:display "flex"
               :height "100vh"
               :font-family "'IBM Plex Sans', system-ui, sans-serif"
               :font-size "14px"
               :background "#0f0f10"}

   :sidebar {:width "300px"
             :border-right "1px solid #2a2a2e"
             :overflow-y "auto"
             :background "#18181b"}

   :main {:flex "1"
          :overflow-y "auto"
          :padding "20px 28px 40px"
          :background "#0f0f10"}

   :essay-page {:min-height "100vh"
                :background "#fbfaf7"
                :color "#161310"}

   :essay-shell {:max-width "980px"
                 :margin "0 auto"
                 :padding "72px 36px 120px"}

   :essay-kicker {:font-size "10px"
                  :text-transform "uppercase"
                  :letter-spacing "0.16em"
                  :color "#9a4632"
                  :font-family "'IBM Plex Mono', monospace"
                  :margin-bottom "20px"}

   :essay-title {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                 :font-size "clamp(42px, 6.4vw, 66px)"
                 :line-height "0.98"
                 :letter-spacing "-0.045em"
                 :margin "0 0 18px 0"
                 :color "#11100d"
                 :max-width "840px"}

   :essay-deck {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                :font-size "clamp(18px, 2.2vw, 21px)"
                :line-height "1.68"
                :color "#39322c"
                :max-width "690px"
                :margin "0 0 32px 0"}

   :essay-divider {:height "1px"
                   :background "#d9d2c6"
                   :margin "46px 0 54px"}

   :essay-section {:margin-bottom "74px"}

   :essay-section-title {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                         :font-size "clamp(26px, 3.2vw, 31px)"
                         :line-height "1.08"
                         :letter-spacing "-0.028em"
                         :margin "0 0 14px 0"
                         :color "#151310"
                         :max-width "700px"}

   :essay-copy {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                :font-size "18px"
                :line-height "1.82"
                :color "#2f2a25"
                :max-width "680px"
                :margin "0 0 16px 0"}

   :essay-band {:display "grid"
                :grid-template-columns "repeat(auto-fit, minmax(220px, 1fr))"
                :gap "18px"
                :margin-top "26px"}

   :essay-band-card {:min-width "0"
                     :padding-top "14px"
                     :border-top "1px solid #d8d1c4"}

   :essay-band-title {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                      :font-size "21px"
                      :line-height "1.15"
                      :letter-spacing "-0.018em"
                      :margin "0 0 8px 0"
                      :color "#151310"}

   :essay-band-copy {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                     :font-size "16px"
                     :line-height "1.72"
                     :color "#4a4037"
                     :margin "0 0 12px 0"}

   :essay-two-up {:display "grid"
                  :grid-template-columns "repeat(auto-fit, minmax(280px, 1fr))"
                  :gap "16px"
                  :margin-top "18px"
                  :max-width "860px"}

   :essay-panel {:min-width "0"
                 :padding-top "14px"
                 :border-top "1px solid #d8d1c4"}

   :essay-panel-title {:font-size "10px"
                       :text-transform "uppercase"
                       :letter-spacing "0.15em"
                       :color "#8f4b38"
                       :font-family "'IBM Plex Mono', monospace"
                       :margin-bottom "10px"}

   :essay-code {:background "#f3efe8"
                :border "1px solid #e0d8ca"
                :border-radius "4px"
                :padding "14px 16px"
                :font-family "'IBM Plex Mono', monospace"
                :font-size "12px"
                :line-height "1.68"
                :white-space "pre-wrap"
                :color "#2e2924"}

   :essay-example-card {:margin-top "32px"
                        :padding-top "30px"
                        :border-top "1px solid #d8d1c4"}

   :essay-example-ref {:font-size "10px"
                       :text-transform "uppercase"
                       :letter-spacing "0.14em"
                       :color "#9a4632"
                       :font-family "'IBM Plex Mono', monospace"
                       :margin-bottom "10px"}

   :essay-example-title {:font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                         :font-size "clamp(25px, 3vw, 30px)"
                         :line-height "1.06"
                         :letter-spacing "-0.028em"
                         :margin "0 0 8px 0"
                         :color "#151310"
                         :max-width "680px"}

   :essay-example-shell {:display "flex"
                         :flex-direction "column"
                         :gap "14px"}

   :essay-action-line {:display "block"
                       :max-width "640px"
                       :padding "0 0 0 12px"
                       :border-left "2px solid #c86b4f"
                       :font-family "'IBM Plex Mono', monospace"
                       :font-size "12px"
                       :line-height "1.7"
                       :white-space "pre-wrap"
                       :color "#5d4c42"}

   :essay-figure-row {:display "flex"
                      :flex-wrap "wrap"
                      :gap "12px"
                      :align-items "flex-start"
                      :max-width "860px"}

   :essay-figure-card {:flex "1 1 320px"
                       :min-width "0"
                       :padding-top "10px"}

   :essay-editor-frame {:display "block"
                        :width "100%"
                        :border "1px solid #ddd5c8"
                        :border-radius "4px"
                        :background "#fffefe"}

   :essay-figure-code {:margin-top "10px"
                       :padding "12px 14px"
                       :border-radius "4px"
                       :background "#f8f5ef"
                       :border "1px solid #e0d8ca"
                       :font-family "'IBM Plex Mono', monospace"
                       :font-size "12px"
                       :line-height "1.65"
                       :overflow-x "auto"}

   :essay-figure-arrow {:display "flex"
                        :align-items "center"
                        :justify-content "center"
                        :font-family "\"Iowan Old Style\", \"Palatino Linotype\", \"Book Antiqua\", Georgia, serif"
                        :font-size "30px"
                        :color "#a08b76"
                        :padding "78px 4px 0"
                        :min-width "26px"}

   :search-input {:width "100%"
                  :box-sizing "border-box"
                  :margin-top "12px"
                  :padding "9px 11px"
                  :border "1px solid #27272a"
                  :border-radius "6px"
                  :background "#111114"
                  :color "#fafafa"
                  :font-size "12px"
                  :outline "none"}

   :sidebar-kicker {:color "#71717a"
                    :font-size "11px"
                    :margin-top "6px"
                    :line-height "1.45"}

   :sidebar-help {:color "#71717a"
                  :font-size "11px"
                  :line-height "1.5"
                  :margin-top "10px"}

   :sticky-header {:position "sticky"
                   :top "-20px"
                   :z-index "2"
                   :margin "0 -8px 24px"
                   :padding "20px 8px 14px"
                   :background "linear-gradient(180deg, rgba(15,15,16,0.98) 0%, rgba(15,15,16,0.95) 78%, rgba(15,15,16,0) 100%)"
                   :backdrop-filter "blur(10px)"}

   :sticky-header-inner {:border-bottom "1px solid #27272a"
                         :padding-bottom "16px"}

   :eyebrow {:font-size "11px"
             :text-transform "uppercase"
             :letter-spacing "0.08em"
             :color "#71717a"
             :font-family "'IBM Plex Mono', monospace"
             :margin-bottom "10px"}

   :doc-main {:display "flex"
              :flex-direction "column"
              :gap "20px"}

   :surface {:background "#151518"
             :border "1px solid #27272a"
              :border-radius "12px"
              :padding "18px 20px"}

   :lede {:font-size "17px"
          :line-height "1.55"
          :color "#ededf0"
          :margin "0"}

   :section-title {:font-size "14px"
                   :font-weight "600"
                   :color "#d4d4d8"
                   :margin "0 0 14px 0"
                   :text-transform "uppercase"
                   :letter-spacing "0.05em"}

   :section-copy {:font-size "14px"
                  :line-height "1.7"
                  :color "#bdbdc7"
                  :margin "0"}

   :meta-list {:display "flex"
               :flex-wrap "wrap"
               :gap "8px"}

   :meta-chip {:padding "5px 9px"
               :border-radius "999px"
               :background "#222228"
               :color "#c7c7d1"
               :font-size "11px"
               :font-family "'IBM Plex Mono', monospace"}

   :behavior-card {:padding "12px 0"
                   :border-bottom "1px solid #23232a"}

   :behavior-context {:font-size "12px"
                      :color "#8f90a0"
                      :margin-bottom "8px"}

   :behavior-text {:font-size "14px"
                   :line-height "1.6"
                   :color "#ededf0"}

   :behavior-grid {:display "flex"
                   :flex-direction "column"
                   :gap "0"}

   :related-link {:display "block"
                  :width "100%"
                  :box-sizing "border-box"
                  :text-align "left"
                  :padding "12px 14px"
                  :border "1px solid #27272a"
                  :border-radius "10px"
                  :background "#141418"
                  :color "#ededf0"
                  :cursor "pointer"}

   :scenario-prose-grid {:display "flex"
                         :align-items "center"
                         :justify-content "space-between"
                         :gap "16px"
                         :margin-bottom "14px"}

   :scenario-note {:display "flex"
                   :flex-direction "column"
                   :gap "6px"
                   :min-width "0"}

   :scenario-note-label {:font-size "10px"
                         :text-transform "uppercase"
                         :letter-spacing "0.08em"
                         :color "#71717a"
                         :font-family "'IBM Plex Mono', monospace"
                         :margin-bottom "8px"}

   :scenario-note-body {:font-size "12px"
                        :line-height "1.5"
                        :color "#d4d4d8"}

   :inline-code-block {:margin-top "10px"
                       :padding "10px 12px"
                       :border-radius "8px"
                       :background "#101014"
                       :border "1px solid #27272a"
                       :font-family "'IBM Plex Mono', monospace"
                       :font-size "11px"
                       :line-height "1.6"
                       :white-space "pre-wrap"
                       :color "#c7c7d1"}

   :note-callout {:margin-top "14px"
                  :padding "12px 14px"
                  :border-radius "10px"
                  :background "#121d1a"
                  :border "1px solid #1f4636"
                  :color "#b7e4d2"
                  :line-height "1.6"}

   ;; Sidebar items
   :fr-item {:padding "12px 16px"
             :cursor "pointer"
             :border-bottom "1px solid #27272a"
             :transition "background 0.15s ease"}

   :fr-item-selected {:background "#3f3f46"}

   :fr-title {:font-weight "500"
              :font-size "13px"
              :color "#fafafa"
              :font-family "'IBM Plex Mono', monospace"}

   :fr-desc {:font-size "11px"
             :color "#71717a"
             :margin-top "4px"
             :line-height "1.4"}

   :fr-meta {:display "flex"
             :align-items "center"
             :gap "8px"
             :flex-wrap "wrap"
             :margin-top "8px"
             :font-size "10px"
             :color "#71717a"
             :font-family "'IBM Plex Mono', monospace"}

   ;; Priority badges
   :priority-badge {:display "inline-block"
                    :padding "3px 8px"
                    :border-radius "3px"
                    :font-size "10px"
                    :font-weight "600"
                    :letter-spacing "0.5px"
                    :font-family "'IBM Plex Mono', monospace"}

   :priority-critical {:background "#7f1d1d" :color "#fecaca"}
   :priority-high {:background "#7c2d12" :color "#fed7aa"}
   :priority-medium {:background "#713f12" :color "#fef08a"}
   :priority-low {:background "#14532d" :color "#bbf7d0"}

   ;; Scenario card
   :scenario-card {:border "1px solid #27272a"
                   :border-radius "8px"
                   :margin-bottom "20px"
                   :background "#18181b"
                   :overflow "hidden"}

   :scenario-header {:padding "14px 18px"
                     :background "#1f1f23"
                     :border-bottom "1px solid #27272a"
                     :display "flex"
                     :justify-content "space-between"
                     :align-items "center"}

   ;; Tags
   :tag {:display "inline-block"
         :background "#27272a"
         :color "#a1a1aa"
         :padding "3px 8px"
         :border-radius "3px"
         :font-size "10px"
         :font-family "'IBM Plex Mono', monospace"
         :margin-right "6px"}})

;; ══════════════════════════════════════════════════════════════════════════════
;; Tree Visualizer
;; ══════════════════════════════════════════════════════════════════════════════

(defn- render-cursor-in-text
  "Render text with a visual cursor indicator at the specified position."
  [text cursor-pos]
  (let [pos (if (= cursor-pos :end) (count text) cursor-pos)
        before (subs text 0 (min pos (count text)))
        after (subs text (min pos (count text)))]
    [:span
     [:span before]
     [:span {:style {:background "#3b82f6"
                     :width "2px"
                     :display "inline-block"
                     :height "1.1em"
                     :vertical-align "text-bottom"
                     :margin "0 1px"
                     :animation "blink 1s step-end infinite"}} ""]
     [:span after]]))

(defn- extract-block-info
  "Extract block info from a tree entry for row-based comparison."
  [entry]
  (when (and (vector? entry) (seq entry))
    (let [[id text & remaining] entry
          attrs (first (filter map? remaining))
          children (filter vector? remaining)]
      {:id id
       :text (or text "")
       :cursor (:cursor attrs)
       :selected? (:selected? attrs)
       :focus? (:focus? attrs)
       :anchor? (:anchor? attrs)
       :folded? (:folded? attrs)
       :children (mapv extract-block-info children)})))

(defn- flatten-blocks
  "Flatten tree into sequence of [depth block-info] pairs."
  [block-info depth]
  (when block-info
    (cons [depth block-info]
          (mapcat #(flatten-blocks % (inc depth)) (:children block-info)))))

(defn- render-block-cell
  "Render a single block cell (used in both before/after columns)."
  [block-info depth]
  (let [{:keys [id text cursor selected? focus? anchor? folded?]} block-info
        indent-px (* depth 14)]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :padding "4px 10px"
                   :padding-left (str (+ 10 indent-px) "px")
                   :background (cond
                                 focus? "rgba(59, 130, 246, 0.12)"
                                 selected? "rgba(59, 130, 246, 0.06)"
                                 :else "transparent")
                   :min-height "28px"}}
     ;; Bullet - BIGGER
     [:span {:style {:color (cond cursor "#3b82f6" selected? "#60a5fa" :else "#52525b")
                     :margin-right "10px"
                     :font-size "10px"}}
      (if folded? "▶" "●")]
     ;; Block ID
     [:span {:style {:background "#27272a"
                     :color "#a1a1aa"
                     :padding "2px 6px"
                     :border-radius "3px"
                     :font-size "10px"
                     :margin-right "10px"
                     :min-width "16px"
                     :text-align "center"}}
      (name id)]
     ;; Text with cursor
     [:span {:style {:color "#e4e4e7" :font-size "13px"}}
      (if cursor
        (render-cursor-in-text text cursor)
        text)]
     ;; State badges (compact)
     (when (or cursor focus? selected? anchor?)
       [:span {:style {:display "flex" :gap "4px" :margin-left "10px"}}
        (when cursor
          [:span {:style {:background "#1e3a8a" :color "#93c5fd" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}}
           (if (= cursor :end) "▮end" (str "▮" cursor))])
        (when focus?
          [:span {:style {:background "#166534" :color "#86efac" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}} "focus"])
        (when (and selected? (not focus?))
          [:span {:style {:background "#1e3a5f" :color "#7dd3fc" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}} "sel"])
        (when anchor?
          [:span {:style {:background "#4c1d95" :color "#c4b5fd" :padding "2px 6px"
                          :border-radius "3px" :font-size "10px"}} "anchor"])])]))

(def dark-dsl-palette
  {:keyword "#c678dd"
   :string "#98c379"
   :attrs "#e5c07b"
   :brackets "#6b7280"
   :indent "#4b5563"})

(defn- render-dsl-tree
  "Render tree DSL with syntax highlighting (raw Clojure notation)."
  ([tree depth]
   (render-dsl-tree tree depth dark-dsl-palette))
  ([tree depth palette]
  (when (and (vector? tree) (seq tree))
    (let [[tag & remaining] tree
          text (first (filter string? remaining))
          attrs (first (filter map? remaining))
          children (filter vector? remaining)
          indent (apply str (repeat (* depth 2) " "))
          kw-color (:keyword palette)
          str-color (:string palette)
          attr-color (:attrs palette)
          bracket-color (:brackets palette)
          indent-color (:indent palette)]
      [:div {:style {:white-space "pre" :line-height "1.5"}}
       [:span {:style {:color indent-color}} indent]
       [:span {:style {:color bracket-color}} "["]
       [:span {:style {:color kw-color}} (str ":" (name tag))]
       (when text
         [:span {:style {:color str-color}} (str " \"" text "\"")])
       (when (seq attrs)
         [:span {:style {:color attr-color}} (str " " (pr-str attrs))])
       (when (empty? children)
         [:span {:style {:color bracket-color}} "]"])
       (for [[i child] (map-indexed vector children)]
         ^{:key i} (render-dsl-tree child (inc depth) palette))
       (when (seq children)
         [:div {:style {:white-space "pre"}}
          [:span {:style {:color indent-color}} indent]
          [:span {:style {:color bracket-color}} "]"]])]))))

(defn- DslView
  "Raw DSL syntax view with syntax highlighting."
  [{:keys [tree]}]
  (when tree
    [:div {:style {:font-family "'IBM Plex Mono', monospace"
                   :font-size "12px"
                   :background "#121216"
                   :border "1px solid #22222a"
                   :padding "14px"
                   :border-radius "10px"}}
     (render-dsl-tree tree 0)]))

(defn- EssayEditorFrame
  [{:keys [state title]}]
  (let [payload (scenario-state->fixture-payload state)
        node-count (max 1 (reduce + (map tree-node-count (tree-entry-children (:tree state)))))
        frame-height (+ 88 (* node-count 54))]
    [:iframe {:title title
              :src essay-demo-src
              :name (js/JSON.stringify (clj->js payload))
              :loading "lazy"
              :style (assoc (:essay-editor-frame styles) :height (str frame-height "px"))}]))

(defn- DslDiffView
  "Side-by-side DSL syntax comparison."
  [{:keys [before after]}]
  [:div {:style {:display "grid"
                 :grid-template-columns "1fr auto 1fr"
                 :gap "12px"
                 :align-items "start"}}
   [:div
    [:div {:style {:color "#71717a" :font-size "10px" :text-transform "uppercase"
                   :letter-spacing "0.5px" :margin-bottom "8px"}}
     "Before"]
    (DslView {:tree before})]
   [:div {:style {:color "#4b5563" :font-size "16px" :padding-top "24px"}} "→"]
   [:div
    [:div {:style {:color "#22c55e" :font-size "10px" :text-transform "uppercase"
                   :letter-spacing "0.5px" :margin-bottom "8px"}}
     "After"]
    (DslView {:tree after})]])

(defn DiffView
  "Row-aligned before/after comparison - minimizes eye movement.
   Columns shrink to fit content, keeping before/after close together."
  [{:keys [before after]}]
  (let [show-dsl? (:show-dsl? @!ui-state)
        before-blocks (when before
                        (let [[_root & entries] before]
                          (vec (mapcat #(flatten-blocks (extract-block-info %) 0) entries))))
        after-blocks (when after
                       (let [[_root & entries] after]
                         (vec (mapcat #(flatten-blocks (extract-block-info %) 0) entries))))
        max-rows (max (count before-blocks) (count after-blocks))]
    (if show-dsl?
      ;; DSL syntax view
      (DslDiffView {:before before :after after})
      ;; Outline view (default)
      [:div {:style {:font-family "'IBM Plex Mono', monospace"
                     :font-size "13px"
                     :display "inline-block"}}
       [:style "@keyframes blink { 50% { opacity: 0; } }"]
       [:table {:style {:border-collapse "collapse"}}
        [:thead
         [:tr
          [:th {:style {:color "#71717a" :font-size "11px" :text-transform "uppercase"
                        :letter-spacing "0.5px" :padding "0 12px 8px 0"
                        :font-weight "500" :text-align "left"}}
           "Before"]
          [:th {:style {:color "#4b5563" :padding "0 16px 8px" :font-weight "normal"}}
           "→"]
          [:th {:style {:color "#22c55e" :font-size "11px" :text-transform "uppercase"
                        :letter-spacing "0.5px" :padding "0 0 8px 0"
                        :font-weight "500" :text-align "left"}}
           "After"]]]
        [:tbody
         (for [i (range max-rows)]
           (let [[before-depth before-block] (get before-blocks i)
                 [after-depth after-block] (get after-blocks i)]
             ^{:key i}
             [:tr {:style {:border-top "1px solid #1f1f23"}}
              [:td {:style {:padding "0" :vertical-align "middle"}}
               (when before-block
                 (render-block-cell before-block before-depth))]
              [:td {:style {:padding "0 12px"
                            :color "#4b5563"
                            :font-size "14px"
                            :vertical-align "middle"
                            :text-align "center"}}
               (when (or before-block after-block) "→")]
              [:td {:style {:padding "0" :vertical-align "middle"}}
               (when after-block
                 (render-block-cell after-block after-depth))]]))]]])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Scenario Card
;; ══════════════════════════════════════════════════════════════════════════════

(defn ScenarioCard
  "Render a scenario with row-aligned before/after diff view."
  [{:keys [scenario-id scenario behavior]}]
  (let [{:keys [setup action expect]} scenario
        executable? (and setup action expect)]
    [:div {:style (:scenario-card styles)}
     ;; Header
     [:div {:style (:scenario-header styles)}
      [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
       (when executable?
         [:span {:style {:color "#22c55e" :font-size "10px"}} "●"])
       [:span {:style {:font-weight "600" :font-size "13px" :color "#fafafa"
                       :font-family "'IBM Plex Mono', monospace"}}
        (safe-name scenario-id)]
       [:span {:style {:color "#71717a" :font-size "12px"}}
        (:name scenario)]]
      (when-let [tags (:tags scenario)]
        [:div {:style {:display "flex" :gap "4px"}}
         (for [tag (if (set? tags) (sort tags) tags)]
           ^{:key (str tag)}
           [:span {:style (:tag styles)} (safe-name tag)])])]

     ;; Row-aligned diff view (Tufte-style: minimal saccades)
     (when executable?
       [:div
        [:div {:style {:padding "16px"}}
         [:div {:style (:scenario-prose-grid styles)}
          [:div {:style (:scenario-note styles)}
           [:div {:style (:scenario-note-label styles)} "Meaning"]
           [:div {:style (:scenario-note-body styles)}
            (or (:behavior behavior)
                (:context behavior)
                "Example state transition.")]]
          [:div {:style (:scenario-note styles)}
           [:div {:style (:scenario-note-label styles)} "Operation"]
           [:div {:style (:inline-code-block styles)}
            (pr-str action)]]]
         (DiffView {:before (:tree setup) :after (:tree expect)})]

        (when-let [scenario-note (:notes scenario)]
          [:div {:style (:note-callout styles)} scenario-note])])]))

(defn EssayExample
  [{:keys [title body fr-id scenario behavior scenario-id]}]
  [:section {:style (:essay-example-card styles)}
   [:div {:style (:essay-example-shell styles)}
    [:div {:style (:essay-example-ref styles)}
     (str (name fr-id) " / " (safe-name scenario-id))]
    [:h3 {:style (:essay-example-title styles)} title]
    [:p {:style (:essay-copy styles)} body]
    [:div {:style (:essay-action-line styles)}
     (pr-str (:action scenario))]
    [:div {:style (:essay-figure-row styles)}
     [:div {:style (:essay-figure-card styles)}
      [:div {:style (:essay-panel-title styles)} "Before"]
      (EssayEditorFrame {:state (:setup scenario)
                         :title (str title " before")})]
     [:div {:style (:essay-figure-arrow styles)} "→"]
     [:div {:style (:essay-figure-card styles)}
      [:div {:style (:essay-panel-title styles)} "After"]
      (EssayEditorFrame {:state (:expect scenario)
                         :title (str title " after")})]]
    [:p {:style (merge (:essay-copy styles) {:max-width "700px"})}
     (or (:behavior behavior)
         (:context behavior)
         "Structural state transition.")]]])

(defn EssayFrameSection
  []
  [:section {:style (:essay-section styles)}
   [:h2 {:style (:essay-section-title styles)} "You are editing three synchronized machines"]
   [:p {:style (:essay-copy styles)}
    "The document has shape. The user session has mode. The kernel has a small algebra for changing structure. Most of the spec exists to keep those three layers in sync without ambiguity."]
   [:div {:style (:essay-band styles)}
    [:div {:style (:essay-band-card styles)}
     [:h3 {:style (:essay-band-title styles)} "1. Document graph"]
     [:p {:style (:essay-band-copy styles)}
      "Blocks are not lines in one long buffer. They are nodes in a tree with explicit parent and sibling relationships."]
     [:div {:style (:essay-code styles)} essay-outline-snippet]]
    [:div {:style (:essay-band-card styles)}
     [:h3 {:style (:essay-band-title styles)} "2. Session state"]
     [:p {:style (:essay-band-copy styles)}
      "Cursor, node selection, and editing mode are distinct pieces of state. That is why behavior can stay crisp at boundaries."]
     [:div {:style (:essay-code styles)} essay-session-snippet]]
    [:div {:style (:essay-band-card styles)}
     [:h3 {:style (:essay-band-title styles)} "3. Kernel operations"]
     [:p {:style (:essay-band-copy styles)}
      "Even complex gestures reduce to a tiny set of structural edits. That keeps the implementation small and the spec legible."]
     [:div {:style (:essay-code styles)} essay-kernel-snippet]]]])

(defn EssayMindsetSection
  []
  [:section {:style (:essay-section styles)}
   [:h2 {:style (:essay-section-title styles)} "Why normal editor intuition breaks"]
   [:p {:style (:essay-copy styles)}
    "In a string editor, nearly everything feels like text mutation plus cursor arithmetic. In a structural editor, the same keys often become shape-changing operations or state transitions. That is why the first encounter feels foreign: the mental model is different, not just the UI."]
   [:div {:style (:essay-two-up styles)}
    [:div {:style (:essay-panel styles)}
     [:div {:style (:essay-panel-title styles)} "String editor intuition"]
     [:div {:style (:essay-code styles)}
      "text: \"Hello\\nWorld\"\ncursor: 7\nselection: [3 9]"]]
    [:div {:style (:essay-panel styles)}
     [:div {:style (:essay-panel-title styles)} "Structural editor intuition"]
     [:div {:style (:essay-code styles)} essay-state-machine-snippet]]]])

(defn EssayExamplesSection
  []
  (let [examples (essay-example-data)]
    [:section {:style (:essay-section styles)}
     [:h2 {:style (:essay-section-title styles)} "Three examples that make the model click"]
     [:p {:style (:essay-copy styles)}
      "These examples are intentionally small. Each one takes a familiar gesture and shows the actual structural interpretation underneath it."]
     (for [example examples]
       ^{:key (str (:fr-id example) "-" (:scenario-id example))}
       (EssayExample example))]))

(defn EssayTakeawaySection
  []
  [:section {:style (:essay-section styles)}
   [:h2 {:style (:essay-section-title styles)} "Why this matters for programmers"]
   [:p {:style (:essay-copy styles)}
    "Once you stop modeling the editor as a string widget, the implementation gets cleaner. Tree changes become composable operations. Editing modes become explicit state, not DOM accidents. Complex behaviors become specs and examples instead of piles of conditionals."]
   [:p {:style (:essay-copy styles)}
    "That is the real payoff: structural editing is not mainly a UX trick. It is a better decomposition for reasoning about document behavior."]])

(defn SpecEssay
  []
  [:div {:style (:essay-page styles)}
   [:style "
      @import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Serif:wght@400;500;600&display=swap');
    "]
   [:div {:style (:essay-shell styles)}
    [:div {:style (:essay-kicker styles)} "Essay view"]
    [:h1 {:style (:essay-title styles)}
     "Why structural editors feel strange until you stop thinking in strings"]
    [:p {:style (:essay-deck styles)}
     "A structural editor is not mainly a smarter textarea. It is a document model with an explicit tree, explicit interaction states, and a tiny algebra for changing shape. Many ordinary keys only make sense once you see those layers separately."]
    [:div {:style (:essay-divider styles)}]
    (EssayFrameSection)
    (EssayMindsetSection)
    (EssayExamplesSection)
    (EssayTakeawaySection)]])

;; ══════════════════════════════════════════════════════════════════════════════
;; FR Detail Panel
;; ══════════════════════════════════════════════════════════════════════════════

(defn FRDetail
  "Detailed view of a single FR with all its scenarios."
  [{:keys [fr-id]}]
  (when-let [fr (fr/get-fr fr-id)]
    (let [all-scenarios (:scenarios fr)
          scenarios (into {} (filter (fn [[_ s]] (fr/executable-scenario? s)) all-scenarios))
          scenario-count (count scenarios)
          show-dsl? (:show-dsl? @!ui-state)
          audit (get (intent-audit-index) fr-id)
          implementing-intents (sort (:implementing-intents audit))
          behaviors (:behaviors fr)
          behavior-index (behaviors-by-scenario behaviors)
          related (related-frs fr-id)]
      [:div
       [:div {:style (:sticky-header styles)}
        [:div {:style (merge (:sticky-header-inner styles)
                             {:display "flex"
                              :justify-content "space-between"
                              :align-items "flex-start"
                              :gap "16px"
                              :flex-wrap "wrap"})}
         [:div
          [:div {:style (:eyebrow styles)} "Functional Requirement"]
          [:h2 {:style {:margin "0 0 8px 0"
                        :font-size "28px"
                        :font-weight "600"
                        :color "#fafafa"
                        :font-family "'IBM Plex Mono', monospace"}}
           (name fr-id)]
          [:p {:style {:color "#c9cad5"
                       :margin "0"
                       :line-height "1.7"
                       :max-width "820px"}}
           (:desc fr)]
          [:div {:style {:margin-top "14px"
                         :display "flex"
                         :gap "12px"
                         :flex-wrap "wrap"
                         :align-items "center"}}
           [:span {:style (merge (:priority-badge styles)
                                 (case (:priority fr)
                                   :critical (:priority-critical styles)
                                   :high (:priority-high styles)
                                   :medium (:priority-medium styles)
                                   :low (:priority-low styles)
                                   {}))}
            (str/upper-case (name (:priority fr)))]
           [:span {:style {:font-size "12px" :color "#71717a"}}
            (str "Type: " (name (:type fr)))]
           [:span {:style {:font-size "12px" :color "#71717a"}}
            (str "Spec: " (:spec-ref fr))]
           (when-let [level (:level fr)]
             [:span {:style {:font-size "12px" :color "#71717a"}}
              (str "Level: " (safe-name level))])
           (when (pos? scenario-count)
             [:span {:style {:display "flex"
                             :align-items "center"
                             :gap "4px"
                             :font-size "12px"
                             :color "#22c55e"}}
              "●"
              (str scenario-count " scenario" (when (> scenario-count 1) "s"))])]]
         [:div {:style {:display "flex"
                        :gap "4px"
                        :background "#27272a"
                        :padding "3px"
                        :border-radius "4px"
                        :align-self "flex-start"}}
          [:button {:style {:background (if-not show-dsl? "#3f3f46" "transparent")
                            :border "none"
                            :color (if-not show-dsl? "#fafafa" "#71717a")
                            :padding "4px 10px"
                            :border-radius "3px"
                            :cursor "pointer"
                            :font-size "11px"
                            :font-family "'IBM Plex Mono', monospace"}
                    :on {:click (fn [_] (set-ui-state! assoc :show-dsl? false))}}
           "Outline"]
          [:button {:style {:background (if show-dsl? "#3f3f46" "transparent")
                            :border "none"
                            :color (if show-dsl? "#fafafa" "#71717a")
                            :padding "4px 10px"
                            :border-radius "3px"
                            :cursor "pointer"
                            :font-size "11px"
                            :font-family "'IBM Plex Mono', monospace"}
                    :on {:click (fn [_] (set-ui-state! assoc :show-dsl? true))}}
           "DSL"]]]]

       [:div {:style (:doc-main styles)}
        (when (seq behaviors)
          [:section {:style (:surface styles)}
           [:h3 {:style (:section-title styles)} "Behavior breakdown"]
           [:div {:style (:behavior-grid styles)}
            (for [[idx behavior] (map-indexed vector behaviors)]
              ^{:key (str idx "-" (:context behavior))}
              [:div {:style (merge (:behavior-card styles)
                                   (when (= idx (dec (count behaviors)))
                                     {:border-bottom "none"}))}
               [:div {:style (:behavior-context styles)} (:context behavior)]
               [:div {:style (:behavior-text styles)} (:behavior behavior)]])]])

        (when (seq scenarios)
          [:section
           [:h3 {:style (:section-title styles)}
            (str "Worked examples (" scenario-count ")")]
           (for [[scenario-id scenario] scenarios]
             ^{:key scenario-id}
             (ScenarioCard {:scenario-id scenario-id
                            :scenario scenario
                            :behavior (get behavior-index scenario-id)}))])

        (when (or (seq (:tags fr))
                  (seq implementing-intents)
                  (:notes fr)
                  (:invariants fr)
                  (seq related))
          [:section {:style (:surface styles)}
           [:h3 {:style (:section-title styles)} "Spec context"]
           (when (seq (:tags fr))
             [:div {:style {:margin-bottom "14px"}}
              [:div {:style (:meta-list styles)}
               (for [tag (:tags fr)]
                 ^{:key (str "tag-" tag)}
                 [:span {:style (:meta-chip styles)}
                  (str "#" (safe-name tag))])]])
           (when (seq implementing-intents)
             [:div {:style {:margin-bottom "14px"}}
              [:div {:style (:scenario-note-label styles)} "Linked intents"]
              [:div {:style (:meta-list styles)}
               (for [intent-id implementing-intents]
                 ^{:key intent-id}
                 [:span {:style (:meta-chip styles)}
                  (str ":" (safe-name intent-id))])]])
           (when-let [notes (:notes fr)]
             [:p {:style (merge (:section-copy styles) {:margin-bottom "14px"})} notes])
           (when-let [invariants (:invariants fr)]
             [:div {:style {:margin-bottom "14px"}}
              (when-let [pre (:pre invariants)]
                [:div {:style {:margin-bottom "10px"}}
                 [:div {:style (:scenario-note-label styles)} "Preconditions"]
                 [:div {:style (:meta-list styles)}
                  (for [item pre]
                    ^{:key (str "pre-" item)}
                    [:span {:style (:meta-chip styles)} (safe-name item)])]])
              (when-let [post (:post invariants)]
                [:div
                 [:div {:style (:scenario-note-label styles)} "Postconditions"]
                 [:div {:style (:meta-list styles)}
                  (for [item post]
                    ^{:key (str "post-" item)}
                    [:span {:style (:meta-chip styles)} (safe-name item)])]])])
           (when (seq related)
             [:div
              [:div {:style (:scenario-note-label styles)} "Related"]
              [:div {:style (:meta-list styles)}
               (for [{:keys [id]} related]
                 ^{:key id}
                 [:span {:style (:meta-chip styles)} (name id)])]])])]])))

;; ══════════════════════════════════════════════════════════════════════════════
;; FR Sidebar
;; ══════════════════════════════════════════════════════════════════════════════

(defn FRSidebar
  "Sidebar listing all FRs, filterable."
  []
  (let [state @!ui-state
        selected (:selected-fr state)
        show-all? (:show-all? state)
        search-query (:search-query state)
        implemented-set (intent/implemented-frs)
        frs (visible-frs state)
        handbook-scenario-count (visible-scenario-count frs)
        by-priority (group-by #(:priority (fr/get-fr %)) frs)
        priority-order [:critical :high :medium :low]]
    [:div {:style (:sidebar styles)}
     ;; Header
     [:div {:style {:padding "18px 16px"
                    :border-bottom "1px solid #27272a"}}
      [:h1 {:style {:margin "0"
                    :font-size "17px"
                    :font-weight "600"
                    :color "#fafafa"}}
       "Spec Handbook"]
      [:div {:style (:sidebar-kicker styles)}
       "Browse functional requirements as human-readable spec pages."]
      [:div {:style {:color "#a1a1aa"
                     :font-size "11px"
                     :margin-top "10px"
                     :font-family "'IBM Plex Mono', monospace"}}
       (str (count frs) " FRs · " handbook-scenario-count " executable scenarios")]

      [:input {:type "text"
               :value search-query
               :placeholder "Search requirements"
               :style (:search-input styles)
               :on {:input (fn [event]
                             (set-ui-state! assoc :search-query (.. event -target -value)))}}]

      ;; Toggle
      [:div {:style {:margin-top "10px"
                     :display "flex"
                     :align-items "center"
                     :gap "8px"}}
       [:div {:style {:display "flex" :gap "4px" :background "#27272a"
                      :padding "3px" :border-radius "4px"}}
        [:button {:style {:background (if show-all? "#3f3f46" "transparent")
                          :border "none"
                          :color (if show-all? "#fafafa" "#71717a")
                          :padding "4px 10px" :border-radius "3px" :cursor "pointer"
                          :font-size "11px" :font-family "'IBM Plex Mono', monospace"}
                  :on {:click (fn [_] (set-ui-state! assoc :show-all? true))}}
         "All"]
        [:button {:style {:background (if-not show-all? "#3f3f46" "transparent")
                          :border "none"
                          :color (if-not show-all? "#fafafa" "#71717a")
                          :padding "4px 10px" :border-radius "3px" :cursor "pointer"
                          :font-size "11px" :font-family "'IBM Plex Mono', monospace"}
                  :on {:click (fn [_] (set-ui-state! assoc :show-all? false))}}
         "Intent-linked"]]]
      [:div {:style (:sidebar-help styles)}
       (if show-all?
         "Showing the full handbook. Switch to intent-linked if you only want requirements with registered runtime linkage."
         (str "Showing " (count frs) " requirements currently cited by registered intents."))]]

     ;; FR list by priority
     (if (seq frs)
       (for [priority priority-order
             :let [fr-ids (get by-priority priority)]
             :when (seq fr-ids)]
         ^{:key priority}
         [:div
          [:div {:style {:padding "8px 16px"
                         :background "#0f0f10"
                         :font-size "10px"
                         :font-weight "600"
                         :color "#52525b"
                         :text-transform "uppercase"
                         :letter-spacing "0.5px"
                         :border-bottom "1px solid #27272a"}}
           (str (name priority) " (" (count fr-ids) ")")]
          (for [fr-id (sort fr-ids)]
            (let [fr (fr/get-fr fr-id)
                  is-selected (= fr-id selected)
                  is-implemented? (contains? implemented-set fr-id)
                  scenarios (executable-scenarios fr)
                  has-scenarios? (seq scenarios)]
              ^{:key fr-id}
              [:div {:style (merge (:fr-item styles)
                                   (when is-selected (:fr-item-selected styles)))
                     :on {:click (fn [_] (set-ui-state! assoc :selected-fr fr-id))}}
               [:div {:style {:display "flex" :align-items "center" :gap "6px"}}
                [:span {:style (:fr-title styles)}
                 (name fr-id)]]
               [:div {:style (:fr-desc styles)}
                (let [desc (:desc fr)]
                  (if (> (count desc) 55)
                    (str (subs desc 0 52) "...")
                    desc))]
               [:div {:style (:fr-meta styles)}
                [:span (safe-name (:priority fr))]
                (when has-scenarios?
                  [:span (str (count scenarios) " ex")])
                (when is-implemented?
                  [:span {:style {:color "#22c55e"}} "linked"])
                (when-let [first-tag (first (:tags fr))]
                  [:span (str "#" (safe-name first-tag))])]]))])
       [:div {:style {:padding "20px 16px"
                      :color "#71717a"
                      :font-size "12px"
                      :line-height "1.5"}}
        "No matching FRs. Adjust the filter or search query."])]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Component
;; ══════════════════════════════════════════════════════════════════════════════

(defn SpecHandbook
  "Main handbook/spec browser component."
  []
  (let [state @!ui-state
        selected-fr (:selected-fr state)]
    [:div {:style (:container styles)}
     ;; Font import
     [:style "
       @import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Serif:wght@400;500;600&display=swap');
     "]

     ;; Sidebar
     (FRSidebar)

     ;; Main content
     [:div {:style (:main styles)}
      (if selected-fr
        (FRDetail {:fr-id selected-fr})
        [:div {:style {:padding "48px 16px"
                       :max-width "520px"
                       :color "#a1a1aa"}}
         [:h2 {:style {:margin "0 0 10px 0"
                       :font-size "20px"
                       :font-weight "600"
                       :color "#fafafa"}}
         "No FR Selected"]
         [:p {:style {:margin "0"
                      :line-height "1.6"}}
          "The current filter returned no functional requirements. Adjust the sidebar search or switch between Implemented and All."]])]]))

(defn SpecViewer
  "Route to either the handbook viewer or the essay viewer."
  []
  (if (essay-mode?)
    (SpecEssay)
    (SpecHandbook)))
