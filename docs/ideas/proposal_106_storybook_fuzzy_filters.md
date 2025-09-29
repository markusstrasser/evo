# Proposal 106 · Fuzzy Storybook Filters

## Problem
Even once we build a kernel storybook (Proposal 93), finding the right example will require manual scrolling; there’s no structured search or keyboard navigation.

## Inspiration
- **Zed’s `ComponentPreview`** maintains a search box, cursor index, and filtered entries (vectors of `PreviewEntry`) to provide instant fuzzy filtering (`/Users/alien/Projects/inspo-clones/zed/crates/zed/component_preview.rs:130-360`). Uniform list scroll handles keep the highlighted entry in view.

## Proposal
Port the preview filtering model to our storybook:

### Before
```clojure
(defn render-catalog [catalog]
  (for [{:keys [title examples]} catalog]
    ...)) ; manual scan only
```

### After
```clojure
(defrecord StorybookState [filter-text cursor-index entries])

(defn filtered-examples [state]
  (let [term (str/lower (:filter-text state))]
    (filter #(str/includes? (str/lower (:title %)) term)
            (:entries state))))

(defn handle-key [state key]
  (case key
    :down (update state :cursor-index inc)
    :up   (update state :cursor-index dec)
    state))
```

Add `storybook.persistence/save-filter!` (Proposal 102) so filter strings persist per workspace.

## Payoff
- **Faster discovery**: type “reference” and jump straight to trace-related examples.
- **Keyboard-first UX**: mirror Zed’s arrow navigation to scan proposals quickly.
- **LLM integration**: agents can set filter text programmatically to surface context for responses.

## Considerations
- Keep state in pure atoms or explicit context objects to avoid introducing async loops.
- Reuse existing `dev.persistence` (Proposal 102) for storage to avoid duplication.
