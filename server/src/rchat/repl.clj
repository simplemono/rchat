(ns rchat.repl
  "An agent that is a REPL inside the app it works on. An experiment after
  Bret Victor's Inventing on Principle: the creation lives in the app's
  own state, the model shapes it with Clojure forms evaluated in this
  process, and the user sees every change the moment it is made.

  The model writes Clojure forms; this process evaluates them in the
  `user` namespace and shows the results back the way a REPL does. Every
  result stays bound to `$n`. The model can redefine the view of the
  creation, change the data it renders, and add commands the UI sends, so
  the harness grows controls (sliders, color pickers, text fields) that
  let the user play with what was built.

  The log has the shape of a mini-swe-agent log, so `rchat.agent` runs it
  with this namespace's `step` as the `:step` of the runner, and the feed
  and the view show it unchanged: a form is a \"tool call\", its REPL
  output the result, `(done \"...\")` the handover.

  Hazard, the same as a REPL: a form runs with the full authority of the
  process. This is for a sandbox that holds nothing but the user's own
  creation."
  (:require [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [minisweagent.log :as log]
            [minisweagent.model :as model]
            [minisweagent.observation :as observation]))

(def preview-chars
  "How much of a result the model sees; the binding has it all."
  4000)

;;; What the model gets besides Clojure

(defn sh
  "Runs a command and returns {:exit :out :err}."
  [& args]
  (select-keys (apply shell/sh args) [:exit :out :err]))

(def ^:dynamic *done*
  "Bound to an atom while a message is evaluated: `done` puts the summary
  into it."
  nil)

(defn done
  "Ends the turn with a message for the user. Nothing after it is
  evaluated."
  [summary]
  (when *done*
    (reset! *done* (str summary)))
  summary)

(defn design-helpers
  "Two functions over the `:design` key of the app's atom `db`, so that the
  model changes the creation without reading the whole app state back:
  `(design)` is the design map, `(design! f & args)` applies `f` to it and
  returns the new map."
  [db]
  {'design (fn []
             (:design @db))
   'design! (fn [f & args]
              (:design (swap! db (fn [db]
                                   (update db :design #(apply f % args))))))})

(defn install!
  "Puts the REPL vocabulary into the `user` namespace: `sh`, `done` and
  whatever the app adds in `bindings`, e.g. the db atom and
  `design-helpers`."
  [bindings]
  (doseq [[sym value] (merge {'sh sh
                              'done done}
                             bindings)]
    (intern 'user sym value)))

;;; Reading and evaluating

(defn read-forms
  "All forms of `text`, and the reader error that stopped reading, if any."
  [text]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. (str text)))]
    (loop [forms []]
      (let [form (try
                   (read {:eof ::eof} reader)
                   (catch Exception e
                     e))]
        (cond
          (= ::eof form) {:forms forms}
          (instance? Exception form) {:forms forms
                                      :error form}
          :else (recur (conj forms form)))))))

(defn- eval-form
  [form]
  (let [out (java.io.StringWriter.)]
    (try
      (let [value (binding [*ns* (the-ns 'user)
                            *out* out
                            *err* out]
                    (eval form))]
        {:value value
         :out (str out)})
      (catch Throwable e
        {:error e
         :out (str out)}))))

(defn- pp-str
  [value]
  (binding [*print-length* 50
            *print-level* 8
            pp/*print-right-margin* 100]
    (str/trimr (with-out-str (pp/pprint value)))))

(defn- elide
  [s max-chars]
  (let [length (count s)]
    (if (<= length max-chars)
      s
      (let [half (quot max-chars 2)]
        (str (subs s 0 half)
             "\n;; ... " (- length max-chars) " of " length " chars not shown, the binding has them all ...\n"
             (subs s (- length half)))))))

(defn- error-map
  [e]
  (cond-> {:error (.getName (class e))
           :message (ex-message e)}
    (ex-data e) (assoc :data (ex-data e))
    (ex-cause e) (assoc :cause (ex-message (ex-cause e)))))

(defn render-result
  "The REPL output of evaluation `n`: what was printed, then `$n => value`."
  [n {:keys [value error out]}]
  (let [long-string? (and (string? value)
                          (or (str/includes? value "\n") (> (count value) 60)))
        shown (cond
                error (pp-str (error-map error))
                long-string? (str ";; string of " (count value) " chars\n" value)
                :else (pp-str value))
        shown (elide shown preview-chars)
        separator (if (or (str/includes? shown "\n") (> (count shown) 60)) "\n" " ")]
    (str out
         (when (and (seq out) (not (str/ends-with? out "\n"))) "\n")
         "$" n " =>" separator shown "\n")))

(defn- n-evaluations
  "How many forms were evaluated in the log so far: the next `$n`."
  [log]
  (count (for [entry log
               :when (= :actions/observed (:type entry))
               output (:outputs entry)
               :when (:evaluated? output)]
           output)))

;;; The step

(defn- record
  [log now & entries]
  (into log (map #(assoc % :at now)) entries))

(defn- actions
  "The forms of the model's message as actions, each form as text."
  [text]
  (let [{:keys [forms error]} (read-forms text)]
    (cond-> (mapv (fn [form]
                    {:command (pr-str form)})
                  forms)
      error (conj {:command ""
                   :reader-error (ex-message error)}))))

(defn note
  "The comments of the model's message, the part written for the user."
  [text]
  (->> (str/split-lines (str text))
       (map str/trim)
       (filter #(str/starts-with? % ";"))
       (map #(str/trim (str/replace % #"^;+\s?" "")))
       (remove str/blank?)
       (str/join "\n")))

(defn- query-model
  [{:keys [model/query clock/now]} log]
  (let [{:keys [model] :as config} (log/config log)
        response (query model (log/conversation log))
        actions (actions (:text response))
        call {:cost (model/cost model response)
              :tokens (:tokens response)
              :model (:model response)}]
    (record log (now)
            (assoc call
                   :type :model/responded
                   :message (:message response)
                   :note (note (:text response))
                   :actions (if (seq actions)
                              actions
                              ;; Nothing to evaluate: the note is the
                              ;; observation, so the model gets it.
                              [{:command ""
                                :note (str ";; No forms read. Write Clojure forms, or (done \"message\")"
                                           " to hand over.")}])))))

(defn evaluate
  "Evaluates the pending actions in order, binding each result to `$n`.
  Stops after `done`, whose output carries the handover for the feed."
  [{:keys [clock/now]} log]
  (let [pending (log/pending-actions log)
        first-n (inc (n-evaluations log))
        done-summary (atom nil)
        outputs (loop [actions pending
                       n first-n
                       outputs []]
                  (if-let [{:keys [command reader-error note]} (first actions)]
                    (cond
                      @done-summary
                      (recur (rest actions) n (conj outputs {:output ""
                                                            :returncode 0
                                                            :exception-info "not evaluated: after done"}))

                      note
                      (recur (rest actions) n (conj outputs {:output note
                                                            :returncode 1
                                                            :exception-info ""}))

                      reader-error
                      (recur (rest actions) n (conj outputs {:output (str ";; Reader error: " reader-error
                                                                          " The rest of the message was not read.")
                                                            :returncode 1
                                                            :exception-info ""}))

                      :else
                      (let [form (read-string command)
                            result (binding [*done* done-summary]
                                     (eval-form form))]
                        (if @done-summary
                          (recur (rest actions) n
                                 (conj outputs {:output (str observation/submit-command "\n" @done-summary)
                                                :returncode 0
                                                :exception-info ""
                                                :evaluated? true}))
                          (do
                            (intern 'user (symbol (str "$" n)) (if (contains? result :error)
                                                                 (:error result)
                                                                 (:value result)))
                            (recur (rest actions) (inc n)
                                   (conj outputs {:output (render-result n result)
                                                  :returncode (if (:error result) 1 0)
                                                  :exception-info ""
                                                  :evaluated? true}))))))
                    outputs))
        text (str/join "" (map :output outputs))]
    (record log (now)
            {:type :actions/observed
             :message {:role "user"
                       :content (if (str/blank? text)
                                  "(no output)"
                                  text)}
             :outputs outputs})))

(defn- submit
  "The model handed over: the next message of the user is the next task."
  [{:keys [user/ask clock/now]} log]
  (let [input (ask {:prompt "> "
                    :mode :yolo})]
    (record log (now)
            {:type :user/interrupted
             :interrupt-type "UserNewTask"
             :message {:role "user"
                       :content (str "The user added a new task: " input)}})))

(defn step
  "The `:step` of a `rchat.agent/runner`: appends the entries of the next
  step, like mini-swe-agent's, over the same log."
  [effects log]
  (case (log/phase log)
    :query (query-model effects log)
    :execute (evaluate effects log)
    :submit (submit effects log)
    :done log))

(defn source-of
  "The latest definition of `name` the model evaluated, as text, or nil:
  the creation as code, ready to be lifted out as a template."
  [log name]
  (let [prefix (str "(defn " name " ")]
    (->> log
         (filter #(= :model/responded (:type %)))
         (mapcat :actions)
         (map :command)
         (filter #(str/starts-with? (str %) prefix))
         (last))))

(comment
  (read-forms "(+ 1 2) ;; a note\n(done \"ok\")")
  (render-result 1 (eval-form '(+ 1 2)))
  )
