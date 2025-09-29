# Malli - Ingenious Patterns Analysis

## Repository: metosin/malli
**Category**: Schema Validation + Type System
**Language**: Clojure/ClojureScript
**Paradigm**: Data-Driven Schemas + Runtime Validation

## 📁 Key Namespaces to Study
- `malli.core` - Schema definition and validation core
- `malli.generator` - Generative testing integration
- `malli.transform` - Data transformation pipelines
- `malli.instrument` - Function instrumentation
- `malli.dev` - Development-time enhancements

---

## 🧠 The 8 Most Ingenious Patterns

### 1. **Schema-as-Data with Runtime Manipulation** ⭐⭐⭐⭐⭐
**Location**: `src/malli/core.cljc`

Schemas are plain data structures that can be manipulated at runtime:

```clojure
;; Schemas are just data
(def user-schema
  [:map
   [:name :string]
   [:age [:int {:min 0 :max 120}]]
   [:email [:re #".*@.*"]]])

;; Runtime schema manipulation
(def admin-schema
  (mu/assoc user-schema :permissions [:set :keyword]))

;; Dynamic schema generation
(defn create-api-schema [fields]
  [:map (map (fn [field] [field :string]) fields)])

;; Schema serialization/deserialization
(def serialized (json/encode user-schema))
(def deserialized (json/decode serialized))
(m/validate deserialized {:name "Alice" :age 30 :email "alice@test.com"})
```

**Innovation**: First-class schemas enable dynamic validation systems impossible with macro-based approaches.

### 2. **Transformation Pipeline Architecture** ⭐⭐⭐⭐⭐
**Location**: `src/malli/transform.cljc`

Composable data transformation using schema-driven decode/encode:

```clojure
;; Composable transformers
(def api-transformer
  (mt/transformer
    mt/string-transformer      ; String -> EDN
    mt/json-transformer        ; JSON specifics
    mt/strip-extra-keys-transformer  ; Remove unknown keys
    mt/default-value-transformer))    ; Apply defaults

;; Schema-driven transformation
(m/decode [:map [:age [:int {:default 0}]]]
          {:age "25" :extra "data"}
          api-transformer)
;; => {:age 25}

;; Custom transformers
(def my-transformer
  (mt/transformer
    {:name :my-transformer
     :decoders {:keyword (fn [schema value]
                          (when (string? value)
                            (keyword value)))}}))
```

**Innovation**: Schema-driven transformations eliminate boilerplate while ensuring type safety.

### 3. **Function Schema with Guards** ⭐⭐⭐⭐
**Location**: `src/malli/core.cljc`

Complete function contracts with input/output validation and relationship constraints:

```clojure
;; Function schemas with guards
(def safe-divide
  (m/=> [:=> [:cat :int :int] :double
         [:fn '(fn [{:keys [args ret]}]
                 (let [[a b] args]
                   (or (not= b 0) (= ret ##-Inf))))]]))

;; Multi-arity function schemas
(def add-schema
  [:function
   [:=> [:cat :int] :int]
   [:=> [:cat :int :int] :int]
   [:=> [:cat :int :int :int] :int]])

;; Guard ensuring output relationship
(def increment-schema
  [:=> [:cat :int] :int
   [:fn '(fn [{:keys [args ret]}]
           (= ret (inc (first args))))]])
```

**Innovation**: Guards enable validation of complex relationships between inputs and outputs.

### 4. **Generative Testing Integration** ⭐⭐⭐⭐
**Location**: `src/malli/generator.cljc`

Automatic test data generation from schemas:

```clojure
;; Generate test data
(mg/generate user-schema)
;; => {:name "KQM1qK", :age 73, :email "z@o"}

;; Customized generation
(mg/generate
  [:map
   [:name [:string {:gen/min 5 :gen/max 20}]]
   [:age [:int {:gen/min 18 :gen/max 65}]]
   [:role [:enum {:gen/elements [:admin :user :guest]}]]])

;; Function property testing
(defn test-function [f schema]
  (let [input-gen (mg/generator (first (:schema schema)))
        output-schema (second (:schema schema))]
    (prop/for-all [input input-gen]
      (m/validate output-schema (f input)))))
```

