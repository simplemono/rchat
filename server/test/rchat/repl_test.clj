(ns rchat.repl-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [minisweagent.log :as log]
            [rchat.agent :as agent]
            [rchat.feed :as feed]
            [rchat.repl :as repl]
            [rchat.scripted :as scripted]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "rchat-repl"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(def script
  [(str ";; A heading with a slider.\n"
        "(design! assoc :title \"Hello\")\n"
        "(defn design-view [design] [:h1 {:style {:font-size (str (:size design 24) \"px\")}} (:title design)])\n"
        "(+ 1 2)")
   "(done \"A heading is on your canvas.\")"
   ";; Changing the title.\n(design! assoc :title \"Changed\")\n(unbalanced"
   "(done \"Title changed.\")"])

(defn- make-runner
  [dir db]
  (agent/runner {:db db
                 :dir (io/file dir ".agent")
                 :step repl/step
                 :model/query (scripted/text-query-fn script)
                 :config-fn #(agent/config {:model "openrouter/test/model"
                                            :cwd dir
                                            :text-based? true
                                            :system-template (slurp (io/resource "rchat/prompts/repl-system.md"))
                                            :instance-template (slurp (io/resource "rchat/prompts/repl-task.md"))})}))

(defn- await-status
  [runner statuses ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (if (or (statuses (agent/status runner))
              (< deadline (System/currentTimeMillis)))
        (agent/status runner)
        (do (Thread/sleep 20)
            (recur))))))

