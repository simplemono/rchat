(ns rchat.look
  "Lets the model see images.

  A command of the agent (the `look` script of an app's container, say)
  stores an image as <agent-dir>/looks/<sha>.jpg and prints
  `[[look:<path>]]`. After a step `attach` adds image blocks that reference
  these files to the conversation: into the tool result for the Anthropic
  API, as a user message after the tool results for OpenAI-compatible APIs
  (OpenRouter), where a tool message is text only. `inline` replaces the
  references with the image data right before a request. The files are
  content-addressed and never change, so the conversation is the same from
  request to request and the log stays small."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [minisweagent.log :as log]))

(def max-images
  "Image blocks per command output."
  8)

(def ^:private marker
  #"\[\[look:([^\]\s]+)\]\]")

(defn paths
  "The image files that `text` announces, if they are in `looks-dir`."
  [looks-dir text]
  (->> (re-seq marker (str text))
       (map second)
       (filter #(and (str/starts-with? % (str looks-dir "/"))
                     (not (str/includes? % ".."))))
       (distinct)))

(defn reference
  "The image block the log keeps: a reference to the file, not its bytes."
  [path]
  {:type "image"
   :source {:type "look"
            :path path}})

(defn- with-images
  [looks-dir {:keys [content] :as block}]
  (let [image-paths (when (string? content)
                      (take max-images (paths looks-dir content)))]
    (if (seq image-paths)
      (assoc block :content (into [{:type "text"
                                    :text content}]
                                  (map reference)
                                  image-paths))
      block)))

(defn- attach-to-tool-results
  [looks-dir entry]
  (if (and (= :actions/observed (:type entry))
           (vector? (get-in entry [:message :content])))
    (update-in entry [:message :content]
               (fn [blocks]
                 (mapv #(if (= "tool_result" (:type %))
                          (with-images looks-dir %)
                          %)
                       blocks)))
    entry))

(defn- images-entry
  "The log entry with a user message that shows the images of the new
  entries, or nil if they announce none."
  [looks-dir now entries]
  (let [image-paths (->> entries
                         (filter #(= :actions/observed (:type %)))
                         (mapcat :outputs)
                         (mapcat #(take max-images (paths looks-dir (:output %))))
                         (distinct))]
    (when (seq image-paths)
      {:type :look/attached
       :at now
       :message {:role "user"
                 :content (into [{:type "text"
                                  :text "The images of your look command, in its order:"}]
                                (map reference)
                                image-paths)}})))

(defn attach
  "Adds the images that the entries of `log` from index `from` on announce.
  `api` is the API of the model, :anthropic or :openai. A handover is left
  as the last entry, so a step that submits gets no image message."
  [log from looks-dir api now]
  (if (= :anthropic api)
    (into (subvec log 0 from)
          (map #(attach-to-tool-results looks-dir %))
          (subvec log from))
    (let [entry (when-not (log/submission log)
                  (images-entry looks-dir now (subvec log from)))]
      (cond-> log
        entry (conj entry)))))

(defn- image-data
  [file]
  (.encodeToString (java.util.Base64/getEncoder)
                   (java.nio.file.Files/readAllBytes (.toPath file))))

(defn media-type
  [file]
  (case (str/lower-case (or (second (re-find #"\.([^./]+)$" (.getName file))) ""))
    "png" "image/png"
    "webp" "image/webp"
    "gif" "image/gif"
    "image/jpeg"))

(defn- image-block
  [api file]
  (if (= :anthropic api)
    {:type "image"
     :source {:type "base64"
              :media_type (media-type file)
              :data (image-data file)}}
    {:type "image_url"
     :image_url {:url (str "data:" (media-type file) ";base64," (image-data file))}}))

(defn- inline-block
  [api block]
  (cond
    (and (= "image" (:type block))
         (= "look" (get-in block [:source :type])))
    (let [file (io/file (get-in block [:source :path]))]
      (if (.isFile file)
        (image-block api file)
        {:type "text"
         :text "[image no longer available]"}))

    (vector? (:content block))
    (update block :content (fn [blocks]
                             (mapv #(inline-block api %) blocks)))

    :else
    block))

(defn inline
  "The messages as the API expects them: every image reference replaced with
  the image."
  [api messages]
  (mapv (fn [message]
          (if (vector? (:content message))
            (update message :content (fn [blocks]
                                       (mapv #(inline-block api %) blocks)))
            message))
        messages))

(defn- look-path
  [block]
  (when (= "look" (get-in block [:source :type]))
    (get-in block [:source :path])))

(defn references
  "The paths of the images a log entry shows to the model."
  [entry]
  (let [content (get-in entry [:message :content])]
    (when (vector? content)
      (vec (mapcat (fn [block]
                     (if (vector? (:content block))
                       (keep look-path (:content block))
                       (keep look-path [block])))
                   content)))))

(comment
  (paths "/work/.agent/looks" "frame.png [[look:/work/.agent/looks/ab12.jpg]]")
  )
