(ns scripts.utils.http
  "HTTP client utilities for Clojure/Babashka scripts with mocking support."
  (:require [babashka.http-client :as http]
            [scripts.utils.json :as json]))

(def ^:dynamic *mock-responses*
  "Map of URL patterns to mock responses {:status int :body string}."
  (atom {}))

(defn enable-mocking!
  "Enable HTTP mocking. Pass map of patterns to responses."
  [mock-map]
  (reset! *mock-responses* mock-map))

(defn disable-mocking!
  "Disable HTTP mocking."
  []
  (reset! *mock-responses* {}))

(defn- find-mock
  "Find mock response for URL."
  [url]
  (some (fn [[pattern response]]
          (when (cond
                  (string? pattern) (= pattern url)
                  (instance? java.util.regex.Pattern pattern) (re-find pattern url)
                  :else false)
            response))
        @*mock-responses*))

(defn request
  "Make HTTP request. Options: :method, :headers, :body, :timeout."
  [{:keys [url method headers body timeout] :or {method :get timeout 120000}}]
  (if-let [mock (find-mock url)]
    (do
      (println "[MOCK]" method url)
      mock)
    (try
      (let [opts (cond-> {:method method :headers headers :timeout timeout :uri url :throw false}
                   body (assoc :body (if (map? body) (json/generate-json body) body)))]
        (http/request opts))
      (catch Exception e
        {:status 500 :error (.getMessage e) :exception e}))))

(defn post-json
  "POST JSON data to URL. Returns parsed response."
  [url body & [{:keys [headers timeout]}]]
  (let [response (request {:url url
                           :method :post
                           :headers (merge {"Content-Type" "application/json"} headers)
                           :body body
                           :timeout timeout})]
    (cond-> response
      (and (= 200 (:status response)) (string? (:body response)))
      (update :body json/parse-json))))

(defn get-json
  "GET JSON from URL. Returns parsed response."
  [url & [{:keys [headers timeout]}]]
  (let [response (request {:url url :method :get :headers headers :timeout timeout})]
    (cond-> response
      (and (= 200 (:status response)) (string? (:body response)))
      (update :body json/parse-json))))
