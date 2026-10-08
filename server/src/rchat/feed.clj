(ns rchat.feed
  "The conversation as the user reads it: a projection of the agent log to
  feed items, and one line about what the agent does right now."
  (:require [clojure.string :as str]
            [minisweagent.log :as log]
            [minisweagent.observation :as observation]
            [rchat.look :as look]))

(defn cut
  "At most `n` characters of `text`."
  [n text]
  (let [text (str text)]
    (if (< n (count text))
      (str (subs text 0 n) " …")
      text)))

(defn tail
  [n text]
  (let [text (str text)]
    (if (< n (count text))
      (str "… " (subs text (- (count text) n)))
      text)))

(defn message-text
  "The text of a message, without thinking and tool blocks."
  [{:keys [content]}]
  (cond
    (string? content) content
    (vector? content) (->> content
                           (filter #(= "text" (:type %)))
                           (map :text)
                           (str/join "\n"))
    :else ""))

(defn- look-url
  [path]
  (str "/looks/" (last (str/split path #"/"))))

(defn- user-text
  "What the user wrote, without the sentence mini-swe-agent puts in front
  of a message that answers a handover."
  [entry]
  (str/replace-first (message-text (:message entry)) "The user added a new task: " ""))

(defn item
  "A log entry as what the user reads about it, or nil."
  [entry]
  (case (:type entry)
    :run/started
    {:kind :user
     :text (:task entry)}

    :model/responded
    {:kind :step
     ;; An agent whose message is code puts the note for the user in :note.
     :text (str/trim (or (:note entry) (message-text (:message entry))))
     :commands (mapv :command (:actions entry))}

    :actions/observed
    (if-let [handover (some observation/submission (:outputs entry))]
      {:kind :handover
       :text (str/trim handover)}
      {:kind :result
       :outputs (mapv #(select-keys % [:returncode :output :exception-info]) (:outputs entry))
       :looks (mapv look-url (look/references entry))})

    :look/attached
    {:kind :looks
     :looks (mapv look-url (look/references entry))}

    :user/interrupted
    (let [looks (mapv look-url (look/references entry))]
      (cond-> {:kind :user
               :text (user-text entry)}
        (seq looks) (assoc :looks looks)))

    :user/attached
    {:kind :user-looks
     :looks (mapv look-url (look/references entry))}

    :model/format-error
    {:kind :note
     :text "The model answered without a command and was asked to try again."}

    :run/exited
    {:kind :exit
     :status (:status entry)
     :exception (:exception entry)}

    nil))

(defn items
  "The feed of a log, each item with the index of its entry."
  [log]
  (vec (keep-indexed (fn [index entry]
                       (some-> (item entry)
                               (assoc :index index)))
                     log)))

(defn grouped
  "The items with every result and image folded into the step that produced
  them, so that a step reads as one note, one collapsed row of tool calls
  and the images."
  [items]
  (reduce (fn [acc {:keys [kind] :as item}]
            (cond
              (and (#{:result :looks} kind)
                   (= :step (:kind (peek acc))))
              (conj (pop acc)
                    (-> (peek acc)
                        (update :outputs (fnil into []) (:outputs item))
                        (update :looks (fnil into []) (:looks item))))

              (and (= :user-looks kind)
                   (= :user (:kind (peek acc))))
              (conj (pop acc)
                    (update (peek acc) :looks (fnil into []) (:looks item)))

              :else
              (conj acc item)))
          []
          items))

(defn- running-command
  [log]
  (when (= :execute (log/phase log))
    (:command (first (log/pending-actions log)))))

(defn status-text
  "One line about what the agent does right now."
  [{:keys [agent/status agent/stopping? agent/log]}]
  (case status
    :idle "Ready"
    :waiting "Waiting for you"
    :stopped (case (:status (log/exit log))
               "LimitsExceeded" (format "Stopped: the budget of $%.0f is used up"
                                        (double (get-in (log/config log) [:agent :cost-limit] 0)))
               "Stopped" "Stopped"
               nil "Stopped"
               (str "Stopped: " (:status (log/exit log))))
    :working (cond
               stopping? "Stopping"
               (running-command log) (str "Running: " (cut 70 (first (str/split-lines (running-command log)))))
               :else "Thinking")
    "Ready"))

(comment
  (items [{:type :model/responded
           :message {:role "assistant" :content "Hi"}
           :actions [{:command "ls"}]}])
  )