**Innovation**: Schema-driven property testing automatically validates function contracts.

### 5. **Live Instrumentation with Hot Reloading** ⭐⭐⭐⭐
**Location**: `src/malli/instrument.cljc`

Runtime function instrumentation with automatic re-instrumentation:

```clojure
;; Instrument functions
(mi/instrument! {:filters [#"myapp.*"]})

;; Schema in metadata
(defn ^{::m/schema [:=> [:cat :int :int] :int]}
  add [x y] (+ x y))

;; Development mode with auto-re-instrumentation
(malli.dev/start! {:report (fn [event] (tap> event))})

;; Violations reported with context
(add "not" "numbers")
;; Reports: {:type :malli.instrument/invalid-input
;;          :function add :input ["not" "numbers"] ...}
```

**Innovation**: Zero-overhead instrumentation in production with rich development-time validation.

### 6. **Schema Inference and Code Generation** ⭐⭐⭐
**Location**: Schema inference utilities

Automatically derive schemas from data or code:

```clojure
;; Infer schema from data
(mi/infer [{:name "Alice" :age 30}
           {:name "Bob" :age 25}])
;; => [:map [:name :string] [:age :int]]

;; Infer from destructuring
(mi/infer-from-destructure '[{:keys [name age]}])
;; => [:map [:name :any] [:age :any]]

;; Generate code from schemas
(defn generate-constructor [schema]
  (let [props (m/children schema)]
    `(defn ~'create-entity [~@(map first props)]
       ~(zipmap (map first props) (map first props)))))
```

**Innovation**: Automatic schema inference reduces manual schema definition effort.

### 7. **Recursive Schema with Cycle Detection** ⭐⭐⭐
**Location**: Recursive schema handling

Handle recursive data structures safely:

```clojure
;; Recursive schemas
(def tree-schema
  [:schema {:registry {"tree" [:map
                              [:value :int]
                              [:children [:vector [:ref "tree"]]]]}}
   "tree"])

;; Generate finite recursive data
(mg/generate tree-schema {:seed 42 :max-depth 3})
;; => {:value 123 :children [{:value 456 :children []}]}

;; Validation with cycle detection
(m/validate tree-schema cyclic-data)  ; Safely handles cycles
```

**Innovation**: Safe handling of recursive schemas prevents infinite loops in validation and generation.

### 8. **Performance-Optimized Validation** ⭐⭐⭐
**Location**: Performance optimizations

Pre-compiled validators for hot paths:

```clojure
;; Pre-compiled validators
(def fast-validator (m/validator user-schema))
(def fast-explainer (m/explainer user-schema))

;; Batch validation
(def users-validator
  (m/validator [:vector user-schema]))

;; Custom validation for performance
(defn validate-user-fast [user]
  (and (string? (:name user))
       (int? (:age user))
       (<= 0 (:age user) 120)))
```

**Innovation**: Compilation strategies optimize validation performance for production use.

---

## 🎯 Key Takeaways

1. **Schemas as Data**: First-class schemas enable runtime manipulation and dynamic validation
2. **Transformation Pipelines**: Schema-driven data transformation eliminates boilerplate
3. **Function Contracts**: Complete function validation with input/output/relationship constraints
4. **Generative Testing**: Automatic property testing from schema definitions
5. **Live Development**: Hot-reloading instrumentation for enhanced developer experience
6. **Inference**: Automatic schema generation from data and code patterns
7. **Safe Recursion**: Proper handling of recursive data structures
8. **Performance**: Pre-compilation strategies for production performance

Malli demonstrates how data-driven schemas can provide more flexibility and power than traditional macro-based validation libraries.