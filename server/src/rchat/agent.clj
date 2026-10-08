(ns rchat.agent
  "One agent, one conversation, on one machine.

  The agent is a mini-swe-agent log under `:agent/log` of the app's db atom,
  and one background thread steps it. Every prefix of the log is a valid
  state: the log is saved after every step, and a stopped agent continues
  from the saved log.

  A submission does not end the run. The agent hands over, and the
  `:user/ask` effect waits for the next message of the user, which becomes
  the next task. A message that arrives while the agent works is added to
  the conversation before the next model call. A stop ends the command that
  runs right now and the run, which `resume!` or the next message continues.

  The db keys this namespace owns:

      :agent/log        the mini-swe-agent log
      :agent/status     :idle, :working, :waiting or :stopped
      :agent/pending    messages of the user the agent has not read yet
      :agent/stopping?  a stop was requested and the step still runs"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [minisweagent.agent :as agent]
            [minisweagent.config :as mini-config]
            [minisweagent.log :as log]
            [minisweagent.model.http :as http]
            [rchat.env :as env]
            [rchat.look :as look]
            [rchat.scripted :as scripted]
            [rframes.command :as command]
            [rframes.http :as rhttp]))

(defn- now
  []
  (System/currentTimeMillis))

(defn runner
  "The handle of one agent.

      {:db          the app's atom (required)
       :dir         the agent's directory: its log and the images it looked
                    at (required)
       :config-fn   (fn [] mini-swe-agent config), see `config` (required)
       :scripted    path of an EDN script that stands in for the model, see
                    rchat.scripted (optional)
       :model/query a :model/query effect, for tests (optional)
       :on-wait     (fn [runner]) called when the agent hands over (optional)
       :after-step  (fn [runner log]) called after every step (optional)}"
  [{:keys [db dir] :as opts}]
  (let [dir (io/file dir)]
    (swap! db update :agent/status #(or % :idle))
    (assoc opts
           :dir dir
           :log-file (io/file dir "log.edn")
           :looks-dir (str (io/file dir "looks"))
           :queue (java.util.concurrent.LinkedBlockingQueue.)
           :running (atom nil)
           :stop? (atom false)
           :lock (Object.))))

(def default-model
  "GPT-6.1 Sol through OpenRouter: a good trade-off between cost and
  intelligence ($2 and $10 per million tokens, 1M context, tool calling)."
  "openrouter/openai/gpt-6.1-sol")

(defn- openrouter?
  [model-name]
  (str/starts-with? (str model-name) "openrouter/"))

(defn config
  "A mini-swe-agent config for a chat agent: it never asks before a
  command, a submission hands over instead of ending the run, no step
  limit, a cost limit in USD. The prompts default to the generic ones in
  resources/rchat/prompts, and an app replaces them with its own.

      {:model            provider/id as in mini-swe-agent-clj, default
                         `default-model`
       :price            USD per million tokens, {:input :output}, for an API
                         that does not report the cost (OpenRouter does)
       :text-based?      true for a model without tool calling
       :cost-limit       USD before the agent stops and asks (default 10)
       :cwd              where the commands run (default: this process's)
       :timeout-seconds  per command (default 300)
       :env              environment variables of the commands
       :system-template  the system prompt, {{name}} placeholders
       :instance-template the first user message, {{task}} and the rest
       :output-max-chars what the model sees of a command output}"
  [{:keys [model price text-based? cost-limit cwd timeout-seconds env
           system-template instance-template output-max-chars]
    :or {model default-model
         cost-limit 10.0
         timeout-seconds 300
         output-max-chars 12000}}]
  (mini-config/build
   [(if text-based? "mini-textbased.edn" "mini.edn")
    {:agent {:mode :yolo
             :confirm-exit true
             :step-limit 0
             :cost-limit cost-limit
             :output-max-chars output-max-chars
             :system-template (or system-template
                                  (slurp (io/resource (if text-based?
                                                        "rchat/prompts/system-text.md"
                                                        "rchat/prompts/system.md"))))
             :instance-template (or instance-template
                                    (slurp (io/resource "rchat/prompts/task.md")))}
     :environment (cond-> {:timeout-seconds timeout-seconds}
                    cwd (assoc :cwd cwd)
                    env (assoc :env env))
     :model (cond-> {:name model}
              price (assoc :price price)
              ;; OpenRouter reports the cost of a call itself. For Claude
              ;; it caches the conversation prefix with the same top-level
              ;; field as the Anthropic API.
              (openrouter? model) (merge {:cost-tracking :ignore-errors
                                          :params {:max_tokens 16000
                                                   :cache_control {:type "ephemeral"}}}))}]))

;;; The log in the db

(defn- save!
  [{:keys [log-file]} log]
  (io/make-parents log-file)
  (spit log-file (pr-str log)))

(defn- put-log!
  [{:keys [db] :as runner} log]
  (swap! db assoc :agent/log log)
  (save! runner log))

(defn- log-of
  [{:keys [db]}]
  (:agent/log @db []))

(defn status
  [{:keys [db]}]
  (:agent/status @db :idle))

;;; Messages of the user

(def ^:private stop-signal
  ::stop)

(defn- enqueue!
  [{:keys [db queue]} text]
  (swap! db update :agent/pending (fnil conj []) text)
  (.put queue text))

(defn- read-pending!
  [{:keys [db]}]
  (swap! db update :agent/pending #(vec (rest %))))

(defn- stopped
  []
  (ex-info "The user stopped the agent." {::stop true}))

(defn- ask!
  "The `:user/ask` effect. mini-swe-agent calls it when the agent submits:
  the agent hands over, and the thread waits for the next message of the
  user, which becomes the next task."
  [{:keys [db queue on-wait] :as runner} _prompt]
  (swap! db assoc :agent/status :waiting)
  (when on-wait
    (on-wait runner))
  (let [message (.take queue)]
    (when (= stop-signal message)
      (throw (stopped)))
    (read-pending! runner)
    (swap! db assoc :agent/status :working)
    message))

(defn- steer
  "A message that arrived while the agent works becomes a user message
  before the next model call."
  [{:keys [queue] :as runner} log]
  (let [message (when (= :query (log/phase log))
                  (.peek queue))]
    (if (and message (not= stop-signal message))
      (do
        (.take queue)
        (read-pending! runner)
        (conj log {:type :user/interrupted
                   :interrupt-type "UserMessage"
                   :at (now)
                   :message {:role "user"
                             :content message}}))
      log)))

;;; The loop

(defn- query-fn
  [{:keys [model/query scripted]}]
  (or query
      (if scripted
        (scripted/query-fn scripted)
        (http/query-fn {}))))

(defn- effects
  [runner]
  (let [query (query-fn runner)]
    {:model/query (fn [model-config messages]
                    (query model-config
                           (look/inline (keyword (:api model-config)) messages)))
     :env/execute (fn [env-config command]
                    (env/execute runner env-config command))
     :user/ask (fn [prompt]
                 (ask! runner prompt))
     :clock/now now}))

(defn- step!
  [{:keys [looks-dir after-step] :as runner} effects]
  (let [log (steer runner (log-of runner))
        api (keyword (get-in (log/config log) [:model :api]))
        stepped (look/attach (agent/step effects log) (count log) looks-dir api (now))]
    (put-log! runner stepped)
    (when after-step
      (after-step runner stepped))))

(defn- exit!
  [runner status]
  (let [log (log-of runner)]
    (when-not (log/exit log)
      (put-log! runner (conj log {:type :run/exited
                                  :status status
                                  :submission ""
                                  :at (now)})))))

(defn- run-loop!
  [{:keys [db stop? queue] :as runner}]
  (let [effects (effects runner)]
    (try
      (while (and (not @stop?)
                  (not= :done (log/phase (log-of runner))))
        (step! runner effects))
      (when @stop?
        (exit! runner "Stopped"))
      (catch Throwable e
        (if (::stop (ex-data e))
          (exit! runner "Stopped")
          (do
            (binding [*out* *err*]
              (println "[rchat]" (str e)))
            (put-log! runner (agent/crash (log-of runner) (now) e)))))
      (finally
        (reset! stop? false)
        (while (.remove queue stop-signal))
        (swap! db assoc
               :agent/status :stopped
               :agent/stopping? false)))))

(defn- start-thread!
  [{:keys [db stop?] :as runner}]
  (reset! stop? false)
  (swap! db assoc
         :agent/status :working
         :agent/stopping? false)
  (future (run-loop! runner)))

(defn- default-vars
  [{:keys [dir]} config]
  (merge (mini-config/platform-vars)
         {:cwd (or (not-empty (get-in config [:environment :cwd]))
                   (System/getProperty "user.dir"))
          :agent_dir (str dir)
          :timeout_minutes (max 1 (quot (get-in config [:environment :timeout-seconds] 30) 60))}))

(defn- startable?
  [runner]
  (and (empty? (log-of runner))
       (not (#{:working :waiting} (status runner)))))

(defn start!
  "Starts the agent on `task`. `vars` are extra values for the prompt
  templates. Returns true if it started."
  [{:keys [config-fn lock] :as runner} {:keys [task vars]}]
  (locking lock
    (when (startable? runner)
      (let [config (config-fn)]
        (put-log! runner (agent/init {:config config
                                      :task task
                                      :vars (merge (default-vars runner config) vars)
                                      :now (now)}))
        (start-thread! runner)
        true))))

(defn resumable
  "The log of a stopped run as one that continues: without its exit, and
  with the cost limit raised by the budget of the run if it was reached."
  [log now]
  (let [log (if (log/exit log)
              (pop log)
              log)
        budget (get-in (first log) [:config :agent :cost-limit] 0)]
    (if (agent/limits-exceeded? log)
      (conj log {:type :limits/raised
                 :at now
                 :step-limit 0
                 :cost-limit (+ (log/cost log) budget)})
      log)))

(defn resume!
  "Continues a stopped run. Returns true if it did."
  [{:keys [lock] :as runner}]
  (locking lock
    (when (and (= :stopped (status runner))
               (seq (log-of runner)))
      (put-log! runner (resumable (log-of runner) (now)))
      (start-thread! runner)
      true)))

(defn send!
  "A message of the user. It starts the agent, answers its handover, steers
  it while it works, or continues a stopped run with the message as the next
  task. Returns true if the message was taken."
  [{:keys [lock] :as runner} text]
  (locking lock
    (cond
      (startable? runner)
      (start! runner {:task text})

      (#{:working :waiting} (status runner))
      (do (enqueue! runner text)
          true)

      :else
      (do (put-log! runner (resumable (log-of runner) (now)))
          (enqueue! runner text)
          (start-thread! runner)
          true))))

(defn stop!
  "Ends the command that runs right now and the run. Returns true if there
  was something to stop."
  [{:keys [db queue stop? lock] :as runner}]
  (locking lock
    (when (#{:working :waiting} (status runner))
      (reset! stop? true)
      (swap! db assoc :agent/stopping? true)
      (env/kill! runner)
      (.put queue stop-signal)
      true)))

(defn load!
  "Puts the saved log into the db. An agent that was waiting for the user
  waits again, one that was working stays stopped until the user continues
  it. Called once at start."
  [{:keys [db log-file] :as runner}]
  (let [log (when (.isFile log-file)
              (edn/read-string (slurp log-file)))]
    (when (seq log)
      (swap! db assoc
             :agent/log log
             :agent/status :stopped)
      (when (= :submit (log/phase log))
        (resume! runner)))
    runner))

(defn api-key-missing?
  "Whether the environment variable with the API key of the model is
  unset."
  [{:keys [config-fn scripted] :as runner}]
  (and (not scripted)
       (not (:model/query runner))
       (let [variable (get-in (config-fn) [:model :api-key-env])]
         (boolean (and variable (str/blank? (System/getenv variable)))))))

;;; Commands and routes

(defn- send-command
  [runner w]
  (let [text (str/trim (str (:text (command/command-data w))))]
    (cond
      (str/blank? text)
      (command/rejected w :empty-message)

      (api-key-missing? runner)
      (command/rejected w :api-key-missing)

      :else
      (do (send! runner text)
          (command/accepted w)))))

(defn- stop-command
  [runner w]
  (if (stop! runner)
    (command/accepted w)
    (command/rejected w :not-running)))

(defn- continue-command
  [runner w]
  (cond
    (api-key-missing? runner)
    (command/rejected w :api-key-missing)

    (resume! runner)
    (command/accepted w)

    :else
    (command/rejected w :not-stopped)))

(defn- look-handler
  [{:keys [looks-dir]} w]
  (let [file (rhttp/resolve-file looks-dir (get-in w [:ring/route-params :*]))]
    (assoc w
           :ring/response
           (if file
             (rhttp/file-response (:ring/request w) file)
             rhttp/not-found))))

(defn register
  "The commands of the chat and the route for the images the agent looked
  at: `:agent/send {:text ...}`, `:agent/stop`, `:agent/continue` and
  GET /looks/*."
  [runner]
  [{:command/kind :agent/send
    :command/fn (fn [w] (send-command runner w))}
   {:command/kind :agent/stop
    :command/fn (fn [w] (stop-command runner w))}
   {:command/kind :agent/continue
    :command/fn (fn [w] (continue-command runner w))}
   {:ring/route [:get "/looks/*"]
    :ring/handler (fn [w] (look-handler runner w))}])

(comment
  (def db (atom {}))
  (def r (runner {:db db
                  :dir "/tmp/rchat-repl/.agent"
                  :config-fn #(config {:model "openrouter/moonshotai/kimi-k2"
                                       :cwd "/tmp/rchat-repl"})}))
  (send! r "Create hello.txt with a greeting.")
  (status r)
  (map :type (log-of r))
  (stop! r)
  )
