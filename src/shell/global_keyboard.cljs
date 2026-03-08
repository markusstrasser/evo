(ns shell.global-keyboard
  "Global non-contenteditable keyboard adapter.

   Owns app-level shortcuts plus the small amount of DOM enrichment that is
   still required before an intent reaches the canonical executor path."
  (:require [components.lightbox :as lightbox]
            [kernel.history :as H]
            [kernel.query :as q]
            [keymap.core :as keymap]
            [shell.executor :as executor]
            [shell.view-state :as vs]
            [utils.block-dom :as block-dom]
            [utils.text-selection :as text-sel]))

(defn- idle-state?
  [editing-block-id focus-id]
  (and (nil? editing-block-id) (nil? focus-id)))

(defn- printable-key?
  [event key]
  (and (= 1 (.-length key))
       (not (:mod event))
       (not (:alt event))
       (not (contains? #{"Enter" "Escape" "Tab" "Backspace" "Delete"} key))))

(defn- selection-all-text?
  [editable-el]
  (try
    (let [sel (.getSelection js/window)
          text-length (count (.-textContent editable-el))]
      (and sel
           (pos? (.-rangeCount sel))
           (= (count (str (.toString sel))) text-length)))
    (catch js/Error _
      false)))

(defn- event-target-element
  [e]
  (let [target (.-target e)]
    (cond
      (nil? target) nil
      (= 3 (.-nodeType target)) (.-parentElement target)
      :else target)))

(defn- form-control-target?
  [target]
  (boolean
   (when target
     (or (.-isContentEditable target)
         (contains? #{"INPUT" "TEXTAREA" "SELECT"} (.-tagName target))))))

(defn- closest-element
  [target selector]
  (when (and target (.-closest target))
    (.closest target selector)))

(defn- block-root-id
  [target]
  (some-> (closest-element target "[data-block-id]")
          (.getAttribute "data-block-id")))

(defn- editing-target-context
  "Return the active editable element only when the DOM target belongs to the
   same block the session currently marks as editing."
  [e editing-block-id]
  (when editing-block-id
    (let [target (event-target-element e)
          editable-el (or (when (and target (.-isContentEditable target)) target)
                          (closest-element target "[contenteditable='true']"))]
      (when (= editing-block-id (block-root-id editable-el))
        {:target target
         :editable-el editable-el}))))

(defn- inject-effective-block-id
  [intent-map focus-id editing-block-id]
  (let [effective-block-id (or focus-id editing-block-id)]
    (if (and effective-block-id
             (#{:toggle-fold :collapse :expand-all :zoom-in} (:type intent-map)))
      (assoc intent-map :block-id effective-block-id)
      intent-map)))

(defn- replace-editing-block-placeholder
  [intent-map editing-block-id]
  (if (and editing-block-id
           (= (:block-id intent-map) :editing-block-id))
    (assoc intent-map :block-id editing-block-id)
    intent-map))

(defn- enrich-format-selection-intent
  [handle-intent editing-block-id editable-el intent-map]
  (try
    (let [pos-info (text-sel/get-position editable-el)]
      (when pos-info
        (let [{:keys [position extent]} pos-info]
          (when (pos? extent)
            (let [dom-text (.-textContent editable-el)]
              (handle-intent {:type :update-content
                              :block-id editing-block-id
                              :text dom-text})
              (merge intent-map
                     {:block-id editing-block-id
                      :start position
                      :end (+ position extent)}))))))
    (catch js/Error e
      (js/console.error "Selection read failed:" e)
      nil)))

(defn- enrich-follow-link-intent
  [intent-map]
  (try
    (when-let [sel (.getSelection js/window)]
      (assoc intent-map :cursor-pos (.-anchorOffset sel)))
    (catch js/Error e
      (js/console.error "Cursor position read failed:" e)
      nil)))

(defn- enrich-selection-navigation-intent
  [focus-id intent-map]
  (let [direction (case (:mode intent-map)
                    (:next :extend-next) :down
                    (:prev :extend-prev) :up)
        dom-fallback (block-dom/get-adjacent-block-by-dom direction focus-id)]
    (if dom-fallback
      (assoc intent-map :dom-adjacent-id dom-fallback)
      intent-map)))

(defn- enrich-intent
  [handle-intent focus-id editing-block-id editable-el intent-type]
  (let [intent-map (cond-> intent-type
                     (map? intent-type)
                     (-> (inject-effective-block-id focus-id editing-block-id)
                         (replace-editing-block-placeholder editing-block-id)))]
    (cond
      (not (map? intent-map))
      intent-map

      (and (= (:type intent-map) :format-selection) editing-block-id editable-el)
      (enrich-format-selection-intent handle-intent editing-block-id editable-el intent-map)

      (and (= (:type intent-map) :follow-link-under-cursor)
           (= (:cursor-pos intent-map) :cursor-pos))
      (when editing-block-id
        (enrich-follow-link-intent intent-map))

      (and (= (:type intent-map) :selection)
           (#{:next :prev :extend-next :extend-prev} (:mode intent-map))
           (vs/journals-view?)
           focus-id)
      (enrich-selection-navigation-intent focus-id intent-map)

      :else
      intent-map)))

(defn- structural-edit-intent?
  [intent]
  (contains? #{:indent-selected :outdent-selected :move-selected-up :move-selected-down}
             (if (map? intent) (:type intent) intent)))

(defn- commit-editing-buffer-before-structural!
  [handle-intent editing-block-id editable-el]
  (when (and editing-block-id editable-el)
    (vs/keep-edit-on-blur!)
    (let [sel (.getSelection js/window)
          saved-cursor-pos (when sel (.-anchorOffset sel))
          buffer-text (vs/buffer-text editing-block-id)
          dom-text (.-textContent editable-el)
          final-text (or buffer-text dom-text)]
      (when final-text
        (handle-intent {:type :update-content
                        :block-id editing-block-id
                        :text final-text}))
      (when saved-cursor-pos
        (vs/set-cursor-position! saved-cursor-pos)))))

(defn handle-keydown
  "Global keyboard resolver for app-level shortcuts."
  [!db handle-intent e]
  (when (lightbox/handle-keydown e)
    (.preventDefault e))

  (when-not (.-defaultPrevented e)
    (let [session-editing-block-id (vs/editing-block-id)
          editing-context (editing-target-context e session-editing-block-id)
          target-el (event-target-element e)
          foreign-editable? (and (form-control-target? target-el)
                                 (nil? editing-context))]
      (when-not foreign-editable?
        (let [event (keymap/parse-dom-event e)
              db @!db
              current-session (cond-> (vs/get-view-state)
                                (and session-editing-block-id
                                     (nil? editing-context))
                                (assoc-in [:ui :editing-block-id] nil))
              key (.-key e)
              mod? (or (.-metaKey e) (.-ctrlKey e))
              shift? (.-shiftKey e)
              focus-id (vs/focus-id)
              editing-block-id (when editing-context session-editing-block-id)
              idle? (idle-state? editing-block-id focus-id)
              intent-type (keymap/resolve-intent-type event current-session)
              editable-el (:editable-el editing-context)]

          (cond
            (and idle? intent-type (contains? #{"Enter" "Backspace" "Delete" "Tab"} key))
            nil

            (and idle? mod? (= key "Enter"))
            nil

            (and idle? shift? (= key "Enter"))
            nil

            (and idle? shift? (contains? #{"ArrowUp" "ArrowDown"} key))
            (let [visible-blocks (q/visible-blocks db current-session)
                  target-id (if (= key "ArrowUp")
                              (last visible-blocks)
                              (first visible-blocks))]
              (when target-id
                (.preventDefault e)
                (handle-intent {:type :selection :mode :replace :ids target-id})))

            (and intent-type
                 editing-block-id
                 shift?
                 (contains? #{"ArrowUp" "ArrowDown"} key)
                 (not mod?)
                 (not (.-altKey e)))
            nil

            (and mod? (= key "v") (not shift?) focus-id (not editing-block-id))
            (do
              (.preventDefault e)
              (-> (js/navigator.clipboard.readText)
                  (.then (fn [text]
                           (when (and text (pos? (count text)))
                             (handle-intent {:type :paste-text
                                             :block-id focus-id
                                             :cursor-pos 0
                                             :selection-end (count (get-in db [:nodes focus-id :props :text] ""))
                                             :pasted-text text}))))
                  (.catch (fn [err]
                            (js/console.error "Clipboard read failed:" err)))))

            (and editing-block-id mod? (= key "a") (not shift?) editable-el)
            (when (selection-all-text? editable-el)
              (.preventDefault e)
              (handle-intent {:type :select-all-cycle
                              :from-editing? true
                              :block-id editing-block-id}))

            intent-type
            (do
              (.preventDefault e)
              (cond
                (= intent-type :undo)
                (when-let [{:keys [db session]} (H/undo @!db (vs/get-view-state))]
                  (when session
                    (vs/merge-view-state-updates! session))
                  (reset! !db db)
                  (executor/assert-derived-fresh! db "after undo"))

                (= intent-type :redo)
                (when-let [{:keys [db session]} (H/redo @!db (vs/get-view-state))]
                  (when session
                    (vs/merge-view-state-updates! session))
                  (reset! !db db)
                  (executor/assert-derived-fresh! db "after redo"))

                :else
                (let [enriched-intent (enrich-intent handle-intent
                                                     focus-id
                                                     editing-block-id
                                                     editable-el
                                                     intent-type)]
                  (when enriched-intent
                    (when (and editing-block-id
                               (structural-edit-intent? enriched-intent))
                      (commit-editing-buffer-before-structural! handle-intent
                                                               editing-block-id
                                                               editable-el))
                    (if (map? enriched-intent)
                      (handle-intent enriched-intent)
                      (handle-intent {:type enriched-intent}))))))

            (and (printable-key? event key) focus-id (not editing-block-id))
            (do
              (.preventDefault e)
              (handle-intent {:type :enter-edit-with-char
                              :block-id focus-id
                              :char key}))))))))
