# Missionary - Ingenious Patterns Analysis

## Repository: leonoel/missionary
**Category**: Functional Reactive Programming + Effect System
**Language**: Clojure/ClojureScript
**Paradigm**: FRP + Functional Effects + Streaming

## 📁 Key Namespaces to Study
- `missionary.core` - Main API with Tasks, Flows, and Signals
- `missionary.impl.Continuous` - Continuous process implementation
- `missionary.impl.Latest` - Latest combinator for reactive signals
- `missionary.impl.Store` - Concurrent state management primitive
- `missionary.impl.RCF` - Reactive control flow implementation

---

## 🧠 The 12 Most Ingenious Patterns

### 1. **Unified Flow Protocol for Discrete and Continuous Streams** ⭐⭐⭐⭐⭐
**Location**: `src/missionary/core.cljc`
**Key Abstractions**: `Flow`, `Task`, `Signal`

Unifies discrete-time events and continuous-time values under a single protocol:

```clojure
(ns missionary.core :as m)

;; Discrete flows - emit values over time then terminate
(def discrete-flow
  (m/ap
    (m/?> (m/seed [1 2 3]))  ; Fork over collection
    (println "Processing:" m/?%)
    (* m/?% m/?%)))          ; Transform each value

;; Continuous flows (Signals) - time-varying values
(def !state (atom 0))
(def continuous-signal
  (m/cp
    (let [current (m/? (m/watch !state))]  ; Watch atom changes
      (* current 10))))                     ; Derive new signal

;; Both use the same Flow protocol
(m/reduce + discrete-flow)      ; => Task<30>
(m/sample continuous-signal)    ; => Current value * 10

;; Composition works across discrete/continuous boundaries
(def hybrid
  (m/ap
    (let [discrete-val (m/?> discrete-flow)
          continuous-val (m/?< continuous-signal)]
      (+ discrete-val continuous-val))))
```

**Innovation**: Single protocol unifies event streams and reactive values, enabling seamless composition between discrete and continuous reactive primitives.

### 2. **Ambiguous Evaluation with Fork Operators** ⭐⭐⭐⭐⭐
**Location**: `src/missionary/core.cljc`
**Key Operators**: `m/?>`, `m/?<`, `m/ap`

Fork execution into multiple parallel branches with different semantics:

```clojure
;; Sequential forking with m/?>
(def sequential-processing
  (m/ap
    (let [item (m/?> (m/seed [1 2 3 4 5]))]  ; Pull items sequentially
      (m/? (expensive-computation item)))))   ; Process each sequentially

;; Concurrent forking with parallelism control
(def concurrent-processing
  (m/ap
    (let [item (m/?> 3 (m/seed [1 2 3 4 5]))]  ; Max 3 concurrent branches
      (m/? (expensive-computation item)))))     ; Process up to 3 in parallel

;; Preemptive forking with m/?<
(def preemptive-sampling
  (m/ap
    (let [latest-price (m/?< price-signal)      ; Always get latest
          volume (m/?> volume-stream)]          ; Sequential volume
      (calculate-value latest-price volume))))  ; Use latest price for each volume

;; Switch behavior - restart on new values
(def search-as-you-type
  (m/ap
    (let [query (m/?< search-input)]
      (m/? (api-search query)))))              ; Restart search on new input
```

**Innovation**: Different fork operators (sequential, concurrent, preemptive) enable precise control over execution semantics in reactive programs.

### 3. **Backpressure with Semantic Aggregation**
**Location**: `src/missionary/core.cljc`
**Key Functions**: `m/relieve`, `m/buffer`

Handle backpressure by aggregating values using semigroup operations:

```clojure
;; Relieve backpressure with semigroup aggregation
(def high-frequency-events
  (m/seed (range 1000000)))

(def manageable-stream
  (m/relieve +                    ; Use + as semigroup for aggregation
             high-frequency-events)) ; Aggregate numbers when downstream slow

;; Custom semigroup for complex data
(defn merge-events [event1 event2]
  {:timestamp (max (:timestamp event1) (:timestamp event2))
   :count (+ (:count event1) (:count event2))
   :data (merge (:data event1) (:data event2))})

(def aggregated-events
  (m/relieve merge-events event-stream))

;; Buffer with capacity
(def buffered-stream
  (m/buffer 100 high-frequency-events))  ; Buffer up to 100 items

;; Example: Mouse movement with position aggregation
(def smooth-mouse-movement
  (m/relieve (fn [pos1 pos2] pos2)        ; Keep latest position
             raw-mouse-events))
```

