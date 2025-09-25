;; Configuration System for Agent Tools
;; Provides customizable behavior and persistent settings

(ns agent.configuration
  "Configuration management for agent tools.

  Features:
  - Hierarchical configuration with inheritance
  - Profile-based settings
  - Persistent configuration storage
  - Runtime configuration updates
  - Validation and defaults"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [agent.enhanced-explorer :as explorer]))

;; Configuration hierarchy: defaults < profile < runtime < overrides
(def ^:private config-hierarchy
  {:defaults {:output-format :rich-text
              :max-depth 5
              :max-items 20
              :colors-enabled true
              :interactive-mode true
              :auto-save-sessions false
              :monitoring-enabled false
              :error-handling :strict
              :logging-level :info
              :cache-enabled true
              :performance-tracking true}

   :profiles {:development {:interactive-mode true
                           :colors-enabled true
                           :auto-save-sessions true
                           :logging-level :debug
                           :max-depth 10
                           :monitoring-enabled true}

              :production {:interactive-mode false
                          :colors-enabled false
                          :auto-save-sessions true
                          :logging-level :warn
                          :cache-enabled true
                          :performance-tracking true}

              :ci {:interactive-mode false
                  :colors-enabled false
                  :auto-save-sessions false
                  :logging-level :error
                  :output-format :plain-text}

              :debug {:interactive-mode true
                     :colors-enabled true
                     :logging-level :trace
                     :max-depth 15
                     :max-items 50
                     :performance-tracking true}}

   :runtime {}
   :overrides {}})

(def ^:private current-profile (atom :development))
(def ^:private config-file ".agent-config.edn")

;; Configuration management
(defn- merge-configs
  "Merge configuration maps in hierarchy order"
  [& configs]
  (apply merge (reverse configs)))

(defn- get-effective-config
  "Get the effective configuration by merging all levels"
  []
  (let [profile-config (get-in config-hierarchy [:profiles @current-profile] {})]
    (merge-configs
     (:defaults config-hierarchy)
     profile-config
     (:runtime config-hierarchy)
     (:overrides config-hierarchy))))

(defn get-config
  "Get configuration value or entire config map"
  ([]
   (get-effective-config))
  ([key]
   (get (get-effective-config) key))
  ([key default]
   (get (get-effective-config) key default)))

(defn set-config!
  "Set a configuration value at runtime level"
  [key value]
  (swap! (:runtime config-hierarchy) assoc key value)
  (println (explorer/ansi-color :green (str "⚙️  Config updated: " key " = " value))))

(defn set-profile!
  "Switch to a different configuration profile"
  [profile]
  (if (contains? (:profiles config-hierarchy) profile)
    (do
      (reset! current-profile profile)
      (println (explorer/ansi-color :green (str "👤 Switched to profile: " profile)))
      (println (explorer/format-key-value "Active Config" (get-effective-config))))
    (do
      (println (explorer/ansi-color :red (str "❌ Unknown profile: " profile)))
      (println (explorer/ansi-color :yellow "Available profiles:"))
      (println (explorer/format-list (keys (:profiles config-hierarchy)) :bullet true)))))

(defn override-config!
  "Set an override that takes precedence over all other configs"
  [key value]
  (swap! (:overrides config-hierarchy) assoc key value)
  (println (explorer/ansi-color :yellow (str "🔧 Override set: " key " = " value))))

(defn reset-config!
  "Reset configuration to defaults for a level"
  ([]
   (reset! (:runtime config-hierarchy) {})
   (reset! (:overrides config-hierarchy) {})
   (println (explorer/ansi-color :green "🔄 Configuration reset to defaults")))
  ([level]
   (case level
     :runtime (do (reset! (:runtime config-hierarchy) {})
                  (println (explorer/ansi-color :green "🔄 Runtime config reset")))
     :overrides (do (reset! (:overrides config-hierarchy) {})
                    (println (explorer/ansi-color :green "🔄 Overrides reset")))
     :profile (do (reset! current-profile :development)
                  (println (explorer/ansi-color :green "🔄 Switched to default profile")))
     (println (explorer/ansi-color :red (str "❌ Unknown config level: " level))))))

;; Persistent configuration
(defn save-config
  "Save current configuration to file"
  []
  (try
    (let [config-to-save {:profile @current-profile
                          :runtime @(:runtime config-hierarchy)
                          :overrides @(:overrides config-hierarchy)}]
      (spit config-file (pr-str config-to-save))
      (println (explorer/ansi-color :green (str "💾 Configuration saved to " config-file))))
    (catch Exception e
      (println (explorer/ansi-color :red (str "❌ Failed to save config: " (.getMessage e)))))))

(defn load-config
  "Load configuration from file"
  []
  (try
    (when (.exists (io/file config-file))
      (let [saved-config (edn/read-string (slurp config-file))]
        (when (:profile saved-config)
          (reset! current-profile (:profile saved-config)))
        (when (:runtime saved-config)
          (reset! (:runtime config-hierarchy) (:runtime saved-config)))
        (when (:overrides saved-config)
          (reset! (:overrides config-hierarchy) (:overrides saved-config)))
        (println (explorer/ansi-color :green (str "📂 Configuration loaded from " config-file)))))
    (catch Exception e
      (println (explorer/ansi-color :red (str "❌ Failed to load config: " (.getMessage e)))))))

;; Configuration validation
(defn- validate-config-value
  "Validate a configuration value"
  [key value]
  (let [validators {:output-format #{:rich-text :plain-text :json}
                    :max-depth #(and (integer? %) (> % 0) (<= % 20))
                    :max-items #(and (integer? %) (> % 0) (<= % 100))
                    :colors-enabled boolean?
                    :interactive-mode boolean?
                    :auto-save-sessions boolean?
                    :monitoring-enabled boolean?
                    :error-handling #{:strict :lenient :silent}
                    :logging-level #{:trace :debug :info :warn :error}
                    :cache-enabled boolean?
                    :performance-tracking boolean?}]
    (if-let [validator (validators key)]
      (if (fn? validator)
        (validator value)
        (contains? validator value))
      true))) ; Unknown keys are allowed

(defn validate-config
  "Validate current configuration"
  []
  (let [config (get-effective-config)
        issues (atom [])]
    (doseq [[key value] config]
      (when-not (validate-config-value key value)
        (swap! issues conj {:key key :value value :issue :invalid-value})))

    (if (seq @issues)
      (do
        (println (explorer/format-header "❌ CONFIGURATION ISSUES" :level 2 :color :red))
        (doseq [issue @issues]
          (println (str "• " (:key issue) " = " (:value issue) " (" (:issue issue) ")")))
        false)
      (do
        (println (explorer/ansi-color :green "✅ Configuration is valid"))
        true))))

;; Configuration profiles management
(defn create-profile
  "Create a new configuration profile"
  [name config]
  (if (validate-config-value name config) ; Basic validation
    (do
      (swap! (:profiles config-hierarchy) assoc name config)
      (println (explorer/ansi-color :green (str "📝 Profile '" name "' created"))))
    (println (explorer/ansi-color :red (str "❌ Invalid profile configuration for '" name "'")))))

(defn delete-profile
  "Delete a configuration profile"
  [name]
  (if (= name @current-profile)
    (println (explorer/ansi-color :red "❌ Cannot delete active profile"))
    (if (contains? (:profiles config-hierarchy) name)
      (do
        (swap! (:profiles config-hierarchy) dissoc name)
        (println (explorer/ansi-color :green (str "🗑️  Profile '" name "' deleted"))))
      (println (explorer/ansi-color :red (str "❌ Profile '" name "' not found"))))))

(defn list-profiles
  "List all available profiles"
  []
  (println (explorer/format-header "👥 CONFIGURATION PROFILES" :level 1 :color :cyan))
  (doseq [[name config] (:profiles config-hierarchy)]
    (let [active? (= name @current-profile)]
      (println (str (if active? (explorer/ansi-color :green "▶️  ") "   ") name))
      (when active?
        (println (explorer/ansi-color :dim "   (active)")))
      (println (explorer/format-key-value "Settings" (count config) :indent 3))))
  (println))

;; Configuration introspection
(defn show-config
  "Display current configuration with sources"
  []
  (println (explorer/format-header "⚙️  CURRENT CONFIGURATION" :level 1 :color :cyan))

  (println (explorer/format-header "👤 ACTIVE PROFILE" :level 2 :color :yellow))
  (println (str "Profile: " @current-profile))

  (let [effective (get-effective-config)
        defaults (:defaults config-hierarchy)
        profile (get-in config-hierarchy [:profiles @current-profile] {})
        runtime @(:runtime config-hierarchy)
        overrides @(:overrides config-hierarchy)]

    (println "\n" (explorer/format-header "📋 CONFIGURATION SOURCES" :level 2 :color :blue))
    (println (explorer/format-key-value "Defaults" (count defaults)))
    (println (explorer/format-key-value "Profile" (count profile)))
    (println (explorer/format-key-value "Runtime" (count runtime)))
    (println (explorer/format-key-value "Overrides" (count overrides)))

    (println "\n" (explorer/format-header "🔧 EFFECTIVE CONFIGURATION" :level 2 :color :green))
    (doseq [[key value] (sort-by first effective)]
      (let [source (cond
                     (contains? overrides key) :override
                     (contains? runtime key) :runtime
                     (contains? profile key) :profile
                     :else :default)]
        (println (str (explorer/format-key-value key value)
                      " "
                      (explorer/ansi-color :dim (str "(" (name source) ")"))))))))

(defn config-help
  "Show configuration help"
  []
  (println (explorer/format-header "🆘 CONFIGURATION HELP" :level 1 :color :cyan))

  (println "📖 BASIC USAGE:")
  (println "• (get-config) - Show current configuration")
  (println "• (get-config :key) - Get specific setting")
  (println "• (set-config! :key value) - Set runtime config")
  (println "• (set-profile! :profile-name) - Switch profiles")

  (println "\n👥 PROFILES:")
  (println "• :development - Interactive, full features")
  (println "• :production - Optimized for production")
  (println "• :ci - Minimal output for CI/CD")
  (println "• :debug - Maximum debugging info")

  (println "\n💾 PERSISTENCE:")
  (println "• (save-config) - Save current config")
  (println "• (load-config) - Load saved config")

  (println "\n🔧 ADVANCED:")
  (println "• (override-config! :key value) - Force override")
  (println "• (reset-config!) - Reset to defaults")
  (println "• (create-profile :name {...}) - Create custom profile"))

;; Initialize configuration on load
(load-config)

;; Export main functions
(def current get-config)
(def set! set-config!)
(def profile! set-profile!)
(def save! save-config)
(def load! load-config)
(def show show-config)
(def help config-help)