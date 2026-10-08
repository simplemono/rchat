(ns rchat.env
  "The `:env/execute` effect of a chat agent: mini-swe-agent's local
  environment (every command in a fresh `bash -c`, stdout and stderr
  combined, a timeout) plus a handle on the running command, so that a stop
  from the user ends a command that is still running."
  (:require [clojure.java.io :as io]))

(defn- kill-tree!
  [process]
  (doseq [child (.toList (.descendants process))]
    (.destroyForcibly child))
  (.destroyForcibly process)
  (.waitFor process))

(defn- start!
  [{:keys [cwd env]} command output-file]
  (let [builder (doto (ProcessBuilder. ["bash" "-c" command])
                  (.directory (io/file (if (seq cwd)
                                         cwd
                                         (System/getProperty "user.dir"))))
                  (.redirectErrorStream true)
                  (.redirectOutput output-file))]
    (doseq [[k v] env]
      (.put (.environment builder) (name k) (str v)))
    (let [process (.start builder)]
      ;; No input: a command that reads stdin gets EOF instead of hanging.
      (.close (.getOutputStream process))
      process)))

(defn kill!
  "Ends the command that runs right now, if any."
  [{:keys [running]}]
  (when-let [process @running]
    (reset! running nil)
    (kill-tree! process)))

(defn execute
  "Runs `command` and returns `{:output :returncode :exception-info}`. The
  process is kept in the `:running` atom of the runner while it runs, and
  `kill!` ends it; a stop requested before the command started ends it as
  well."
  [{:keys [running stop?]}
   {:keys [timeout-seconds] :or {timeout-seconds 30} :as env-config}
   command]
  (let [output-file (java.io.File/createTempFile "rchat" ".out")]
    (try
      (let [process (start! env-config command output-file)]
        (reset! running process)
        (when @stop?
          (kill! {:running running}))
        (let [finished? (.waitFor process timeout-seconds java.util.concurrent.TimeUnit/SECONDS)
              killed? (nil? @running)]
          (reset! running nil)
          (cond
            killed?
            {:output (slurp output-file)
             :returncode -1
             :exception-info "The command was stopped by the user."}

            finished?
            {:output (slurp output-file)
             :returncode (.exitValue process)
             :exception-info ""}

            :else
            (do
              (kill-tree! process)
              {:output (slurp output-file)
               :returncode -1
               :exception-info (str "An error occurred while executing the command: Command '"
                                    command "' timed out after " timeout-seconds " seconds")}))))
      (catch java.io.IOException e
        {:output ""
         :returncode -1
         :exception-info (str "An error occurred while executing the command: " (ex-message e))})
      (finally
        (.delete output-file)))))

(comment
  (execute {:running (atom nil) :stop? (atom false)} {} "echo hello && ls /nope")
  (execute {:running (atom nil) :stop? (atom false)} {:timeout-seconds 1} "sleep 5")
  )