**Innovation**: Backpressure handling through semantic aggregation preserves meaningful data while managing flow control, rather than just dropping values.

### 4. **Referentially Transparent Effect System**
**Location**: `src/missionary/core.cljc`
**Key Macros**: `m/sp`, `m/?`, sequential composition

Effects are values that can be composed and reasoned about:

```clojure
;; Effects as first-class values
(defn fetch-user [id]
  (m/sp
    (let [response (m/? (http-get (str "/users/" id)))
          user-data (m/? (parse-json response))]
      (if (:active user-data)
        user-data
        (m/? (fetch-fallback-user))))))

;; Compose effects
(defn fetch-user-with-posts [user-id]
  (m/sp
    (let [user (m/? (fetch-user user-id))
          posts (m/? (fetch-posts user-id))]
      (assoc user :posts posts))))

;; Effects can be stored, passed around, composed
(def user-loading-effect (fetch-user-with-posts 123))

;; Run effect multiple times
(m/reduce conj [] (m/enumerate [user-loading-effect
                                 user-loading-effect
                                 user-loading-effect]))

;; Effects with resource management
(defn with-database [f]
  (m/sp
    (let [conn (m/? (open-connection))]
      (try
        (m/? (f conn))
        (finally
          (m/? (close-connection conn)))))))
```

**Innovation**: Effects are pure values that can be composed, stored, and reasoned about before execution, providing true referential transparency.

### 5. **Publisher System for Lazy Shareable Computations**
**Location**: `src/missionary/core.cljc`
**Key Functions**: `m/memo`, `m/stream`, `m/signal`

Share expensive computations across multiple consumers with automatic lifecycle management:

```clojure
;; Memoized tasks - computed once, shared result
(def expensive-computation
  (m/memo
    (m/sp
      (println "Computing...")  ; Only printed once
      (m/? (Thread/sleep 1000))
      42)))

;; Multiple consumers get same result
(m/join expensive-computation expensive-computation)  ; => [42 42], computed once

;; Shared streams - single producer, multiple consumers
(def shared-event-stream
  (m/stream
    (m/ap
      (let [event (m/?> websocket-events)]
        (process-event event)))))

;; Multiple subscribers share the same stream
(def subscriber1 (m/reduce conj [] shared-event-stream))
(def subscriber2 (m/reduce (fn [acc x] (inc acc)) 0 shared-event-stream))

;; Shared signals - latest value semantics
(def shared-temperature
  (m/signal
    (m/cp
      (let [raw (m/? (m/watch !sensor-reading))]
        (fahrenheit->celsius raw)))))

;; All subscribers get latest temperature
(m/sample shared-temperature)  ; Current temperature for all
```

**Innovation**: Automatic subscription management starts computation on first subscriber and stops on last unsubscribe, enabling efficient resource sharing.

### 6. **Structured Error Propagation with Cancellation**
**Location**: `src/missionary/core.cljc`
**Key Functions**: `m/join`, `m/race`, cancellation propagation

Errors automatically propagate and trigger cleanup throughout the computation tree:

```clojure
;; Automatic error propagation and cleanup
(def robust-pipeline
  (m/sp
    (try
      (let [results (m/? (m/join
                           (fetch-data-a)      ; If any fails,
                           (fetch-data-b)      ; all are cancelled
                           (fetch-data-c)))]
        (process-results results))
      (catch Exception e
        (m/? (log-error e))
        (m/? (send-alert e))
        nil))))

;; Race with automatic cancellation
(def fastest-response
  (m/race
    (fetch-from-primary-server)
    (fetch-from-backup-server)
    (fetch-from-cache)))          ; Others cancelled when first completes

;; Resource cleanup with cancellation
(def with-cleanup
  (m/sp
    (let [resource (m/? (acquire-resource))]
      (try
        (m/? (use-resource resource))
        (finally
          (m/? (release-resource resource)))))))  ; Always cleaned up

;; Cancellation inhibition
(def critical-operation
  (m/sp
    (let [result (m/? (normal-operation))]
      (m/? (m/compel (critical-cleanup result))))))  ; Never cancelled
```

**Innovation**: Structured concurrency with automatic cancellation propagation ensures resources are properly cleaned up even in error scenarios.

