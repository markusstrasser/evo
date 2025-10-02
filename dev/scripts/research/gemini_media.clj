(ns scripts.research.gemini-media
  (:require [scripts.utils.files :as files]
            [scripts.utils.shell :as shell]
            [scripts.utils.http :as http]
            [scripts.utils.json :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; API Configuration
(def base-url "https://generativelanguage.googleapis.com/v1beta")
(def upload-url "https://generativelanguage.googleapis.com/upload/v1beta")

;; Defaults
(def defaults
  {:model-video "veo-3.0-generate-001"
   :model-image "imagen-4.0-generate-001"
   :model-analyze "gemini-2.5-flash"
   :aspect-ratio "16:9"
   :resolution "720p"
   :num-images 4
   :output-dir "./gemini-output"
   :poll-interval 10})

;; Logging
(defn log [level msg]
  (let [colors {:info "\033[0;34m" ; Blue
                :success "\033[0;32m" ; Green
                :error "\033[0;31m" ; Red
                :warning "\033[1;33m" ; Yellow
                :reset "\033[0m"}
        symbols {:info "ℹ" :success "✓" :error "✗" :warning "⚠"}]
    (binding [*out* *err*]
      (println (str (colors level) (symbols level) (colors :reset) " " msg)))))

(defn check-api-key []
  (when (str/blank? (System/getenv "GEMINI_API_KEY"))
    (log :error "GEMINI_API_KEY not set")
    (log :info "Get your API key at: https://aistudio.google.com/apikey")
    (System/exit 1)))

;; File Upload (resumable upload protocol)
(defn upload-file [file-path mime-type]
  (log :info (str "Uploading file: " (fs/file-name file-path)))

  (let [api-key (System/getenv "GEMINI_API_KEY")
        file-size (files/file-size file-path)
        display-name (fs/file-name file-path)]

    ;; Step 1: Start resumable upload
    (let [start-result (http/request
                        {:url (str upload-url "/files")
                         :method :post
                         :headers {"x-goog-api-key" api-key
                                   "X-Goog-Upload-Protocol" "resumable"
                                   "X-Goog-Upload-Command" "start"
                                   "X-Goog-Upload-Header-Content-Length" (str file-size)
                                   "X-Goog-Upload-Header-Content-Type" mime-type
                                   "Content-Type" "application/json"}
                         :body (json/generate-json {:file {:display_name display-name}})})
          upload-url-header (get-in start-result [:headers "x-goog-upload-url"])]

      (when-not upload-url-header
        (log :error "Failed to get upload URL")
        (throw (ex-info "Upload failed" {:response start-result})))

      ;; Step 2: Upload file content
      (let [file-content (files/read-file file-path)
            upload-result (http/request
                           {:url upload-url-header
                            :method :post
                            :headers {"Content-Length" (str file-size)
                                      "X-Goog-Upload-Offset" "0"
                                      "X-Goog-Upload-Command" "upload, finalize"}
                            :body file-content})
            response-body (json/parse-json (:body upload-result))
            file-uri (get-in response-body [:file :uri])]

        (when-not file-uri
          (log :error (str "Upload failed: " (get-in response-body [:error :message] "Unknown error")))
          (throw (ex-info "Upload failed" {:response response-body})))

        (log :success (str "Uploaded: " file-uri))

        ;; Step 3: Poll until ACTIVE
        (log :info "Waiting for file to become ACTIVE...")
        (let [file-name (str/replace file-uri (str base-url "/") "")]
          (loop []
            (let [status-result (http/request
                                 {:url (str base-url "/" file-name)
                                  :method :get
                                  :headers {"x-goog-api-key" api-key}})
                  status-body (json/parse-json (:body status-result))
                  state (:state status-body)]

              (cond
                (= state "ACTIVE")
                (do
                  (log :success "File is ready")
                  file-uri)

                (= state "FAILED")
                (do
                  (log :error "File processing failed")
                  (throw (ex-info "File processing failed" {:response status-body})))

                :else
                (do
                  (print "." *err*)
                  (flush)
                  (Thread/sleep 5000)
                  (recur))))))))))

;; Poll long-running operation
(defn poll-operation [operation-name]
  (log :info "Polling operation status (this may take 1-2 minutes)...")

  (let [api-key (System/getenv "GEMINI_API_KEY")]
    (loop []
      (let [result (http/request
                    {:url (str base-url "/" operation-name)
                     :method :get
                     :headers {"x-goog-api-key" api-key}})
            body (json/parse-json (:body result))
            done? (:done body)]

        (if done?
          (if-let [error (:error body)]
            (do
              (log :error (str "Operation failed: " (:message error)))
              (throw (ex-info "Operation failed" {:error error})))
            (do
              (log :success "Operation complete")
              body))
          (do
            (print "." *err*)
            (flush)
            (Thread/sleep (* 1000 (:poll-interval defaults)))
            (recur)))))))

;; Download file
(defn download-file [uri output-path]
  (log :info (str "Downloading to: " output-path))

  (let [api-key (System/getenv "GEMINI_API_KEY")
        result (http/request
                {:url uri
                 :method :get
                 :headers {"x-goog-api-key" api-key}
                 :as :bytes})]

    (files/mkdir-p (fs/parent output-path))
    (files/write-file output-path (:body result))

    (if (and (files/exists? output-path) (> (files/file-size output-path) 0))
      (let [size-bytes (files/file-size output-path)
            size-str (cond
                       (> size-bytes (* 1024 1024)) (str (quot size-bytes (* 1024 1024)) "M")
                       (> size-bytes 1024) (str (quot size-bytes 1024) "K")
                       :else (str size-bytes "B"))]
        (log :success (str "Downloaded: " output-path " (" size-str ")")))
      (do
        (log :error "Download failed or file is empty")
        (throw (ex-info "Download failed" {:path output-path}))))))

;; Generate video
(defn generate-video [{:keys [prompt negative-prompt aspect-ratio resolution output-file]}]
  (check-api-key)

  (let [out-file (or output-file
                     (str (:output-dir defaults) "/video-"
                          (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                                   (java.time.LocalDateTime/now))
                          ".mp4"))]

    (files/mkdir-p (:output-dir defaults))

    (log :info "Generating video with Veo 3...")
    (log :info (str "Prompt: " prompt))
    (log :info (str "Aspect ratio: " aspect-ratio ", Resolution: " resolution))

    ;; Build request
    (let [params (cond-> {:aspectRatio aspect-ratio
                          :resolution resolution}
                   negative-prompt (assoc :negativePrompt negative-prompt))
          request-body {:instances [{:prompt prompt}]
                        :parameters params}
          api-key (System/getenv "GEMINI_API_KEY")

          ;; Submit generation request
          response (http/request
                    {:url (str base-url "/models/" (:model-video defaults) ":predictLongRunning")
                     :method :post
                     :headers {"x-goog-api-key" api-key
                               "Content-Type" "application/json"}
                     :body (json/generate-json request-body)})
          body (json/parse-json (:body response))]

      (when-let [error (:error body)]
        (log :error (str "Generation failed: " (:message error)))
        (throw (ex-info "Generation failed" {:error error})))

      (let [operation-name (:name body)]
        (when-not operation-name
          (log :error "No operation name returned")
          (throw (ex-info "No operation name" {:response body})))

        (log :info (str "Operation: " operation-name))

        ;; Poll for completion
        (let [result (poll-operation operation-name)
              video-uri (or (get-in result [:response :generateVideoResponse :generatedSamples 0 :video :uri])
                            (get-in result [:response :predictions 0 :videoUri])
                            (get-in result [:response :predictions 0 :uri]))]

          (when-not video-uri
            (log :error "No video URI in response")
            (throw (ex-info "No video URI" {:response result})))

          (log :success (str "Video generated: " video-uri))

          ;; Download video
          (download-file video-uri out-file)
          out-file)))))

;; Analyze video
(defn analyze-video [{:keys [video-file question output-file]}]
  (check-api-key)

  (when-not (files/exists? video-file)
    (log :error (str "Video file not found: " video-file))
    (System/exit 1))

  (log :info (str "Analyzing video: " (fs/file-name video-file)))

  ;; Detect MIME type
  (let [ext (fs/extension video-file)
        mime-type (case ext
                    ".mp4" "video/mp4"
                    ".mpeg" "video/mpeg"
                    ".mpg" "video/mpeg"
                    ".mov" "video/mov"
                    ".avi" "video/x-msvideo"
                    ".webm" "video/webm"
                    (do
                      (log :warning "Unknown video format, assuming mp4")
                      "video/mp4"))]

    ;; Upload video
    (let [file-uri (upload-file video-file mime-type)]

      (log :info (str "Analyzing with question: " question))

      ;; Build request
      (let [request-body {:contents [{:parts [{:fileData {:fileUri file-uri
                                                          :mimeType mime-type}}
                                              {:text question}]}]}
            api-key (System/getenv "GEMINI_API_KEY")

            ;; Send analysis request
            response (http/request
                      {:url (str base-url "/models/" (:model-analyze defaults) ":generateContent")
                       :method :post
                       :headers {"x-goog-api-key" api-key
                                 "Content-Type" "application/json"}
                       :body (json/generate-json request-body)})
            body (json/parse-json (:body response))
            answer (get-in body [:candidates 0 :content :parts 0 :text])]

        (when-not answer
          (log :error "No answer received")
          (throw (ex-info "No answer" {:response body})))

        (log :success "Analysis complete")

        ;; Output result
        (if output-file
          (do
            (files/write-file output-file answer)
            (log :success (str "Saved to: " output-file)))
          (do
            (println)
            (println answer)))

        answer))))

;; Generate images
(defn generate-images [{:keys [prompt num-images output-dir]}]
  (check-api-key)

  (let [out-dir (or output-dir (:output-dir defaults))
        num (or num-images (:num-images defaults))]

    (files/mkdir-p out-dir)

    (let [timestamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                             (java.time.LocalDateTime/now))]

      (log :info (str "Generating " num " image(s) with Imagen 4..."))
      (log :info (str "Prompt: " prompt))

      ;; Build request
      (let [request-body {:instances [{:prompt prompt}]
                          :parameters {:sampleCount num}}
            api-key (System/getenv "GEMINI_API_KEY")

            ;; Send generation request
            response (http/request
                      {:url (str base-url "/models/" (:model-image defaults) ":predict")
                       :method :post
                       :headers {"x-goog-api-key" api-key
                                 "Content-Type" "application/json"}
                       :body (json/generate-json request-body)})
            body (json/parse-json (:body response))]

        (when-let [error (:error body)]
          (log :error (str "Generation failed: " (:message error)))
          (throw (ex-info "Generation failed" {:error error})))

        (let [predictions (:predictions body)
              num-predictions (count predictions)]

          (when (zero? num-predictions)
            (log :error "No images returned")
            (throw (ex-info "No images returned" {:response body})))

          (log :success (str "Generated " num-predictions " image(s)"))

          ;; Save images
          (doall
           (for [i (range num-predictions)]
             (let [prediction (nth predictions i)
                   image-bytes (or (:imageBytes (:image prediction))
                                   (:bytesBase64Encoded prediction))]

               (if image-bytes
                 (let [output-file (str out-dir "/imagen-" timestamp "-" i ".png")
                       decoded (.decode (java.util.Base64/getDecoder) image-bytes)]
                   (files/write-file output-file decoded)
                   (log :success (str "Saved: " output-file " (" (files/file-size output-file) " bytes)"))
                   output-file)
                 (do
                   (log :warning (str "No image data for prediction " i))
                   nil))))))))))

;; Help
(defn show-help []
  (println "Gemini Media API - Video and Image Generation Tool")
  (println)
  (println "Usage:")
  (println "  gemini-media video generate -p <prompt> [options]")
  (println "  gemini-media video analyze <file> -p <question> [options]")
  (println "  gemini-media image generate -p <prompt> [options]")
  (println)
  (println "Commands:")
  (println "  video generate    Generate video using Veo 3")
  (println "  video analyze     Analyze video and answer questions")
  (println "  image generate    Generate images using Imagen 3")
  (println)
  (println "Video Generation Options:")
  (println "  -p, --prompt <text>           Video description (required)")
  (println "  -n, --negative <text>         Negative prompt")
  (println "  -a, --aspect-ratio <ratio>    Aspect ratio: 16:9 or 9:16 (default: 16:9)")
  (println "  -r, --resolution <res>        Resolution: 720p or 1080p (default: 720p)")
  (println "  -o, --output <file>           Output file path (default: ./gemini-output/video-*.mp4)")
  (println)
  (println "Video Analysis Options:")
  (println "  <file>                        Video file to analyze")
  (println "  -p, --prompt <question>       Question about the video (required)")
  (println "  -o, --output <file>           Output file for response (optional)")
  (println)
  (println "Image Generation Options:")
  (println "  -p, --prompt <text>           Image description (required)")
  (println "  -n, --num-images <count>      Number of images: 1-4 (default: 4)")
  (println "  -o, --output <dir>            Output directory (default: ./gemini-output)")
  (println)
  (println "Global Options:")
  (println "  -h, --help                    Show this help")
  (println)
  (println "Environment Variables:")
  (println "  GEMINI_API_KEY                API key (required)")
  (println)
  (println "Examples:")
  (println "  # Generate a video")
  (println "  gemini-media video generate -p \"A cat playing piano\" -r 1080p")
  (println)
  (println "  # Analyze a video")
  (println "  gemini-media video analyze my-video.mp4 -p \"What is happening in this video?\"")
  (println)
  (println "  # Generate images")
  (println "  gemini-media image generate -p \"Robot holding a red skateboard\" -n 4")
  (println)
  (println "  # Generate with custom output")
  (println "  gemini-media video generate -p \"Sunset over ocean\" -o sunset.mp4"))

;; Argument parsing
(defn parse-args [args]
  (loop [remaining args
         result {}]
    (if (empty? remaining)
      result
      (let [[flag value & rest] remaining]
        (case flag
          ("-p" "--prompt") (recur rest (assoc result :prompt value))
          ("-n" "--negative") (recur rest (assoc result :negative-prompt value))
          ("-a" "--aspect-ratio") (recur rest (assoc result :aspect-ratio value))
          ("-r" "--resolution") (recur rest (assoc result :resolution value))
          ("-o" "--output") (recur rest (assoc result :output value))
          ("--num-images") (recur rest (assoc result :num-images (parse-long value)))
          ("-h" "--help") (recur rest (assoc result :help true))
          ;; Non-flag argument (file path)
          (if (str/starts-with? flag "-")
            (do
              (log :error (str "Unknown option: " flag))
              (System/exit 1))
            (recur (cons value rest) (assoc result :file flag))))))))

;; Main
(defn -main [& args]
  (when (empty? args)
    (show-help)
    (System/exit 1))

  (let [[command subcommand & rest-args] args]
    (case command
      "video"
      (case subcommand
        "generate"
        (let [parsed (parse-args rest-args)]
          (when (:help parsed)
            (show-help)
            (System/exit 0))
          (when-not (:prompt parsed)
            (log :error "Prompt required (-p)")
            (System/exit 1))
          (generate-video
           {:prompt (:prompt parsed)
            :negative-prompt (:negative-prompt parsed)
            :aspect-ratio (or (:aspect-ratio parsed) (:aspect-ratio defaults))
            :resolution (or (:resolution parsed) (:resolution defaults))
            :output-file (:output parsed)}))

        "analyze"
        (let [parsed (parse-args rest-args)]
          (when (:help parsed)
            (show-help)
            (System/exit 0))
          (when-not (:file parsed)
            (log :error "Video file required")
            (System/exit 1))
          (when-not (:prompt parsed)
            (log :error "Question required (-p)")
            (System/exit 1))
          (analyze-video
           {:video-file (:file parsed)
            :question (:prompt parsed)
            :output-file (:output parsed)}))

        (do
          (log :error (str "Unknown video subcommand: " subcommand))
          (show-help)
          (System/exit 1)))

      "image"
      (case subcommand
        "generate"
        (let [parsed (parse-args rest-args)]
          (when (:help parsed)
            (show-help)
            (System/exit 0))
          (when-not (:prompt parsed)
            (log :error "Prompt required (-p)")
            (System/exit 1))
          (generate-images
           {:prompt (:prompt parsed)
            :num-images (:num-images parsed)
            :output-dir (:output parsed)}))

        (do
          (log :error (str "Unknown image subcommand: " subcommand))
          (show-help)
          (System/exit 1)))

      ("-h" "--help")
      (show-help)

      (do
        (log :error (str "Unknown command: " command))
        (show-help)
        (System/exit 1)))))
