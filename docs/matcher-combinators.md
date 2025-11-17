# matcher-combinators: A Clojure Testing Library

## Overview

**matcher-combinators** is a Clojure library for asserting the structure of nested data during testing. Created by Nubank's Devflow team, it provides composable matchers that make validating complex data structures more readable and flexible than traditional equality assertions.

## Core Purpose

The library addresses a testing gap in Clojure: while the language excels at creating and manipulating data structures, it lacks a comprehensive way to assert that results match expected patterns. matcher-combinators fills this by offering matcher combinators as "building blocks to test functions that evaluate to nested data-structures."

## Key Features

- **Structural Matchers**: Validates scalar values and nested collections with sensible defaults
- **Readable Assertions**: Data structures are interpreted intuitively (maps as partial matches, sequences as ordered)
- **Detailed Diffs**: Provides pretty-printed output showing exactly where mismatches occur
- **Framework Integration**: Works seamlessly with `clojure.test`, `midje`, and standalone contexts
- **Extensibility**: Implement the `Matcher` protocol to create custom matchers

## Essential Matchers

| Matcher | Purpose |
|---------|---------|
| `equals` | Exact value matching across scalars and collections |
| `embeds` | Partial/subset matching (default for maps) |
| `prefix` | Matches ordered sequence prefixes |
| `in-any-order` | Order-agnostic sequence matching |
| `via` | Transforms actual values before matching |
| `regex` | Pattern matching on strings |
| `set-equals` / `set-embeds` | Set-specific matchers allowing duplicates |

## Testing Patterns

### Basic Example (clojure.test)

```clojure
(is (match? {:name/first "Alfredo"}
            {:name/first "Alfredo"
             :name/last "da Rocha Viana"}))
```

Maps default to partial matching, ignoring extra keys—ideal for API response validation where you only care about specific fields.

### Nested Structures

```clojure
(is (match? {:band/members [{:name/first "Alfredo"}
                            {:name/first "Benedito"}]}
            {:band/members [...]}))
```

Nested structures inherit matcher rules recursively, enabling elegant deep validation.

### Transformed Values

The `via` matcher applies preprocessing:

```clojure
(is (match? {:payloads [(m/via read-string {:foo :bar})]}
            {:payloads ["{:foo :bar :baz :qux}"]}))
```

This eliminates manual data transformation in test setup.

## Default Matcher Behavior

- **Scalars, sequences, sets**: Default to `equals` (exact match)
- **Maps**: Default to `embeds` (partial match)
- **Regex patterns**: Matched against strings using `re-find`

## Overriding Defaults

For stricter validation, wrap data in explicit matchers:

```clojure
;; Exact map matching
(is (match? (m/nested-equals {:a {:b {:c odd?}}})
            {:a {:b {:c 1}}}))
```

## Advantages Over Standard Assertions

Rather than writing assertions like `(is (= (+ 29 8) 37))`, matcher-combinators lets you express intent: "The result should have these properties, ignoring irrelevant details." This approach:

- Reduces brittle tests tied to exact output
- Makes test failures clearer through structured diffs
- Enables reusable matcher compositions
- Supports predicates within data structures

## Community & Support

The project is maintained by Nubank's Devflow team and Phillip Mates. Discussion happens in the Clojurians Slack channel `#matcher-combinators`. Documentation is available on cljdoc, and the library supports Clojure 1.8+.
