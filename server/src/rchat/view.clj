(ns rchat.view
  "The chat as hiccup, a pure function of the app db. The hiccup crosses the
  wire as transit, so it is plain data: event handlers are action vectors,
  and the one thing the browser owns, the text the user types, is the
  `:ui/composer` alias that rchat.client implements.

  `page` is the whole page; an app that wants its own layout composes
  `header`, `feed` and `composer` itself. All take the db value and an
  options map:

      {:title          the name in the header
       :handover-label the label of a handover (\"Ready for you\")
       :text-fn        (fn [text] children) for the text of the agent, by
                       default its markdown as hiccup (rchat.markdown); an
                       app that turns positions into jumps passes
                       (fn [text] (markdown/children text {:leaf ...}))
       :user-text-fn   the same for the text of the user, plain by default:
                       people do not write markdown
       :placeholder    of the composer
       :max-items      newest items of the feed that are shown (60)
       :show-task?     whether the first message, the task, is shown (true)
       :card           hiccup the page shows above the feed (optional)
       :key-missing?   true shows that the model's API key is not set}"
  (:require [clojure.string :as str]
            [minisweagent.log :as log]
            [rchat.feed :as feed]
            [rchat.markdown :as markdown]))

(def defaults
  {:title "rchat"
   :handover-label "Ready for you"
   :text-fn markdown/children
   :user-text-fn list
   :max-items 60
   :show-task? true})

(defn- output-view
  [{:keys [returncode output exception-info]}]
  [:div.output
   [:div.meta {:class (when-not (= 0 returncode) "failed")}
    (str "exit " returncode " · " (count output) " characters")]
   (when (or (seq output) (seq exception-info))
     [:pre (str (feed/tail 2000 output)
                (when (seq exception-info)
                  (str "\n" exception-info)))])])

(defn- work-view
  "The commands of a step and what they returned, collapsed behind one
  plain row: a normal user does not need to read them, and can."
  [commands outputs]
  (let [failed? (some #(not= 0 (:returncode %)) outputs)]
    [:details.work
     [:summary {:class (when failed? "failed")}
      (str (if (= 1 (count commands))
             "Tool call"
             (str (count commands) " tool calls"))
           (when failed?
             " · one did not work"))]
     (for [[i command] (map-indexed vector commands)
           node [[:pre.command {:replicant/key (str "command-" i)} (feed/cut 3000 command)]
                 (when-let [output (nth outputs i nil)]
                   (output-view output))]
           :when node]
       node)]))

(defn- looks-view
  [looks]
  (when (seq looks)
    [:div.looks
     (for [url looks]
       [:img {:src url
              :loading "lazy"}])]))

(defn- item-view
  [{:keys [text-fn user-text-fn handover-label]}
   {:keys [kind index text commands outputs looks status exception]}]
  (case kind
    :step
    [:div.item.step {:replicant/key index}
     (when (seq text)
       [:div.text (text-fn text)])
     (when (seq commands)
       (work-view commands outputs))
     (looks-view looks)]

    :result
    [:div.item.result {:replicant/key index}
     (work-view [] outputs)
     (looks-view looks)]

    :looks
    [:div.item.result {:replicant/key index}
     (looks-view looks)]

    :handover
    [:div.item.handover {:replicant/key index}
     [:div.label handover-label]
     [:div.text (text-fn text)]]

    :user
    [:div.item.user {:replicant/key index}
     [:div.label "You"]
     (when (seq text)
       [:p.text (user-text-fn text)])
     (looks-view looks)]

    :user-looks
    [:div.item.user {:replicant/key index}
     [:div.label "You"]
     (looks-view looks)]

    :note
    [:div.item.note {:replicant/key index}
     [:p.text text]]

    :exit
    [:div.item.exit {:replicant/key index}
     [:div.label (if (= "Stopped" status)
                   "Stopped"
                   (str "Stopped: " status))]
     (when (seq exception)
       [:p.text (feed/cut 600 exception)])]))

(defn header
  [{:keys [agent/status agent/log] :as db} opts]
  (let [{:keys [title]} (merge defaults opts)]
    [:header.bar
     [:h1 title]
     [:span.status {:class (name (or status :idle))} (feed/status-text db)]
     (when (seq log)
       [:span.spend (format "%d steps · $%.2f" (log/n-calls log) (log/cost log))])]))

(defn feed
  "The conversation. The client keeps an element with the data-stick-to-end
  attribute scrolled to its end while the user is at the end."
  [{:keys [agent/status agent/log agent/pending] :as db} opts]
  (let [status (or status :idle)
        {:keys [max-items show-task?] :as opts} (merge defaults opts)
        items (cond->> (feed/grouped (feed/items log))
                (not show-task?) (remove #(= 0 (:index %))))
        hidden (max 0 (- (count items) max-items))]
    [:section.feed {:data-stick-to-end "true"}
     (when (pos? hidden)
       [:p.hint (str hidden " earlier steps are not shown.")])
     (map #(item-view opts %) (drop hidden items))
     (for [[index {:keys [text images]}] (map-indexed vector pending)]
       [:div.item.user.pending {:replicant/key (str "pending-" index)}
        [:div.label "You (not read yet)"]
        (when (seq text)
          [:p.text text])
        (looks-view (map #(str "/looks/" %) images))])
     ;; Where the eyes are while reading: what the agent does right now,
     ;; below the newest item.
     (when (= :working status)
       [:div.item.working {:replicant/key "working"}
        [:span.dot]
        [:span (feed/status-text db)]])]))

(def send-actions
  "The action that sends the draft and the attached images as a message."
  [[:data/command
    {:command/kind :agent/send
     :command/data {:text [:store/deref :rchat/draft]
                    :images [:store/deref :rchat/attachments]}}
    {:on-success [[:store/dissoc :rchat/draft]
                  [:store/dissoc :rchat/attachments]]}]])

(defn composer
  [{:keys [agent/status agent/stopping?]} opts]
  (let [status (or status :idle)
        {:keys [placeholder key-missing?]} (merge defaults opts)]
    [:div.composer
     (when key-missing?
       [:p.error "The API key of the model is not set in the environment of the server."])
     [:ui/composer {:ui/store-key :rchat/draft
                    :ui/attachments-key :rchat/attachments
                    :ui/upload-url "/upload"
                    :ui/send send-actions
                    :ui/send-label (if (= :idle status) "Start" "Send")
                    :ui/disabled? (boolean key-missing?)
                    :rows 3
                    :placeholder (or placeholder
                                     (case status
                                       :idle "What should the agent do?"
                                       :waiting "Your answer"
                                       "Send a message, the agent reads it at its next step"))}]
     (when (#{:working :waiting} status)
       [:button.stop {:disabled (boolean stopping?)
                      :on {:click [[:data/command {:command/kind :agent/stop}]]}}
        (if stopping? "Stopping…" "Stop")])
     (when (= :stopped status)
       [:button.continue {:disabled (boolean key-missing?)
                          :on {:click [[:data/command {:command/kind :agent/continue}]]}}
        "Continue"])]))

(defn page
  [db opts]
  (let [{:keys [card]} (merge defaults opts)]
    [:div.rchat
     (header db opts)
     [:main
      (when card
        [:section.card card])
      (feed db opts)]
     (composer db opts)]))

(comment
  (page {:agent/log [] :agent/status :idle} {:title "example"})
  )
