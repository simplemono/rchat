(ns rchat.repl-test
  (:require [clojure.java.io :as io]
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
