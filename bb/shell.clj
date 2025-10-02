(ns shell
  "Shell/process utilities for Clojure/Babashka scripts with mocking support."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:dynamic *mock-commands*
  "Map of command patterns to mock responses {:exit int :out string :err string}."
  (atom {}))

(defn enable-mocking!
  "Enable command mocking. Pass map of patterns to responses."
  [mock-map]
  (reset! *mock-commands* mock-map))

(defn disable-mocking!
  "Disable command mocking."
  []
  (reset! *mock-commands* {}))

(defn- find-mock
  "Find mock response for command string."
  [cmd-str]
  (some (fn [[pattern response]]
          (when (cond
                  (string? pattern) (= pattern cmd-str)
                  (instance? java.util.regex.Pattern pattern) (re-find pattern cmd-str)
                  :else false)
            response))
        @*mock-commands*))

(defn sh
  "Run shell command. Pass vector [\"git\" \"status\"] or string \"git status\".
   Returns {:exit int :out string :err string}."
  [cmd-parts & [{:keys [dir in]}]]
  (let [cmd-str (if (string? cmd-parts) cmd-parts (str/join " " cmd-parts))]
    (if-let [mock (find-mock cmd-str)]
      (do
        (println "[MOCK]" cmd-str)
        mock)
      (try
        (let [cmd-vec (if (string? cmd-parts) (str/split cmd-parts #"\s+") cmd-parts)
              opts (cond-> {:out :string :err :string}
                     dir (assoc :dir dir)
                     in (assoc :in in))
              result @(p/process cmd-vec opts)]
          {:exit (:exit result)
           :out (:out result)
           :err (:err result)})
        (catch Exception e
          {:exit 1 :out "" :err (.getMessage e)})))))

(defn sh!
  "Like sh but throws on non-zero exit."
  [& args]
  (let [result (apply sh args)]
    (if (zero? (:exit result))
      result
      (throw (ex-info (str "Command failed: " (:err result)) result)))))