(deftest repl-conversation-test
  (let [db (atom {:design {}})
        runner (make-runner (temp-dir) db)]
    (repl/install! (merge (repl/design-helpers db) {'db db}))
    (testing "the forms run in this process, the creation appears, the model hands over"
      (is (true? (agent/send! runner "A heading, please.")))
      (is (= :waiting (await-status runner #{:waiting :stopped} 10000)))
      (is (= "Hello" (get-in @db [:design :title])))
      (is (= 3 @(ns-resolve 'user '$3)))
      (is (fn? @(ns-resolve 'user 'design-view)))
      (is (= [:h1 {:style {:font-size "24px"}} "Hello"] (@(ns-resolve 'user 'design-view) (:design @db))))
      (testing "a design! returns the design, not the app"
        (is (re-find #"\$1 => \{:title \"Hello\"\}" (get-in (first (filter #(= :actions/observed (:type %)) (:agent/log @db))) [:message :content]))))
      (let [log (:agent/log @db)]
        (is (= "A heading is on your canvas." (log/submission log)))
        (is (= [:user :step :result :step :handover] (map :kind (feed/items log))))
        (is (= "A heading with a slider." (:text (second (feed/items log)))))
        (is (re-find #"\$3 => 3" (get-in (nth (filter #(= :actions/observed (:type %)) log) 0) [:message :content])))))
    (testing "the source of the creation is in the log"
      (is (str/starts-with? (repl/source-of (:agent/log @db) "design-view") "(defn design-view [design] [:h1")))
    (testing "the answer is the next task; a reader error is reported, the forms before it ran"
      (is (true? (agent/send! runner "Change the title.")))
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (while (and (not= "Title changed." (log/submission (:agent/log @db)))
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 20)))
      (is (= :waiting (await-status runner #{:waiting :stopped} 5000)))
      (is (= "Changed" (get-in @db [:design :title])))
      (let [log (:agent/log @db)
            outputs (mapcat :outputs (filter #(= :actions/observed (:type %)) log))]
        (is (some #(and (= 1 (:returncode %)) (re-find #"Reader error" (:output %))) outputs))
        (is (= "Title changed." (log/submission log)))))
    (agent/stop! runner)
    (await-status runner #{:stopped} 5000)))

(def replay-script
  [(str ";; Build.\n"
        "(design! assoc :title \"Hello\")\n"
        "(def pad 12)\n"
        "(def broken (/ 1 0))\n"
        "(defn design-view [design] [:h1 {:style {:padding pad}} (:title design)])\n"
        "(set-command! :design/shout (fn [w] w))\n"
        "(sh \"true\")")
   "(done \"Built.\")"
   ";; Nothing to change.\n(+ 1 1)"
   "(done \"Nothing changed.\")"
   "(design! assoc :f (fn [] 1))"
   "(done \"A function is in the design now.\")"])

(defn- harness-entries
  [log]
  (filter :harness log))

(deftest replay-test
  (let [dir (temp-dir)
        db (atom {:design {}})
        commands (atom {})
        runner (agent/runner {:db db
                              :dir (io/file dir ".agent")
                              :step repl/step
                              :harness/snapshot (fn [] (:design @db))
                              :model/query (scripted/text-query-fn replay-script)
                              :config-fn #(agent/config {:model "openrouter/test/model"
                                                         :cwd dir
                                                         :text-based? true
                                                         :system-template (slurp (io/resource "rchat/prompts/repl-system.md"))
                                                         :instance-template (slurp (io/resource "rchat/prompts/repl-task.md"))})})]
    (repl/install! (merge (repl/design-helpers db)
                          {'db db
                           'set-command! (fn [kind f] (swap! commands assoc kind f) kind)}))
    (testing "the evaluation snapshots the design into the log, outside the message"
      (is (true? (agent/send! runner "Build it.")))
      (is (= :waiting (await-status runner #{:waiting :stopped} 10000)))
      (let [entries (harness-entries (:agent/log @db))]
        (is (= 1 (count entries)))
        (is (= :actions/observed (:type (first entries))))
        (is (= {:title "Hello"} (:harness (first entries))))
        (is (not (str/includes? (get-in (first entries) [:message :content]) ":harness")))))
    (testing "the user's control changes are snapshotted with their next message, an unchanged design is not"
      (swap! db assoc-in [:design :size] 30)
      (is (true? (agent/send! runner "Next.")))
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (while (and (not= "Nothing changed." (log/submission (:agent/log @db)))
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 20)))
      (is (= :waiting (await-status runner #{:waiting :stopped} 5000)))
      (let [entries (harness-entries (:agent/log @db))]
        (is (= 2 (count entries)))
        (is (= :user/interrupted (:type (second entries))))
        (is (= {:title "Hello" :size 30} (:harness (second entries))))))
    (testing "a design that does not survive EDN is not snapshotted, the log stays readable"
      (is (true? (agent/send! runner "Put a function in.")))
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (while (and (not= "A function is in the design now." (log/submission (:agent/log @db)))
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 20)))
      (is (= :waiting (await-status runner #{:waiting :stopped} 5000)))
      (is (fn? (get-in @db [:design :f])))
      (is (= 2 (count (harness-entries (:agent/log @db)))))
      (is (vector? (edn/read-string (slurp (io/file dir ".agent" "log.edn"))))))
    (agent/stop! runner)
    (await-status runner #{:stopped} 5000)
    (testing "after a restart, replay! rebuilds what ran without error and restores the last snapshot"
      (let [log (:agent/log @db)]
        (doseq [sym '[pad broken design-view]]
          (ns-unmap 'user sym))
        (reset! db {:design {}})
        (reset! commands {})
        (let [{:keys [replayed failed]} (repl/replay! log (fn [design] (swap! db assoc :design design)))]
          (is (= 3 replayed))
          (is (= [] failed)))
        (is (= 12 @(ns-resolve 'user 'pad)))
        (is (nil? (ns-resolve 'user 'broken)))
        (is (= [:h1 {:style {:padding 12}} "Hello"] (@(ns-resolve 'user 'design-view) {:title "Hello"})))
        (is (fn? (:design/shout @commands)))
        (is (= {:title "Hello" :size 30} (:design @db)))))
    (testing "an empty log replays nothing"
      (is (= {:replayed 0 :failed []} (repl/replay! nil nil))))))

(deftest install-frees-clojure-repl-names-test
  (repl/install! {})
  (testing "source, doc and dir are the model's to define"
    (is (= 1 (binding [*ns* (the-ns 'user)]
               (eval '(do (def source 1) source)))))
    (is (= 2 (binding [*ns* (the-ns 'user)]
               (eval '(do (def doc 2) doc)))))))

(deftest note-test
  (is (= "A heading with a slider.\nAnd a color." (repl/note ";; A heading with a slider.\n(+ 1 2)\n; And a color.\n(done \"x\")")))
  (is (= "" (repl/note "(+ 1 2)"))))

(deftest definitions-test
  (let [log [{:type :run/started :task "x"}
             {:type :model/responded
              :actions [{:command "(design! assoc :title \"Hi\")"}
                        {:command "(def pad 12)"}
                        {:command "(defn design-view [design t] [:h1 (:title design)])"}
                        {:command "(+ 1 2)"}]}
             {:type :model/responded
              :actions [{:command "(defn- helper [x] x)"}
                        {:command "(done \"ok\")"}]}]]
    (is (= ["(def pad 12)"
            "(defn design-view [design t] [:h1 (:title design)])"
            "(defn- helper [x] x)"]
           (repl/definitions log)))))

(deftest install-aliases-test
  (repl/install! {})
  (testing "the model can use fnmotion under its aliases"
    (is (= 50.0 (binding [*ns* (the-ns 'user)]
                  (eval '(fm/interpolate 1.5 [1 2] [0 100])))))
    (is (= [:p "x"] (binding [*ns* (the-ns 'user)]
                      (eval '(tl/at (tl/still 2 [:p "x"]) 1)))))))

(deftest render-result-test
  (is (= "$1 => 3\n" (repl/render-result 1 {:value 3 :out ""})))
  (is (= "hi\n$2 => nil\n" (repl/render-result 2 {:value nil :out "hi\n"})))
  (is (re-find #"\"error\" \"clojure.lang.ExceptionInfo\"|:error \"clojure.lang.ExceptionInfo\""
               (repl/render-result 3 {:error (ex-info "boom" {:a 1}) :out ""}))))
