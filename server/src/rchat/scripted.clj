(ns rchat.scripted
  "A stand-in for the model, to try an app and to test it without an API
  key. The script is an EDN vector of steps

      [{:text \"Looking around.\" :commands [\"ls\"]} ...]

  and the model answers call n with step n. Which step is next is derived
  from the conversation, so a resumed run continues where it stopped. After
  the last step the model hands over."
  (:require [clojure.edn :as edn]))

(def ^:private last-step
  {:text "The script is over."
   :commands ["echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT; echo The script is over."]})

(defn response
  "The normalized response (see `minisweagent.model`) for a step."
  [index {:keys [text commands]}]
  (let [tool-calls (vec (map-indexed (fn [n command]
                                       {:id (str "scripted_" index "_" n)
                                        :name "bash"
                                        :input {:command command}})
                                     commands))]
    {:message {:role "assistant"
               :content (into [{:type "text"
                                :text text}]
                              (map #(assoc % :type "tool_use"))
                              tool-calls)}
     :text text
     :tool-calls tool-calls
     :truncated? false
     :tokens {:input 0
              :output 0
              :cache-write 0
              :cache-read 0}
     :model "scripted"}))

(defn query-fn
  "The `:model/query` effect that plays `script`: a vector of steps, or
  anything `slurp` reads an EDN vector from (a path, a file, a resource)."
  [script]
  (let [steps (if (vector? script)
                script
                (edn/read-string (slurp script)))]
    (fn [_model-config messages]
      (let [index (count (filter #(= "assistant" (:role %)) messages))]
        (response index (get steps index last-step))))))

(comment
  (response 0 {:text "Looking around." :commands ["ls"]})
  )
