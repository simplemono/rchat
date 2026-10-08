(ns example.main
  "The REPL agent, the default example: the creation lives in this process. The model defines
  `user/design-view`, a function of the design data to hiccup, which the
  page renders beside the chat; it keeps the data under [:design] of the
  one app atom; and the controls it puts into the view send `:design/set`,
  so the user plays with the creation and sees every change at once.

      bb -m example.main

  Without an API key, the texts in resources/repl-script.edn play the
  model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [org.httpkit.server :as server]
            [rchat.agent :as agent]
            [rchat.auth :as auth]
            [rchat.repl :as repl]
            [rchat.scripted :as scripted]
            [rchat.view :as view]
            [rframes.command :as command]
            [rframes.http :as http]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(defonce db
  (atom {:design {}}))

;; Commands the model adds with `set-command!`: kind -> fn of the world.
(defonce commands
  (atom {}))

(def work-dir
  "Where the agent works: RCHAT_WORK, or ./work."
  (.getAbsolutePath (io/file (or (System/getenv "RCHAT_WORK") "work"))))

(def api-key?
  (boolean (or (System/getenv "OPENROUTER_API_KEY")
               (System/getenv "ANTHROPIC_API_KEY"))))

(def model
  (or (System/getenv "RCHAT_MODEL")
      (if (and (System/getenv "ANTHROPIC_API_KEY")
               (not (System/getenv "OPENROUTER_API_KEY")))
        "anthropic/claude-sonnet-5"
        agent/default-model)))

(def scripted
  (when-not api-key?
    (edn/read-string (slurp (io/resource "repl-script.edn")))))

(defn set-command!
  "Adds a command the UI can send: `(set-command! :design/shuffle (fn [w] ... w))`."
  [kind f]
  (swap! commands assoc kind f)
  kind)

(defonce runner
  (agent/runner (cond-> {:db db
                         :dir (io/file work-dir ".agent")
                         :step repl/step
                         :config-fn #(agent/config {:model model
                                                    :cwd work-dir
                                                    :cost-limit 5.0
                                                    :text-based? true
                                                    :system-template (slurp (io/resource "rchat/prompts/repl-system.md"))
                                                    :instance-template (slurp (io/resource "rchat/prompts/repl-task.md"))})}
                  scripted (assoc :model/query (scripted/text-query-fn scripted)))))

(def key-missing?
  (agent/api-key-missing? runner))

;;; The design: its data, its command, its view

(defn- coerce
  "A slider or a number field sends text; a number stays a number."
  [current value]
  (if (and (number? current) (string? value))
    (or (parse-long value) (parse-double value) value)
    value))

(defn- design-set
  [w]
  (let [{:keys [path value]} (command/command-data w)
        path (when (and (vector? path) (seq path))
               (into [:design] path))]
    (if path
      (do (swap! db (fn [db]
                      (assoc-in db path (coerce (get-in db path) value))))
          (command/accepted w))
      (command/rejected w :not-a-design-path))))

(defn- data-only
  "A view the model wrote may hold a function by mistake; a function would
  drop the whole frame. It becomes nothing."
  [hiccup]
  (walk/postwalk (fn [x] (if (fn? x) nil x)) hiccup))

(defn- design-view
  []
  (some-> (ns-resolve 'user 'design-view) deref))

(defn design-pane
  "The creation: a motion design (the design has a :duration) is rendered
  by the browser from the model's definitions and played there; anything
  else is rendered here from `design-view`."
  [db]
  (let [view-fn (design-view)
        {:keys [duration] :as design} (:design db)
        source (repl/source-of (:agent/log db) "design-view")]
    [:div.canvas-pane
     [:div.canvas-bar
      [:span.label "Your creation"]
      (when (and view-fn duration)
        [:span.hint (str duration " s")])]
     (cond
       (and view-fn duration)
       [:ui/motion {:ui/source (str/join "\n" (repl/definitions (:agent/log db)))
                    :ui/design design
                    :ui/duration duration}]

       view-fn
       [:div.design
        (try
          (data-only (view-fn design))
          (catch Exception e
            [:p.error (str "The view failed: " (ex-message e))]))]

       :else
       [:div.canvas-placeholder
        "Your creation will appear here, with controls to play with it."])
     (when source
       [:details.work
        [:summary "The code of your creation"]
        [:pre source]])]))

(defn page-view
  [_w]
  (let [db @db]
    (view/page db {:title "rchat · repl"
                   :key-missing? key-missing?
                   :card (design-pane db)})))

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
    :render/fn #'page-view}
   {:command/kind :design/set
    :command/fn #'design-set}])

(defn get-register
  []
  (replicant/expand (concat register
                            command/register
                            (agent/register runner)
                            (for [[kind f] @commands]
                              {:command/kind kind
                               :command/fn f}))))

(defn -main
  [& _args]
  (.mkdirs (io/file work-dir))
  (repl/install! (merge (repl/design-helpers db)
                        {'db db
                         'set-command! set-command!
                         'frame-at (fn [t]
                                     (when-let [view-fn (design-view)]
                                       (view-fn (:design @db) t)))}))
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
                    ", the model is the script repl-script.edn"
                    (str ", the model is " model)))))
  @(promise))