### 7. **Incremental Maintenance of Dynamic DAGs**
**Location**: `src/missionary/impl/Latest.cljc`
**Key Functions**: `m/latest`, dynamic dependency tracking

Efficiently maintain computations over changing dependency graphs without glitches:

```clojure
;; Dynamic dependencies that change over time
(def !config (atom {:data-source :primary}))
(def !primary-data (atom 100))
(def !backup-data (atom 200))

(def dynamic-computation
  (m/cp
    (let [config (m/? (m/watch !config))]
      (case (:data-source config)
        :primary (m/? (m/watch !primary-data))
        :backup  (m/? (m/watch !backup-data))))))

;; No glitches - always consistent view
(swap! !config assoc :data-source :backup)  ; Cleanly switches data source
(swap! !primary-data inc)                    ; No effect on output
(swap! !backup-data inc)                     ; Immediately reflects in output

;; Complex dynamic DAG
(def adaptive-pipeline
  (m/cp
    (let [mode (m/? (m/watch !processing-mode))
          input (m/? input-signal)]
      (case mode
        :fast (m/? (fast-processor input))
        :accurate (m/? (accurate-processor input))
        :balanced (m/? (m/latest (fast-processor input)
                                 (accurate-processor input)))))))
```

**Innovation**: Dynamic dependency tracking with incremental maintenance prevents FRP glitches and ensures consistent state updates.

### 8. **Fiber-Based Cooperative Concurrency**
**Location**: `src/missionary/impl/Continuous.cljc`
**Key Operations**: `park`, `fork`, `switch`, `unpark`

Cooperative concurrency model with explicit parking and unparking:

```clojure
;; In missionary.impl.Continuous - fiber operations
(deftype Process [terminator notifier state]
  Object
  (park [process]
    ;; Suspend current fiber until unparked
    (set! state :parked))

  (fork [process computation]
    ;; Create new concurrent fiber
    (let [child-process (create-child process)]
      (schedule-fiber child-process computation)))

  (switch [process new-computation]
    ;; Replace current computation
    (park process)
    (start-new-computation new-computation))

  (unpark [process result]
    ;; Resume parked fiber with result
    (when (= state :parked)
      (set! state :running)
      (resume-with-result result))))

;; Usage in reactive computations
(def cooperative-processing
  (m/ap
    (doseq [item (m/?> large-dataset)]
      (m/? (process-item item))      ; Parks between items
      (when (should-yield?)
        (m/? (m/sleep 0))))))        ; Explicit yield point
```

**Innovation**: Cooperative concurrency model provides predictable execution without thread overhead, ideal for JavaScript environments.

### 9. **Latest Semantics for Continuous Values**
**Location**: `src/missionary/impl/Latest.cljc`
**Key Functions**: `m/latest`, continuous flow combination

Combine multiple continuous flows maintaining latest-value semantics:

```clojure
;; Latest combines multiple signals
(def !x (atom 10))
(def !y (atom 20))
(def !z (atom 30))

(def combined-signal
  (m/latest
    (fn [x y z] (+ x y z))
    (m/watch !x)
    (m/watch !y)
    (m/watch !z)))

;; Always produces sum of latest values
(swap! !x inc)  ; combined-signal emits 41 (11 + 20 + 30)
(swap! !y inc)  ; combined-signal emits 42 (11 + 21 + 30)

;; Works with any number of inputs
(def complex-computation
  (m/latest
    (fn [& values]
      (apply compute-metric values))
    input-1 input-2 input-3 input-4 input-5))

;; Lazy evaluation - only computes when someone listens
(def derived
  (m/cp
    (let [base (m/? combined-signal)]
      (* base base))))  ; Only recomputes when combined-signal changes
```

**Innovation**: Latest semantics ensure continuous flows always reflect current state without temporal inconsistencies common in reactive systems.

### 10. **Sampling and Cross-Domain Bridging**
**Location**: `src/missionary/core.cljc`
**Key Functions**: `m/sample`, `m/zip`, domain bridging

Bridge between discrete events and continuous values elegantly:

