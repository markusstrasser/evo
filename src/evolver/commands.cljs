(ns evolver.commands
  (:require [evolver.kernel :as K]
            [evolver.middleware :as MW]
            [evolver.history :as history]))

;; ---------- 0) Tiny utilities ----------
(defn db [store] (:present @store)) ; Extract present state from history ring
(defn selv [store] (get-in (db store) [:view :selection] []))
(defn selset [store] (set (selv store)))
(defn cursor [store] (peek (selv store)))
(defn pos-of [store id] (K/node-position (db store) id))

(defn set-selection! [store v]
  (swap! store assoc-in [:present :view :selection] v)) ; last clicked = primary

(defn toggle-in-ordered [v x]
  (if (some #{x} v) (vec (remove #{x} v)) (conj v x)))

(defn- sort-by-index [db ids]
  (->> ids (sort-by #(-> (K/node-position db %) :index))))

(defn apply! [store cmd]
  "Applies a command via the middleware pipeline and pushes the new state onto the history ring."
  (when cmd
    (let [current-history-ring @store
          current-db (:present current-history-ring)
          ;; The middleware pipeline expects the DB state, not the whole ring
          new-db (MW/safe-apply-command-with-middleware current-db cmd)]
      (when (not= new-db current-db) ; Only update if something changed
        (let [new-history-ring (history/push-snapshot current-history-ring new-db)]
          (reset! store new-history-ring))))))

(defn modifiers [event]
  (when-let [e (:replicant/dom-event event)]
    {:shift? (.getModifierState e "Shift")
     :ctrl? (.getModifierState e "Control")
     :meta? (.getModifierState e "Meta")
     :stop! #(.stopPropagation e)}))

(defn ctx [store]
  (let [s (selv store)]
    {:db (db store)
     :cursor (cursor store)
     :selected s
     :selected1 (first s)
     :pos (fn [id] (K/node-position (db store) id))}))

;; ---------- 1) Pure intent → command builders ----------
(def intent->command
  {;; creation
   :create-child-block
   (fn [{:keys [cursor]}]
     (when cursor
       {:op :insert
        :parent-id cursor
        :node-id (K/gen-new-id)
        :node-data {:type :div :props {:text "New child"}}
        :position nil}))

   :create-sibling-above
   (fn [{:keys [cursor pos]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)]
         {:op :insert :parent-id parent :node-id (K/gen-new-id)
          :node-data {:type :div :props {:text "New sibling"}}
          :position index})))

   :create-sibling-below
   (fn [{:keys [cursor pos]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)]
         {:op :insert :parent-id parent :node-id (K/gen-new-id)
          :node-data {:type :div :props {:text "New sibling"}}
          :position (inc index)})))

   ;; ENTER key behavior - context-sensitive block creation
   :enter-new-block
   (fn [{:keys [cursor pos cursor-position block-content]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)]
         (if (or (nil? cursor-position) (= cursor-position (count (or block-content ""))))
           ;; Cursor at end - create new sibling
           {:op :insert :parent-id parent :node-id (K/gen-new-id)
            :node-data {:type :div :props {:text ""}}
            :position (inc index)}
           ;; Cursor in middle - split block
           (let [content-before (subs (or block-content "") 0 cursor-position)
                 content-after (subs (or block-content "") cursor-position)]
             {:op :transaction
              :commands [{:op :patch :node-id cursor :updates {:props {:text content-before}}}
                         {:op :insert :parent-id parent :node-id (K/gen-new-id)
                          :node-data {:type :div :props {:text content-after}}
                          :position (inc index)}]})))))

   ;; SHIFT+ENTER behavior - line break within block
   :enter-line-break
   (fn [{:keys [cursor cursor-position block-content]}]
     (when cursor
       (let [content (or block-content "")
             new-content (str (subs content 0 (or cursor-position 0))
                              "\n"
                              (subs content (or cursor-position 0)))]
         {:op :patch :node-id cursor :updates {:props {:text new-content}}})))

   ;; Backspace at start - merge with previous block
   :backspace-merge-up
   (fn [{:keys [cursor pos db cursor-position]}]
     (when (and cursor (= cursor-position 0))
       (let [prev-node (K/get-prev db cursor)]
         (when prev-node
           (let [current-node (get-in db [:nodes cursor])
                 prev-node-data (get-in db [:nodes prev-node])
                 current-text (get-in current-node [:props :text] "")
                 prev-text (get-in prev-node-data [:props :text] "")
                 merged-text (str prev-text current-text)
                 current-children (get-in db [:children-by-parent cursor] [])]
             {:op :transaction
              :commands (concat
                         [{:op :patch :node-id prev-node :updates {:props {:text merged-text}}}]
                         (for [child current-children]
                           {:op :move :node-id child :new-parent-id prev-node :position nil})
                         [{:op :delete :node-id cursor}])})))))

   ;; Delete at end - merge with next block  
   :delete-merge-down
   (fn [{:keys [cursor pos db cursor-position block-content]}]
     (when (and cursor (= cursor-position (count (or block-content ""))))
       (let [next-node (K/get-next db cursor)]
         (when next-node
           (let [current-node (get-in db [:nodes cursor])
                 next-node-data (get-in db [:nodes next-node])
                 current-text (get-in current-node [:props :text] "")
                 next-text (get-in next-node-data [:props :text] "")
                 merged-text (str current-text next-text)
                 next-children (get-in db [:children-by-parent next-node] [])]
             {:op :transaction
              :commands (concat
                         [{:op :patch :node-id cursor :updates {:props {:text merged-text}}}]
                         (for [child next-children]
                           {:op :move :node-id child :new-parent-id cursor :position nil})
                         [{:op :delete :node-id next-node}])})))))

   ;; structure moves
   :indent
   (fn [{:keys [cursor pos]}]
     (when cursor
       (let [{:keys [index parent children]} (pos cursor)]
         (when (> index 0)
           {:op :move :node-id cursor
            :new-parent-id (nth children (dec index))
            :position nil}))))

   :outdent
   (fn [{:keys [cursor pos]}]
     (when cursor
       (let [{:keys [parent]} (pos cursor)]
         (when (not= parent K/root-id)
           (let [{p2 :parent i2 :index} (pos parent)]
             {:op :move :node-id cursor
              :new-parent-id p2
              :position (inc i2)})))))

   ;; references (requires exactly 2 selected)
   :add-reference
   (fn [{:keys [selected]}]
     (when (= 2 (count selected))
       (let [[from to] (vec selected)]
         {:op :add-reference :from-node-id from :to-node-id to})))

   :remove-reference
   (fn [{:keys [selected]}]
     (when (= 2 (count selected))
       (let [[from to] (vec selected)]
         {:op :remove-reference :from-node-id from :to-node-id to})))

   ;; reorder within parent
   :move-up
   (fn [{:keys [cursor pos db]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)
             new (max 0 (dec index))]
         (when (not= index new)
           {:op :reorder :node-id cursor :parent-id parent
            :from-index index :to-index new}))))

   :move-down
   (fn [{:keys [cursor pos db]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)
             sibs (get-in db [:children-by-parent parent])
             new (min (dec (count sibs)) (inc index))]
         (when (not= index new)
           {:op :reorder :node-id cursor :parent-id parent
            :from-index index :to-index new}))))})

