Other ideas: While the VS Code "Contribution Model" is excellent, Neovim and Bevy offer two powerful, alternative philosophies that are highly relevant to your goals: API-first (Neovim) and Data-first/ECS (Bevy).


## VS CODE model


A component sends events by rendering data that describes the event; it never directly calls a dispatcher. This preserves the unidirectional data flow. A plugin is a package that *contributes* components, commands, and logic to the host system, following a pattern perfected by editors like VS Code.

-----

### \#\# How Components Send Events ⚡️

A component never gets access to the `dispatch!` function. Instead, its `:render` function produces **declarative event descriptions** within the hiccup structure. The rendering engine and a single global event handler are responsible for turning a DOM event into a command.

This reuses the exact same command bus and registry we've already designed.

**The Flow:**

1.  **A component's `:render` function** returns hiccup with an `on-*` handler. The value of this handler is a vector that matches our command format: `[:command-id {:params ...}]`.

    ```clojure
    ;; A button component's render function
    (fn [props]
      [:button {:class [...]
                :on-click [:delete-node {:node-id (:id props)}]} ;; <--- Data, not a function
       "Delete Me"])
    ```

2.  **The main event listener** (which you've already configured in `core.cljs`) catches the DOM click.

3.  **It reads the event description vector** `[:delete-node {:node-id "..."}]` from the DOM element's attributes.

4.  **It passes this vector to the single, global `dispatch!` function.**

That's it. The component remains pure. It doesn't know what `dispatch!` is; it only knows how to describe what *should* happen when an interaction occurs. This maintains a clean boundary and ensures that all state changes go through the same centralized, transactional pipeline, whether they originate from a keyboard shortcut, the LLM agent, or a UI interaction.

-----

### \#\# The Best Idea from VS Code: The Contribution Model 📦

The single most important idea to steal from VS Code or Vite's plugin architecture is the **Contribution Model**.

A VS Code plugin is not a script that runs and monkey-patches the editor. It's primarily a manifest (`package.json`) that **declaratively contributes data to well-defined "contribution points"**.

For example, a VS Code plugin contributes:

* A list of commands to the `contributes.commands` point.
* A list of keybindings to the `contributes.keybindings` point.
* A data structure describing a UI view to the `contributes.views` point.

The editor reads this data on startup and integrates the plugin's functionality. The plugin only activates and runs code when one of its contributed commands is invoked.

**Your system is already perfectly designed for this.**

* Your `registry.cljc` is your `commands` and `keybindings` contribution point.
* Your `component-map` is your `views` contribution point.
* Your `intents` map is your "core logic" contribution point.

A plugin in your system should be a single, declarative map that the host can merge at startup.

-----

### \#\# Is a component a plugin? (The Relationship) 🏛

No. A component is a brick; a plugin is a packaged kit containing bricks, instructions, and wiring.

* **A Component** is a single, well-defined unit of UI. In our system, it's a map containing a `:render` function and an optional `:query` function. It is a **view**.
* **A Plugin** is a package of functionality that extends the application. It is a **collection of contributions**.

A single plugin can contribute multiple things to the host system. It's a map that mirrors the structure of your core application logic.

**Example: A "Spellcheck" Plugin Manifest**

```clojure
(def spellcheck-plugin
  {:id "com.user.spellcheck"
   :version "0.1.0"

   :contributions
   {:components
    {:spellcheck/panel
     {:query (fn [db] (select-keys db [:spelling-errors]))
      :render (fn [errors] [:div "Found " (count errors) " errors."])}}

    :intents
    {:spellcheck/run-check
     (fn [db params]
       ;; Pure function that finds errors and returns a transaction to update state
       (let [errors (find-spelling-errors db (:node-id params))]
         [{:op :patch, :node-id "root", :updates {:spelling-errors errors}}]))}

    :commands
    {:spellcheck/run
     {:id :spellcheck/run
      :doc "Run spellchecker on the selected node."
      :handler (fn [store params] (dispatch-intent! store :spellcheck/run-check params))
      :hotkey {:key "F7"}}}}})
```

Your application's startup process would simply be `(load-plugin! spellcheck-plugin)`, which would intelligently merge the contributed components, intents, and commands into your main `component-map`, `intents` map, and `registry`. This model is incredibly robust, easy for an LLM to reason about, and keeps the core application cleanly separated from extensions.