```clojure
;; Sample continuous signal with discrete events
(def price-updates
  (m/ap
    (let [event (m/?> trading-events)          ; Discrete events
          current-price (m/?< price-signal)]   ; Latest continuous value
      {:event event :price current-price})))

;; Zip discrete flows
(def synchronized-streams
  (m/zip
    (fn [a b c] {:a a :b b :c c})
    stream-a                    ; Wait for all three
    stream-b                    ; to have values
    stream-c))                  ; then emit tuple

;; Sample on demand
(def current-state-snapshot
  (m/sp
    (let [user-state (m/? (m/sample user-signal))
          app-state (m/? (m/sample app-signal))]
      {:user user-state :app app-state})))

;; Cross-domain reactive patterns
(def ui-updates
  (m/ap
    (let [user-action (m/?> user-interactions)    ; Discrete user events
          current-data (m/?< data-signal)         ; Latest data state
          ui-state (m/?< ui-state-signal)]        ; Latest UI state
      (update-ui user-action current-data ui-state))))
```

**Innovation**: Seamless bridging between discrete and continuous domains enables natural reactive programming patterns that mirror real-world scenarios.

### 11. **Reactive Streams Compliance with Missionary Semantics**
**Location**: `src/missionary/core.cljc`
**Key Functions**: reactive streams integration

Full compatibility with Reactive Streams while maintaining Missionary's semantics:

```clojure
;; Convert Missionary flow to Reactive Streams Publisher
(def publisher
  (missionary->publisher my-flow))

;; Convert Reactive Streams Publisher to Missionary flow
(def flow
  (publisher->missionary external-publisher))

;; Interop with Java reactive libraries
(def kafka-consumer
  (publisher->missionary
    (kafka-reactive-streams-source config)))

(def processed-events
  (m/ap
    (let [event (m/?> kafka-consumer)]
      (process-kafka-event event))))

;; Backpressure propagates correctly
(def slow-processor
  (m/ap
    (let [item (m/?> fast-producer)]
      (m/? (slow-processing item)))))  ; Backpressure to producer
```

**Innovation**: Full Reactive Streams compliance enables integration with Java ecosystem while preserving Missionary's superior semantics.

### 12. **Metaprogramming-Enabled DSL**
**Location**: `src/missionary/core.cljc`
**Key Macros**: `m/sp`, `m/ap`, `m/cp`

Full Clojure metaprogramming support within reactive contexts:

```clojure
;; Macros work inside reactive contexts
(defmacro with-retry [max-attempts & body]
  `(m/sp
     (loop [attempts# 0]
       (if (< attempts# ~max-attempts)
         (try
           ~@body
           (catch Exception e#
             (if (< attempts# (dec ~max-attempts))
               (recur (inc attempts#))
               (throw e#))))
         (throw (Exception. "Max attempts exceeded"))))))

;; Use macros in reactive code
(def reliable-fetch
  (with-retry 3
    (m/? (http-get "/api/data"))))

;; Higher-order reactive functions
(defn repeat-until [predicate task]
  (m/sp
    (loop []
      (let [result (m/? task)]
        (if (predicate result)
          result
          (recur))))))

;; Code generation for reactive patterns
(defmacro reactive-let [bindings & body]
  `(m/cp
     (let ~(vec (mapcat (fn [[sym expr]]
                          [sym `(m/? ~expr)])
                        (partition 2 bindings)))
       ~@body)))

;; Generated reactive code
(reactive-let [user (fetch-user 123)
               posts (fetch-posts (:id user))]
  (assoc user :posts posts))
```

**Innovation**: Full metaprogramming support enables building domain-specific reactive abstractions and code generation patterns.

---

## 🎯 Key Takeaways

1. **Unified Abstractions**: Single protocol unifies discrete events and continuous values
2. **Precise Concurrency Control**: Different fork operators provide exact execution semantics needed
3. **Semantic Backpressure**: Handle overflow through meaningful aggregation, not just dropping
4. **Referential Transparency**: Effects are values that can be composed and reasoned about
5. **Automatic Resource Management**: Publishers handle subscription lifecycle automatically
6. **Structured Error Handling**: Cancellation propagation ensures cleanup throughout computation trees
7. **Glitch-Free Updates**: Dynamic DAG maintenance prevents temporal inconsistencies
8. **Cooperative Concurrency**: Fiber-based model provides predictable execution
9. **Cross-Domain Bridging**: Seamless composition between discrete and continuous reactive primitives
10. **Reactive Streams Compatible**: Interoperates with existing reactive ecosystems
11. **Metaprogramming Enabled**: Full macro support for building domain-specific abstractions

Missionary represents the state-of-the-art in functional reactive programming, providing both theoretical soundness and practical performance while maintaining the expressiveness of Clojure's metaprogramming capabilities.