(def intent->nav
  {;; navigation helpers that return navigation targets
   :nav-up
   (fn [{:keys [cursor db]}]
     (when cursor
       (K/get-prev db cursor)))

   :nav-down
   (fn [{:keys [cursor db]}]
     (when cursor
       (K/get-next db cursor)))

   :nav-sibling-up
   (fn [{:keys [cursor pos db]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)]
         (when (> index 0)
           (let [siblings (get-in db [:children-by-parent parent])]
             (nth siblings (dec index)))))))

   :nav-sibling-down
   (fn [{:keys [cursor pos db]}]
     (when cursor
       (let [{:keys [parent index]} (pos cursor)
             siblings (get-in db [:children-by-parent parent])]
         (when (< (inc index) (count siblings))
           (nth siblings (inc index))))))

   :nav-parent
   (fn [{:keys [cursor db]}]
     (when cursor
       (let [parent-id (K/get-parent db cursor)]
         (when (and parent-id (not= parent-id K/root-id))
           parent-id))))

   :nav-first-child
   (fn [{:keys [cursor db]}]
     (when cursor
       (K/get-first-child db cursor)))

   :nav-last-child
   (fn [{:keys [cursor db]}]
     (when cursor
       (K/get-last-child db cursor)))})

;; ---------- 2) Thin UI handlers (effects kept here on purpose) ----------
(defn select-node [store event {:keys [node-id]}]
  (let [m (modifiers event)
        _ (when m ((:stop! m)))
        chord? (when m (or (:shift? m) (:ctrl? m) (:meta? m)))
        v (selv store)
        next (if chord? (toggle-in-ordered v node-id) [node-id])]
    (set-selection! store next)
    (swap! store update :present K/log-operation {:op :select-node :node-id node-id})))

(defn set-selected-op [store event _]
  (let [v (some-> event :replicant/dom-event .-target .-value)]
    (swap! store assoc-in [:present :selected-op] (when (not= v "") (keyword v)))))

(defn apply-selected-op [store _ _]
  (when-let [op (:selected-op (db store))]
    (when-let [build (intent->command op)]
      (apply! store (build (ctx store))))))

(defn hover-node [store _event {:keys [node-id]}]
  (let [referencers (K/get-references (:present @store) node-id)]
    (swap! store assoc-in [:present :view :hovered-referencers] referencers)))

