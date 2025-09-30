# Clean Instrumentation Pattern for struct.cljc

## Problem
We want to add validation, lineage tracking, and explicit results WITHOUT muddying the core compiler functions.

## Solution: Malli + Macro + Composition

### 1. Add Intent Schemas (in core/schema.cljc)

```clojure
;; Intent schemas - describes what users send
(def Intent-Delete
  [:map
   [:type [:= :delete]]
   [:id Id]])

(def Intent-Indent
  [:map
   [:type [:= :indent]]
   [:id Id]])

(def Intent-Outdent
  [:map
   [:type [:= :outdent]]
   [:id Id]])

(def Intent
  [:or Intent-Delete Intent-Indent Intent-Outdent])

;; Compilation result
(def CompilationResult
  [:map
   [:ops [:vector Op]]
   [:issues [:vector Issue]]
   [:trace {:optional true} [:vector :any]]])
```

### 2. Keep Original Functions Pure (NO CHANGES)

```clojure
;; These stay EXACTLY as they are - clean logic only
(defn indent-ops [DB id]
  (if-let [sib (prev-sibling DB id)]
    [{:op :place :id id :under sib :at :last}]
    []))

(defn outdent-ops [DB id]
  (let [p  (parent-of DB id)
        gp (grandparent-of DB id)]
    (if (and p gp)
      [{:op :place :id id :under gp :at {:after p}}]
      [])))
```

### 3. Use Macro to Add Instrumentation Layer

```clojure
(ns core.struct.instrument
  (:require [malli.core :as m]
            [core.schema :as schema]))

(defmacro defcompiler
  "Defines a compiler function with automatic:
   - Schema validation of intent
   - Precondition checking
   - Metadata injection
   - Issue reporting"
  [name docstring schema preconditions impl-fn]
  `(defn ~name
     ~docstring
     [DB# intent#]
     ;; 1. Schema validation at boundary
     (if-let [error# (m/explain ~schema intent#)]
       {:ops []
        :issues [{:issue :invalid-intent
                  :intent intent#
                  :schema-errors (me/humanize error#)}]}

       ;; 2. Check preconditions (returns nil or issue map)
       (if-let [issue# (~preconditions DB# intent#)]
         {:ops []
          :issues [issue#]}

         ;; 3. Call pure implementation
         (let [ops# (~impl-fn DB# intent#)]
           {:ops (mapv #(assoc % :meta {:intent intent#
                                         :compiler '~name})
                       ops#)
            :issues []})))))

;; Example usage - precondition functions are pure
(defn has-prev-sibling? [DB {:keys [id]}]
  (when-not (prev-sibling DB id)
    {:issue :no-prev-sibling
     :id id
     :reason "node is first child"}))

(defn has-grandparent? [DB {:keys [id]}]
  (when-not (grandparent-of DB id)
    {:issue :no-grandparent
     :id id
     :reason "node is top-level"}))

;; Now define instrumented compilers using original functions
(defcompiler compile-indent
  "Compiles indent intent with validation"
  schema/Intent-Indent
  has-prev-sibling?
  (fn [DB {:keys [id]}] (indent-ops DB id)))

(defcompiler compile-outdent
  "Compiles outdent intent with validation"
  schema/Intent-Outdent
  has-grandparent?
  (fn [DB {:keys [id]}] (outdent-ops DB id)))
```

### 4. Or Use Function Composition (No Macros)

```clojure
(defn with-validation
  "Wraps a compiler function with schema validation"
  [schema f]
  (fn [DB intent]
    (if-let [error (m/explain schema intent)]
      [[] [{:issue :invalid-intent :errors (me/humanize error)}]]
      (f DB intent))))

(defn with-precondition
  "Wraps a compiler with a precondition check"
  [check-fn f]
  (fn [DB intent]
    (if-let [issue (check-fn DB intent)]
      [[] [issue]]
      (f DB intent))))

(defn with-metadata
  "Wraps ops with lineage metadata"
  [compiler-name f]
  (fn [DB intent]
    (let [[ops issues] (f DB intent)]
      [(mapv #(assoc % :meta {:intent intent :compiler compiler-name}) ops)
       issues])))

;; Compose clean layers around pure functions
(def compile-indent
  (-> (fn [DB {:keys [id]}] [(indent-ops DB id) []])
      (with-precondition has-prev-sibling?)
      (with-validation schema/Intent-Indent)
      (with-metadata :indent)))

;; Or use threading for clarity
(defn make-compiler [impl-fn schema precond compiler-name]
  (-> impl-fn
      (with-precondition precond)
      (with-validation schema)
      (with-metadata compiler-name)))

(def compile-indent-v2
  (make-compiler
    (fn [DB {:keys [id]}] [(indent-ops DB id) []])
    schema/Intent-Indent
    has-prev-sibling?
    :indent))
```

### 5. Even Simpler: Validate at Boundary Only

```clojure
(ns core.struct
  (:require [malli.core :as m]
            [core.schema :as schema]))

;; Original functions unchanged!
(defn indent-ops [DB id] ...)
(defn outdent-ops [DB id] ...)

;; Single validation point at the public API
(defn compile-intents [DB intents]
  ;; Schema check once at boundary
  (let [validation-errors (keep-indexed
                           (fn [idx intent]
                             (when-let [err (m/explain schema/Intent intent)]
                               {:idx idx :intent intent :error (me/humanize err)}))
                           intents)]
    (if (seq validation-errors)
      {:ops [] :errors validation-errors}

      ;; Precondition check layer
      (let [results (map (fn [intent]
                           (let [issue (precondition-check DB intent)]
                             (if issue
                               {:ops [] :issues [issue] :intent intent}
                               {:ops (compile-intent DB intent)
                                :issues []
                                :intent intent})))
                         intents)]
        {:ops (vec (mapcat :ops results))
         :issues (vec (mapcat :issues results))
         :trace (mapv #(select-keys % [:intent :issues]) results)}))))

(defn precondition-check [DB {:keys [type id] :as intent}]
  (case type
    :indent  (when-not (prev-sibling DB id)
               {:issue :no-prev-sibling :id id})
    :outdent (when-not (grandparent-of DB id)
               {:issue :no-grandparent :id id})
    :delete  nil  ; delete always works
    nil))
```

## Recommendation: Start with #5 (Boundary Validation)

**Why?**
- Zero changes to existing functions
- Single validation point = easier to maintain
- Simpler than macros
- 80% of value, 20% of complexity

**Implementation plan:**
1. Add intent schemas to `core/schema.cljc`
2. Add precondition checks to `compile-intents`
3. Change return type to `{:ops [] :issues [] :trace []}`
4. Keep all existing `-ops` functions pure

**DX win:**
```clojure
;; Before
(compile-intents db [{:type :indent :id "a"}])
;; => []  😞 mystery

;; After
(compile-intents db [{:type :indent :id "a"}])
;; => {:ops []
;;     :issues [{:issue :no-prev-sibling :id "a"}]
;;     :trace [{:intent {...} :reason "first child"}]}  😊 clarity
```

Want me to implement the boundary validation approach (#5)?