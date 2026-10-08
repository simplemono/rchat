(ns rchat.canvas
  "The creation of the agent next to the chat.

  The agent writes an HTML page into a directory, `<cwd>/canvas` by
  convention, and the user sees `index.html` of it in an iframe beside the
  conversation. Whenever a file of the directory changes, `refresh!` puts
  a new revision into the db, the frame carries a new `src` and the iframe
  reloads; the chat around it is untouched. An app calls `refresh!` from
  the runner's `:after-step` and `:on-wait`.

  The db keys this namespace owns:

      :canvas/present?  whether there is an index.html to show
      :canvas/rev       the newest modification time of the directory"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rframes.http :as http]))

(defn canvas
  "The handle of one canvas.

      {:db      the app's atom (required)
       :dir     the directory the agent writes to (required)
       :path    the URL prefix the files are served under (\"/canvas\")
       :prepare (fn [html] html) applied to every .html file served, e.g.
                to inject a runtime (optional)}"
  [{:keys [dir path] :as opts}]
  (assoc opts
         :dir (io/file dir)
         :path (or path "/canvas")))

(defn- hidden?
  "Dot files and directories are not served."
  [subpath]
  (boolean (some #(str/starts-with? % ".") (str/split (str subpath) #"/"))))

(defn- newest-mtime
  [dir]
  (->> (file-seq dir)
       (filter #(.isFile %))
       (remove #(hidden? (str (.relativize (.toPath dir) (.toPath %)))))
       (map #(.lastModified %))
       (reduce max 0)))

(defn facts
  "What the view needs to know about the directory."
  [{:keys [dir]}]
  (let [index (io/file dir "index.html")]
    {:canvas/present? (.isFile index)
     :canvas/rev (when (.isFile index)
                   (newest-mtime dir))}))

(defn refresh!
  "Puts the facts of the directory into the db when they changed."
  [{:keys [db] :as canvas}]
  (let [new-facts (facts canvas)]
    (when (not= new-facts (select-keys @db (keys new-facts)))
      (swap! db merge new-facts))
    new-facts))

(defn- file-handler
  [{:keys [dir prepare]} w]
  (let [subpath (get-in w [:ring/route-params :*])
        subpath (if (str/blank? subpath) "index.html" subpath)
        file (when-not (hidden? subpath)
               (http/resolve-file dir subpath))]
    (assoc w
           :ring/response
           (cond
             (nil? file)
             http/not-found

             (and prepare (str/ends-with? (.getName file) ".html"))
             {:status 200
              :headers {"Content-Type" "text/html; charset=utf-8"
                        "Cache-Control" "no-cache"}
              :body (prepare (slurp file))}

             :else
             (http/file-response (:ring/request w) file)))))

(defn register
  "The route that serves the files of the canvas."
  [{:keys [path] :as canvas}]
  [{:ring/route [:get (str path "/*")]
    :ring/handler (fn [w] (file-handler canvas w))}])

(defn view
  "The canvas as hiccup: the iframe over index.html, keyed and revisioned
  so that Replicant leaves it alone until the files change, or a
  placeholder while there is nothing to show yet.

      {:placeholder  the text before the first page (optional)
       :title        the label above the canvas (\"Your creation\")}"
  [{:keys [canvas/present? canvas/rev]} {:keys [path] :as canvas} {:keys [placeholder title]}]
  (let [src (str (or path "/canvas") "/index.html")]
    [:div.canvas-pane
     [:div.canvas-bar
      [:span.label (or title "Your creation")]
      (when present?
        [:a.link {:href (str src "?rev=" rev)
                  :target "_blank"
                  :rel "noopener"}
         "Open in a new tab"])]
     (if present?
       [:ui/canvas {:ui/src src
                    :ui/rev rev}]
       [:div.canvas-placeholder
        (or placeholder "Your creation will appear here as soon as the agent has something to show.")])]))

(comment
  (facts (canvas {:db (atom {}) :dir "/tmp/canvas"}))
  )