(defn unhover-node [store _event {:keys [node-id]}]
  (swap! store assoc-in [:present :view :hovered-referencers] #{}))

(defn undo! [store _ _] (swap! store history/undo))
(defn redo! [store _ _] (swap! store history/redo))

;; Navigation helpers that update selection
(defn navigate-to [store target-node]
  (when target-node
    (set-selection! store [target-node])))

;; ---------- 3) Registry (now tiny & consistent) ----------
(def command-registry
  {:select-node select-node
   :toggle-selection select-node ; Same as select-node for now
   :set-selected-op set-selected-op
   :apply-selected-op apply-selected-op
   :hover-node hover-node
   :unhover-node unhover-node
   :undo undo!
   :redo redo!

    ;; view-only helpers
   :clear-selection (fn [s _ _] (set-selection! s []))
   :select-all-blocks (fn [s _ _] (set-selection! s (vec (keys (:nodes (db s))))))

   ;; navigation commands (use nav helpers from intent->nav)
   :navigate-sequential (fn [s _ {:keys [direction]}]
                          (let [build (intent->nav (case direction :up :nav-up :down :nav-down))]
                            (navigate-to s (build (ctx s)))))

   :navigate-sequential-up (fn [s _ params]
                             (navigate-to s ((intent->nav :nav-up) (ctx s))))

   :navigate-sequential-down (fn [s _ params]
                               (navigate-to s ((intent->nav :nav-down) (ctx s))))

   :navigate-sibling (fn [s _ {:keys [direction]}]
                       (let [build (intent->nav (case direction :up :nav-sibling-up :down :nav-sibling-down))]
                         (navigate-to s (build (ctx s)))))

   :navigate-sibling-up (fn [s _ params]
                          (navigate-to s ((intent->nav :nav-sibling-up) (ctx s))))

   :navigate-sibling-down (fn [s _ params]
                            (navigate-to s ((intent->nav :nav-sibling-down) (ctx s))))

   :select-parent (fn [s _ _]
                    (navigate-to s ((intent->nav :nav-parent) (ctx s))))

   :select-first-child (fn [s _ _]
                         (navigate-to s ((intent->nav :nav-first-child) (ctx s))))

   :select-last-child (fn [s _ _]
                        (navigate-to s ((intent->nav :nav-last-child) (ctx s))))

    ;; direct intents (single node operations)
   :create-child-block (fn [s _ _] (apply! s ((intent->command :create-child-block) (ctx s))))
   :create-sibling-above (fn [s _ _] (apply! s ((intent->command :create-sibling-above) (ctx s))))
   :create-sibling-below (fn [s _ _] (apply! s ((intent->command :create-sibling-below) (ctx s))))
   :move-block (fn [s _ {:keys [direction]}]
                 (let [k (case direction :up :move-up :down :move-down)]
                   (apply! s ((intent->command k) (ctx s)))))

   :move-up (fn [s _ _]
              (apply! s ((intent->command :move-up) (ctx s))))

   :move-down (fn [s _ _]
                (apply! s ((intent->command :move-down) (ctx s))))

    ;; ENTER key behaviors
   :enter-new-block (fn [s _ _] (apply! s ((intent->command :enter-new-block) (ctx s))))

   :enter-line-break (fn [s event {:keys [cursor-position block-content]}]
                       (let [context (assoc (ctx s) :cursor-position cursor-position :block-content block-content)]
                         (when-let [cmd ((intent->command :enter-line-break) context)]
                           (apply! s cmd))))

    ;; Content merging operations
   :backspace-merge-up (fn [s event {:keys [cursor-position]}]
                         (let [context (assoc (ctx s) :cursor-position cursor-position)]
                           (when-let [cmd ((intent->command :backspace-merge-up) context)]
                             (apply! s cmd))))

   :delete-merge-down (fn [s event {:keys [cursor-position block-content]}]
                        (let [context (assoc (ctx s) :cursor-position cursor-position :block-content block-content)]
                          (when-let [cmd ((intent->command :delete-merge-down) context)]
                            (apply! s cmd))))

    ;; multi-node structural operations
   :indent-block (fn [s _ _]
                   (let [ids (sort-by-index (db s) (selv s))]
                     (doseq [id ids]
                       (when-let [cmd ((intent->command :indent) (assoc (ctx s) :cursor id))]
                         (apply! s cmd)))))

   :outdent-block (fn [s _ _]
                    (let [ids (sort-by-index (db s) (selv s))]
                      (doseq [id ids]
                        (when-let [cmd ((intent->command :outdent) (assoc (ctx s) :cursor id))]
                          (apply! s cmd)))))

    ;; multi-node operations
   :delete-selected-blocks
   (fn [s _ _]
     (doseq [id (selv s)]
       (apply! s {:op :delete :node-id id :recursive true}))
     (set-selection! s []))

    ;; toggle view state
   :toggle-collapse (fn [s _ _]
                      (let [selected (cursor s)]
                        (when selected
                          (let [current-collapsed (get-in @s [:present :view :collapsed] #{})
                                new-collapsed (if (contains? current-collapsed selected)
                                                (disj current-collapsed selected)
                                                (conj current-collapsed selected))]
                            (swap! s assoc-in [:present :view :collapsed] new-collapsed)))))})

(defn dispatch-command [store event [k params]]
  (if-let [h (command-registry k)]
    (try (h store event params)
         (catch js/Error e
           (swap! store update :present K/log-message :error (str "Command failed: " k)
                  {:command k :params params :error (.-message e)})))
    (swap! store update :present K/log-message :error (str "Unknown command: " k)
           {:command k :params params})))

(defn dispatch-commands [store event actions]
  (doseq [a (if (sequential? (first actions)) actions [actions])]
    (dispatch-command store event a)))