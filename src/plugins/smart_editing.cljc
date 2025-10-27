(ns plugins.smart-editing
  "Smart editing behaviors: context-aware editing operations.

   Features:
   - Merge with next block (Delete at end)
   - List formatting (auto-increment, empty list unformat)
   - Checkbox toggling
   - Smart text manipulation (future: markup exit, code blocks)"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.query :as q]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; ── Private Helpers ───────────────────────────────────────────────────────────

(defn- get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(defn- list-marker?
  "Check if text is only a list marker (empty list item)."
  [text]
  (re-matches #"^([-*+]|\d+\.)\s*$" text))

(defn- extract-list-number
  "Extract number from numbered list marker. Returns nil if not numbered."
  [text]
  (when-let [match (re-matches #"^(\d+)\.\s.*" text)]
    #?(:clj (Integer/parseInt (second match))
       :cljs (js/parseInt (second match)))))

(defn- toggle-checkbox-text
  "Toggle checkbox in text. Returns updated text."
  [text]
  (cond
    (str/includes? text "[ ]")
    (str/replace-first text "[ ]" "[x]")

    (str/includes? text "[x]")
    (str/replace-first text "[x]" "[ ]")

    (str/includes? text "[X]")
    (str/replace-first text "[X]" "[x]")

    :else text))

;; ── Merge Operations ──────────────────────────────────────────────────────────

(intent/register-intent! :merge-with-next
  {:doc "Merge block with next sibling, delete next block."
   :spec [:map [:type [:= :merge-with-next]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (let [next-id (get-in db [:derived :next-id-of block-id])
                    curr-text (get-block-text db block-id)
                    next-text (get-block-text db next-id)
                    merged-text (str curr-text next-text)
                    next-children (get-in db [:children-by-parent next-id] [])]
                (when next-id
                  (concat
                   ;; Update current block with merged text
                   [{:op :update-node :id block-id :props {:text merged-text}}]
                   ;; Move next block's children to current block (as last children)
                   (map (fn [child-id]
                          {:op :place :id child-id :under block-id :at :last})
                        next-children)
                   ;; Delete next block
                   [{:op :place :id next-id :under const/root-trash :at :last}]))))})

;; ── List Item Behaviors ───────────────────────────────────────────────────────

(intent/register-intent! :unformat-empty-list
  {:doc "Remove list marker from empty list item (becomes plain block)."
   :spec [:map [:type [:= :unformat-empty-list]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (let [text (get-block-text db block-id)]
                (when (list-marker? text)
                  [{:op :update-node :id block-id :props {:text ""}}])))})

(intent/register-intent! :split-with-list-increment
  {:doc "Split block at cursor, incrementing numbered list marker if applicable."
   :spec [:map [:type [:= :split-with-list-increment]]
          [:block-id :string]
          [:cursor-pos :int]]
   :handler (fn [db {:keys [block-id cursor-pos]}]
              (let [text (get-block-text db block-id)
                    before (subs text 0 cursor-pos)
                    after (subs text cursor-pos)
                    parent (get-in db [:derived :parent-of block-id])
                    new-id (str "block-" (random-uuid))
                    list-num (extract-list-number text)
                    new-text (if list-num
                              (str (inc list-num) ". " after)
                              after)]
                (when parent
                  [{:op :update-node :id block-id :props {:text before}}
                   {:op :create-node :id new-id :type :block :props {:text new-text}}
                   {:op :place :id new-id :under parent :at {:after block-id}}])))})

;; ── Checkbox Operations ───────────────────────────────────────────────────────

(intent/register-intent! :toggle-checkbox
  {:doc "Toggle checkbox state in block text ([ ] <-> [x])."
   :spec [:map [:type [:= :toggle-checkbox]] [:block-id :string]]
   :handler (fn [db {:keys [block-id]}]
              (let [text (get-block-text db block-id)
                    new-text (toggle-checkbox-text text)]
                (when (not= text new-text)
                  [{:op :update-node :id block-id :props {:text new-text}}])))})
