(ns rchat.client
  "The browser side of the chat: the composer. The server names it as the
  `:ui/composer` alias and decides when it appears, this side owns what it
  renders: a textarea whose draft lives in the store, so that a new frame
  from the server does not reset what the user types, the images the user
  attaches, and the send button.

      [:ui/composer {:ui/store-key :rchat/draft
                     :ui/attachments-key :rchat/attachments
                     :ui/upload-url \"/upload\"
                     :ui/send [[:data/command ...]]
                     :ui/send-label \"Send\"
                     :ui/attach-title \"Attach an image, or paste one into the text\"
                     :ui/remove-title \"Remove\"
                     :ui/disabled? false
                     :placeholder \"...\"}]

  The labels and the titles (the tooltips of the attach button and of the
  remove button on an attachment) are the server's to word, English when
  absent.

  Enter sends on a keyboard, Shift+Enter breaks the line. On a touch
  device Enter breaks the line and the button sends, like every chat app
  there. An image pasted into the textarea, dropped on it or picked with
  the attach button is scaled in the browser to at most 1600 px on its
  long side, uploaded, and sent along with the next message."
  (:require [clojure.string :as str]
            [cognitect.transit :as t]
            [rframes.client.state :as state]
            [rframes.client.ui :as ui]))

(def max-side
  "Pixels on the long side of an uploaded image."
  1600)

(defn- coarse-pointer?
  []
  (boolean (some-> (js/window.matchMedia "(pointer: coarse)")
                   (.-matches))))

(defn- image-file?
  [^js file]
  (and file (str/starts-with? (str (.-type file)) "image/")))

(defn- scaled
  "Calls `callback` with the image as a blob of at most `max-side` pixels on
  its long side: the file itself when it is small enough, else a scaled
  copy from a canvas, PNG for a PNG (screenshots stay crisp) and JPEG for
  the rest."
  [^js file callback]
  (let [url (js/URL.createObjectURL file)
        image (js/Image.)]
    (set! (.-onload image)
          (fn [_]
            (js/URL.revokeObjectURL url)
            (let [w (.-naturalWidth image)
                  h (.-naturalHeight image)
                  scale (min 1 (/ max-side (max w h 1)))]
              (if (= 1 scale)
                (callback file)
                (let [canvas (js/document.createElement "canvas")
                      cw (js/Math.round (* w scale))
                      ch (js/Math.round (* h scale))
                      png? (= "image/png" (.-type file))]
                  (set! (.-width canvas) cw)
                  (set! (.-height canvas) ch)
                  (.drawImage (.getContext canvas "2d") image 0 0 cw ch)
                  (.toBlob canvas
                           (fn [blob]
                             (callback (or blob file)))
                           (if png? "image/png" "image/jpeg")
                           0.85))))))
    (set! (.-onerror image)
          (fn [_]
            (js/URL.revokeObjectURL url)
            (js/console.error "[upload] not an image the browser can read")))
    (set! (.-src image) url)))

(defn- upload!
  "Uploads the image and adds `{:name :url}` of the stored file to the
  attachments in the store."
  [store attachments-key upload-url ^js file]
  (scaled file
          (fn [^js blob]
            (-> (js/fetch upload-url #js {:method "POST"
                                          :headers #js {"Content-Type" (.-type blob)}
                                          :body blob})
                (.then (fn [^js response]
                         (if (.-ok response)
                           (.text response)
                           (throw (js/Error. (str "upload failed with " (.-status response)))))))
                (.then (fn [text]
                         (let [{:keys [name url]} (t/read (t/reader :json) text)]
                           (swap! store update attachments-key
                                  (fn [attachments]
                                    (if (some #(= name (:name %)) attachments)
                                      attachments
                                      (conj (vec attachments) {:name name
                                                               :url url})))))))
                (.catch (fn [error]
                          (js/console.error "[upload]" error)))))))

(defn- upload-all!
  [store attachments-key upload-url files]
  (doseq [file files
          :when (image-file? file)]
    (upload! store attachments-key upload-url file)))

(defn- pasted-images
  [^js event]
  (->> (array-seq (or (some-> event .-clipboardData .-items) #js []))
       (filter #(str/starts-with? (str (.-type ^js %)) "image/"))
       (map #(.getAsFile ^js %))
       (remove nil?)))

(defn- attachments-view
  [store attachments-key attachments remove-title]
  (when (seq attachments)
    [:div.attachments
     (for [{:keys [name url]} attachments]
       [:span.attachment {:replicant/key name}
        [:img {:src url
               :alt "attached image"}]
        [:button.remove {:type "button"
                         :title (or remove-title "Remove")
                         :on {:click (fn [_event]
                                       (swap! store update attachments-key
                                              (fn [attachments]
                                                (vec (remove #(= name (:name %)) attachments)))))}}
         "×"]])]))

(defn composer
  [{:keys [ui/store-key ui/attachments-key ui/upload-url ui/send ui/send-label
           ui/attach-title ui/remove-title ui/disabled? ui/state]
    :as attrs}
   _children]
  (let [store state/store
        attachments-key (or attachments-key :rchat/attachments)
        upload-url (or upload-url "/upload")
        draft (str (get state store-key ""))
        attachments (get state attachments-key [])
        ready? (and (not disabled?)
                    (or (not (str/blank? draft))
                        (seq attachments))
                    (not (ui/kind-issued? state :agent/send)))
        send! (fn []
                (when ready?
                  (state/dispatch! send)))]
    [:div.composer-input
     (attachments-view store attachments-key attachments remove-title)
     [:div.row
      [:textarea
       (-> (dissoc attrs :ui/store-key :ui/attachments-key :ui/upload-url :ui/send
                   :ui/send-label :ui/attach-title :ui/remove-title :ui/disabled? :ui/state)
           (assoc :value draft
                  :disabled (boolean disabled?)
                  :on {:input [[:store/assoc store-key :event/target.value]]
                       :paste (fn [^js event]
                                (let [files (pasted-images event)]
                                  (when (seq files)
                                    (.preventDefault event)
                                    (upload-all! store attachments-key upload-url files))))
                       :dragover (fn [^js event]
                                   (.preventDefault event))
                       :drop (fn [^js event]
                               (.preventDefault event)
                               (upload-all! store attachments-key upload-url
                                            (array-seq (.. event -dataTransfer -files))))
                       :keydown (fn [^js event]
                                  (when (and (= "Enter" (.-key event))
                                             (not (.-shiftKey event))
                                             (not (.-isComposing event))
                                             (not (coarse-pointer?)))
                                    (.preventDefault event)
                                    (send!)))}))]
      [:label.attach {:title (or attach-title "Attach an image, or paste one into the text")}
       "📎"
       [:input {:type "file"
                :accept "image/*"
                :multiple true
                :disabled (boolean disabled?)
                :style {:display "none"}
                :on {:change (fn [^js event]
                               (upload-all! store attachments-key upload-url
                                            (array-seq (.. event -target -files)))
                               (set! (.. event -target -value) ""))}}]]
      [:button.primary {:disabled (not ready?)
                        :on {:click (fn [_event]
                                      (send!))}}
       (or send-label "Send")]]]))

(defonce ^:private canvas-node*
  (atom nil))

(defonce ^:private canvas-src*
  (atom nil))

(defonce ^:private canvas-scroll*
  (atom nil))

(defn- canvas-scroll
  "The scroll position of the page in the iframe, same origin."
  []
  (try
    (when-let [^js node @canvas-node*]
      (let [^js win (.-contentWindow node)]
        [(.-scrollX win) (.-scrollY win)]))
    (catch :default _
      nil)))

(defn- restore-canvas-scroll!
  [_event]
  (when-let [[x y] @canvas-scroll*]
    (reset! canvas-scroll* nil)
    (try
      (when-let [^js node @canvas-node*]
        (.scrollTo (.-contentWindow node) x y))
      (catch :default _
        nil))))

(defn canvas
  "The creation of the agent: an iframe over the page the server names.
  A new `:ui/rev` changes the src and reloads the page at the position the
  user was looking at; everything else Replicant leaves alone. `:ui/title`
  is the title of the iframe, \"Your creation\" when absent."
  [{:keys [ui/src ui/rev ui/title]} _children]
  (let [src (str src "?rev=" rev)]
    (when (and @canvas-src* (not= src @canvas-src*))
      (reset! canvas-scroll* (canvas-scroll)))
    (reset! canvas-src* src)
    [:iframe.canvas {:replicant/key "canvas"
                     :src src
                     :title (or title "Your creation")
                     :replicant/on-mount (fn [{:keys [replicant/node]}]
                                           (reset! canvas-node* node)
                                           (.addEventListener ^js node "load" restore-canvas-scroll!))
                     :replicant/on-unmount (fn [_]
                                             (reset! canvas-node* nil)
                                             (reset! canvas-src* nil))}]))

(def register
  "The composer and the canvas. The motion pane of the browser mode, which
  brings SCI and fnmotion into the bundle, is `rchat.client.motion/register`
  for the apps that want it."
  [{:ui.alias/kind :ui/composer
    :ui.alias/fn composer}
   {:ui.alias/kind :ui/canvas
    :ui.alias/fn canvas}])
