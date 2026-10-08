(ns rchat.agent-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [minisweagent.log :as log]
            [rchat.agent :as agent]
            [rchat.feed :as feed]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "rchat-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(def script
  "A model that runs a command, hands over, runs a long command, hands over
  again. After that the script is over and every call hands over."
  [{:text "Looking around."
    :commands ["echo hello"]}
   {:text "Handing over."
    :commands ["echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT; echo First result."]}
   {:text "Working on it."
    :commands ["sleep 30"]}
   {:text "Handing over again."
    :commands ["echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT; echo Second result."]}])

(defn- make-runner
  [dir]
  (agent/runner {:db (atom {})
                 :dir (io/file dir ".agent")
                 :scripted script
                 :config-fn #(agent/config {:model "openrouter/test/model"
                                            :cwd dir
                                            :timeout-seconds 60
                                            :cost-limit 5.0})}))

(defn- await-status
  "Waits up to `ms` for the status to be one of `statuses` and returns the
  status."
  [runner statuses ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (if (or (statuses (agent/status runner))
              (< deadline (System/currentTimeMillis)))
        (agent/status runner)
        (do (Thread/sleep 20)
            (recur))))))

(defn- await-running-command
  [runner ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (while (and (nil? @(:running runner))
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 20))
    (some? @(:running runner))))

(defn- last-observed
  [log]
  (last (filter #(= :actions/observed (:type %)) log)))

(deftest conversation-test
  (let [dir (temp-dir)
        runner (make-runner dir)
        db (:db runner)]
    (testing "the first message starts the agent, which hands over"
      (is (true? (agent/send! runner "Say hello.")))
      (is (= :waiting (await-status runner #{:waiting :stopped} 10000)))
      (let [log (:agent/log @db)]
        (is (= "First result.\n" (log/submission log)))
        (is (= "Say hello." (log/task log)))
        (is (= [:step :result :step :handover] (map :kind (feed/items log))))
        (is (= "hello\n" (:output (first (:outputs (first (filter #(= :actions/observed (:type %)) log)))))))))
    (testing "the log is saved after every step"
      (is (= (:agent/log @db) (edn/read-string (slurp (:log-file runner))))))
    (testing "the answer is the next task, a message while it works waits for the next step"
      (is (true? (agent/send! runner "Now wait a bit.")))
      (is (await-running-command runner 10000))
      (is (true? (agent/send! runner "Faster please.")))
      (is (= ["Faster please."] (:agent/pending @db)))
      (is (= :working (agent/status runner))))
    (testing "stop ends the running command and the run"
      (is (true? (agent/stop! runner)))
      (is (= :stopped (await-status runner #{:stopped} 5000)))
      (let [log (:agent/log @db)]
        (is (= "Stopped" (:status (log/exit log))))
        (is (= :done (log/phase log)))
        (is (re-find #"stopped by the user"
                     (:exception-info (first (:outputs (last-observed log))))))))
    (testing "continue reads the waiting message, the script goes on and hands over again"
      (is (true? (agent/resume! runner)))
      (is (= :waiting (await-status runner #{:waiting} 10000)))
      (let [log (:agent/log @db)]
        (is (= "Second result.\n" (log/submission log)))
        (is (some #(and (= :user/interrupted (:type %))
                        (= "UserMessage" (:interrupt-type %))
                        (= "Faster please." (get-in % [:message :content])))
                  log))
        (is (empty? (:agent/pending @db)))))
    (testing "stop while the agent waits"
      (is (true? (agent/stop! runner)))
      (is (= :stopped (await-status runner #{:stopped} 5000)))
      (is (= "Stopped" (:status (log/exit (:agent/log @db)))))
      (is (not (agent/stop! runner))))
    (testing "a message to a stopped agent continues the run with the message"
      (is (true? (agent/send! runner "One more thing.")))
      (is (= :waiting (await-status runner #{:waiting} 10000)))
      (let [log (:agent/log @db)]
        (is (= "The script is over.\n" (log/submission log)))
        (is (some #(= "The user added a new task: One more thing." (get-in % [:message :content])) log))))
    (agent/stop! runner)
    (await-status runner #{:stopped} 5000)))

(deftest load-test
  (let [dir (temp-dir)
        runner (make-runner dir)]
    (agent/send! runner "Say hello.")
    (is (= :waiting (await-status runner #{:waiting} 10000)))
    (testing "a new process picks the saved log up and waits for the user again"
      (let [runner2 (make-runner dir)]
        (agent/load! runner2)
        (is (= :waiting (await-status runner2 #{:waiting} 5000)))
        (is (= (:agent/log @(:db runner)) (:agent/log @(:db runner2))))
        (agent/stop! runner2)
        (await-status runner2 #{:stopped} 5000)))
    (testing "a log that stopped mid-work stays stopped"
      (let [runner3 (make-runner dir)]
        (agent/load! runner3)
        (is (= :stopped (await-status runner3 #{:waiting :stopped} 2000)))))
    (agent/stop! runner)
    (await-status runner #{:stopped} 5000)))

(deftest resumable-test
  (let [log [{:type :run/started
              :at 0
              :task "t"
              :config {:agent {:cost-limit 2.0
                               :step-limit 0}}}
             {:type :model/responded
              :at 1
              :cost 2.5
              :message {:role "assistant"
                        :content "x"}
              :actions [{:command "ls"}]}]
        exited (conj log {:type :run/exited
                          :at 2
                          :status "LimitsExceeded"
                          :submission ""})
        resumed (agent/resumable exited 3)]
    (testing "the exit is dropped and the limit raised by the budget of the run"
      (is (= :limits/raised (:type (peek resumed))))
      (is (= 4.5 (:cost-limit (peek resumed))))
      (is (= :execute (log/phase resumed))))
    (testing "a run within its limits only loses its exit"
      (is (= [(first log)]
             (agent/resumable [(first log) {:type :run/exited :at 2 :status "Stopped" :submission ""}] 3))))))

(deftest config-test
  (let [config (agent/config {:model "openrouter/moonshotai/kimi-k2"
                              :cwd "/tmp/x"})]
    (is (= :openai (get-in config [:model :api])))
    (is (= "moonshotai/kimi-k2" (get-in config [:model :id])))
    (is (= :ignore-errors (get-in config [:model :cost-tracking])))
    (is (= {:type "ephemeral"} (get-in config [:model :params :cache_control])))
    (is (= :yolo (get-in config [:agent :mode])))
    (is (true? (get-in config [:agent :confirm-exit])))
    (is (= 10.0 (get-in config [:agent :cost-limit])))
    (is (= "/tmp/x" (get-in config [:environment :cwd])))
    (is (re-find #"\{\{task\}\}" (get-in config [:agent :instance-template])))
    (is (= :toolcall (get-in config [:model :action-format]))))
  (testing "a model without tool calling"
    (let [config (agent/config {:model "openrouter/x/y"
                                :text-based? true})]
      (is (= :text (get-in config [:model :action-format])))
      (is (re-find #"mswea_bash_command" (get-in config [:agent :system-template]))))))
