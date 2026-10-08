(ns rchat.feed-test
  (:require [clojure.test :refer [deftest is testing]]
            [rchat.feed :as feed]))

(def log
  [{:type :run/started
    :task "Say hello."
    :config {:agent {:cost-limit 20.0}}}
   {:type :prompt/rendered
    :message {:role "system"
              :content "You help."}}
   {:type :model/responded
    :cost 0.25
    :message {:role "assistant"
              :content [{:type "thinking"
                         :thinking ""}
                        {:type "text"
                         :text "Looking at the folder."}
                        {:type "tool_use"
                         :id "toolu_1"
                         :name "bash"
                         :input {:command "look a.png"}}]}
    :actions [{:command "look a.png"
               :tool-call-id "toolu_1"}]}
   {:type :actions/observed
    :message {:role "user"
              :content [{:type "tool_result"
                         :tool_use_id "toolu_1"
                         :content [{:type "text"
                                    :text "{}"}
                                   {:type "image"
                                    :source {:type "look"
                                             :path "/work/.agent/looks/ab12.jpg"}}]}]}
    :outputs [{:output "a.png [[look:/work/.agent/looks/ab12.jpg]]\n"
               :returncode 0
               :exception-info ""}]}
   {:type :actions/observed
    :message {:role "user"
              :content [{:type "tool_result"
                         :tool_use_id "toolu_2"
                         :content "{}"}]}
    :outputs [{:output "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\nFirst result.\n"
               :returncode 0
               :exception-info ""}]}
   {:type :user/interrupted
    :interrupt-type "UserNewTask"
    :message {:role "user"
              :content "The user added a new task: Make it shorter."}}])

(deftest items-test
  (is (= [{:kind :user
           :index 0
           :text "Say hello."}
          {:kind :step
           :index 2
           :text "Looking at the folder."
           :commands ["look a.png"]}
          {:kind :result
           :index 3
           :outputs [{:output "a.png [[look:/work/.agent/looks/ab12.jpg]]\n"
                      :returncode 0
                      :exception-info ""}]
           :looks ["/looks/ab12.jpg"]}
          {:kind :handover
           :index 4
           :text "First result."}
          {:kind :user
           :index 5
           :text "Make it shorter."}]
         (feed/items log))))

(deftest grouped-test
  (is (= [{:kind :user
           :index 0
           :text "Say hello."}
          {:kind :step
           :index 2
           :text "Looking at the folder."
           :commands ["look a.png"]
           :outputs [{:output "a.png [[look:/work/.agent/looks/ab12.jpg]]\n"
                      :returncode 0
                      :exception-info ""}]
           :looks ["/looks/ab12.jpg"]}
          {:kind :handover
           :index 4
           :text "First result."}
          {:kind :user
           :index 5
           :text "Make it shorter."}]
         (feed/grouped (feed/items log))))
  (testing "a result without a step before it stays on its own"
    (is (= [{:kind :result :outputs [] :looks []}]
           (feed/grouped [{:kind :result :outputs [] :looks []}]))))
  (testing "the images the user attached join the message before them"
    (is (= [{:kind :user :index 0 :text "Look." :looks ["/looks/a.png"]}]
           (feed/grouped [{:kind :user :index 0 :text "Look."}
                          {:kind :user-looks :index 3 :looks ["/looks/a.png"]}])))
    (is (= {:kind :user :index 1 :text "Steered." :looks ["/looks/b.png"]}
           (last (feed/items [{:type :run/started :task "x"}
                              {:type :user/interrupted
                               :interrupt-type "UserMessage"
                               :message {:role "user"
                                         :content [{:type "text" :text "Steered."}
                                                   {:type "image"
                                                    :source {:type "look"
                                                             :path "/work/.agent/looks/b.png"}}]}}]))))))

(deftest status-text-test
  (is (= "Ready" (feed/status-text {:agent/status :idle})))
  (is (= "Running: look a.png"
         (feed/status-text {:agent/status :working
                            :agent/log (subvec log 0 3)})))
  (is (= "Thinking"
         (feed/status-text {:agent/status :working
                            :agent/log log})))
  (is (= "Stopping"
         (feed/status-text {:agent/status :working
                            :agent/stopping? true
                            :agent/log log})))
  (is (= "Waiting for you" (feed/status-text {:agent/status :waiting})))
  (testing "why it stopped"
    (is (= "Stopped: the budget of $20 is used up"
           (feed/status-text {:agent/status :stopped
                              :agent/log (conj log {:type :run/exited
                                                    :status "LimitsExceeded"})})))
    (is (= "Stopped"
           (feed/status-text {:agent/status :stopped
                              :agent/log (conj log {:type :run/exited
                                                    :status "Stopped"})})))
    (is (= "Stopped: RuntimeException"
           (feed/status-text {:agent/status :stopped
                              :agent/log (conj log {:type :run/exited
                                                    :status "RuntimeException"})})))))
