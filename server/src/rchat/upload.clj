(ns rchat.upload
  "Images the user attaches to a message. The browser POSTs the image as
  the request body to /upload, the file is stored content-addressed in the
  looks directory of the agent, next to the images the agent looked at, and
  the answer is its name and URL. A message then names the files, and the
  agent namespace turns them into image blocks for the model."
  (:require [clojure.java.io :as io]
            [rframes.transit :as transit]))

(def max-bytes
  (* 10 1024 1024))

(def extensions
  {"image/png" "png"
   "image/jpeg" "jpg"
   "image/webp" "webp"
   "image/gif" "gif"})

(defn- sha256-hex
  [^bytes bytes]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (take 16 digest)))))

(defn- content-type
  [request]
  (some-> (get-in request [:headers "content-type"])
          (clojure.string/split #";")
          (first)
          (clojure.string/trim)
          (clojure.string/lower-case)))

(defn store!
  "Writes `bytes` of `content-type` into `looks-dir` and returns
  `{:name :url}`. Throws for a type that is not an image."
  [looks-dir content-type ^bytes bytes]
  (let [extension (or (extensions content-type)
                      (throw (ex-info "Not an image" {:content-type content-type})))
        file-name (str (sha256-hex bytes) "." extension)
        file (io/file looks-dir file-name)]
    (io/make-parents file)
    (when-not (.isFile file)
      (with-open [out (io/output-stream file)]
        (.write out bytes)))
    {:name file-name
     :url (str "/looks/" file-name)}))

(defn- respond
  [w status value]
  (assoc w
         :ring/response
         {:status status
          :headers {"Content-Type" "application/transit+json"}
          :body (transit/write-str value)}))

(defn handler
  "The handler of POST /upload for the looks directory of a runner."
  [{:keys [looks-dir]} w]
  (let [request (:ring/request w)
        type (content-type request)
        bytes (some-> (:body request) (.readAllBytes))]
    (cond
      (not (extensions type))
      (respond w 415 {:error :not-an-image})

      (or (nil? bytes) (zero? (count bytes)))
      (respond w 400 {:error :empty})

      (< max-bytes (count bytes))
      (respond w 413 {:error :too-large})

      :else
      (respond w 200 (store! looks-dir type bytes)))))

(comment
  (store! "/tmp/looks" "image/png" (.getBytes "png"))
  )
