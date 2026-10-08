(ns example.main
  "A chat with an agent that works in ./work. Without an API key, the
  script in script.edn stands in for the model."
  (:require [clojure.java.io :as io]
            [org.httpkit.server :as server]
            [rchat.agent :as agent]
            [rchat.auth :as auth]
            [rchat.view :as view]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(defonce db
  (atom {}))

(def work-dir
  (.getAbsolutePath (io/file "work")))

(def api-key?
  (boolean (or (System/getenv "OPENROUTER_API_KEY")
               (System/getenv "ANTHROPIC_API_KEY"))))

(def model
  "provider/id as in mini-swe-agent-clj. RCHAT_MODEL overrides the default,
  GPT-6.1 Sol through OpenRouter."
  (or (System/getenv "RCHAT_MODEL")
      (if (and (System/getenv "ANTHROPIC_API_KEY")
               (not (System/getenv "OPENROUTER_API_KEY")))
        "anthropic/claude-sonnet-5"
        agent/default-model)))

(def scripted
  "The script that plays the model: RCHAT_SCRIPT, or script.edn when there
  is no API key."
  (or (System/getenv "RCHAT_SCRIPT")
      (when-not api-key?
        "script.edn")))

(defonce runner
  (agent/runner {:db db
                 :dir (io/file work-dir ".agent")
                 :scripted scripted
                 :config-fn #(agent/config {:model model
                                            :cwd work-dir
                                            :cost-limit 5.0})}))

(def key-missing?
  (agent/api-key-missing? runner))

(defn page-view
  [_w]
  (view/page @db {:title "rchat"
                  :key-missing? key-missing?}))

(def head
  (str "<title>rchat</title>"
       "<link rel=\"icon\" href=\"data:,\">"
       "<link rel=\"stylesheet\" href=\"/rchat.css\">"
       "<script defer src=\"/js/main.js\"></script>"))

(def register
  [(http/resources "/rchat.css" "rchat/chat.css")
   (http/resources "/js/*" "public/js")
   {:replicant/shim "/"
    :head head}
   {:replicant/render "/"
    :render/fn #'page-view}])

(defn get-register
  []
  (replicant/expand (concat register
                            command/register
                            (agent/register runner))))

(defn -main
  [& _args]
  (.mkdirs (io/file work-dir))
  (agent/load! runner)
  (sse/watch! db)
  (let [port (parse-long (or (System/getenv "PORT") "8080"))
        token (System/getenv "RCHAT_TOKEN")]
    (server/run-server (auth/wrap (http/ring-handler get-register) {:token token})
                       {:port port})
    (println (str "rchat example on "
                  (if token
                    (auth/link (str "http://localhost:" port) token)
                    (str "http://localhost:" port))
                  (if scripted
                    (str ", the model is the script " scripted)
                    (str ", the model is " model)))))
  @(promise))
