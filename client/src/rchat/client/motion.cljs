(ns rchat.client.motion
  "The motion pane of the REPL agent: the model's definitions, the
  program the server collects from the log, run here in the browser with
  SCI, and fnmotion's player renders `(design-view design t)` for every
  time the user scrubs to or that playback reaches. Nothing crosses the
  wire per frame; the design data and the source arrive as alias attrs
  when they change.

      [:ui/motion {:ui/source \"(defn design-view [design t] ...)\"
                   :ui/design {...}
                   :ui/duration 8}]

  The frame is rendered into a node of its own by a second Replicant
  root, so that 60 frames a second never re-render the page around it."
  (:require [fnmotion.captions]
            [fnmotion.core]
            [fnmotion.player :as player]
            [fnmotion.timeline]
            [replicant.dom :as r]
            [sci.core :as sci]))

(defonce ^:private ctx
  (sci/init {:namespaces {'fnmotion.core (sci/copy-ns fnmotion.core (sci/create-ns 'fnmotion.core))
                          'fnmotion.timeline (sci/copy-ns fnmotion.timeline (sci/create-ns 'fnmotion.timeline))
                          'fnmotion.captions (sci/copy-ns fnmotion.captions (sci/create-ns 'fnmotion.captions))}
             :classes {'js js/globalThis
                       :allow :all}}))

(def ^:private prelude
  "(require '[fnmotion.core :as fm] '[fnmotion.timeline :as tl] '[fnmotion.captions :as captions])")

(defonce ^:private state*
  (atom {}))

(defn- compile!
  "Evaluates the program and returns the view function or the error."
  [source]
  (try
    (sci/eval-string* ctx (str prelude "\n" source))
    (let [view (sci/eval-string* ctx "(when (resolve 'design-view) design-view)")]
      (if (fn? view)
        {:view view
         :error nil}
        {:view nil
         :error "design-view is not defined yet."}))
    (catch :default e
      {:view nil
       :error (str (.-message e))})))

(defn- frame
  [{:keys [view error design]} t]
  (cond
    error [:p.error error]
    view (try
           (view design t)
           (catch :default e
             [:p.error (str "The frame failed at " (.toFixed t 1) " s: " (.-message e))]))
    :else nil))

(defn- render!
  []
  (let [{:keys [node player] :as state} @state*]
    (when (and node player)
      (r/render node
                [:div.motion
                 [:div.motion-frame (frame state (:t @player))]
                 (player/controls player)]))))

(defn- ensure-player!
  [duration]
  (or (:player @state*)
      (let [player (player/make {:duration duration
                                 :on-change (fn [_] (render!))})]
        (swap! state* assoc :player player)
        player)))

(defn motion
  [{:keys [ui/source ui/design ui/duration]} _children]
  (let [duration (or duration 10)
        player (ensure-player! duration)]
    (when (not= duration (:duration @player))
      (swap! player assoc :duration duration :t (min (:t @player) duration)))
    (when (not= source (:source @state*))
      (swap! state* merge {:source source} (compile! (str source))))
    (swap! state* assoc :design design)
    ;; After the page's own render, in its own root.
    (js/requestAnimationFrame render!)
    [:div.motion-root {:replicant/key "motion"
                       :replicant/on-mount (fn [{:keys [replicant/node]}]
                                             (swap! state* assoc :node node)
                                             (render!))
                       :replicant/on-unmount (fn [_]
                                               (player/pause! (:player @state*))
                                               (swap! state* dissoc :node))}]))

(def register
  "The `:ui/motion` alias, for an app that plays motion designs in the
  browser (RCHAT_MOTION=browser in the example). Concat it with
  `rchat.client/register`."
  [{:ui.alias/kind :ui/motion
    :ui.alias/fn motion}])
