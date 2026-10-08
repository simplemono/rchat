(ns rchat.look-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [rchat.look :as look]))

(def looks-dir
  "/work/.agent/looks")

(def look-output
  "f.png [[look:/work/.agent/looks/ab.jpg]]\n")

(def reference
  {:type "image"
   :source {:type "look"
            :path "/work/.agent/looks/ab.jpg"}})

(defn- anthropic-observed
  [text output]
  {:type :actions/observed
   :message {:role "user"
             :content [{:type "tool_result"
                        :tool_use_id "toolu_1"
                        :content text}]}
   :outputs [{:output output
              :returncode 0}]})

(defn- openai-observed
  [output]
  {:type :actions/observed
   :message {:role "tool"
             :tool_call_id "call_1"
             :content "{}"}
   :outputs [{:output output
              :returncode 0}]})

(deftest paths-test
  (is (= ["/work/.agent/looks/ab.jpg" "/work/.agent/looks/cd.jpg"]
         (look/paths looks-dir
                     (str "a.png [[look:/work/.agent/looks/ab.jpg]]\n"
                          "b.png [[look:/work/.agent/looks/cd.jpg]]\n"
                          "a.png [[look:/work/.agent/looks/ab.jpg]]"))))
  (testing "only files of the looks folder"
    (is (empty? (look/paths looks-dir "[[look:/etc/passwd]]")))
    (is (empty? (look/paths looks-dir "[[look:/work/.agent/looks/../log.edn]]")))))

(deftest attach-anthropic-test
  (let [text "{\"returncode\":0,\"output\":\"f.png [[look:/work/.agent/looks/ab.jpg]]\\n\"}"
        log [{:type :run/started}
             (anthropic-observed text look-output)
             (anthropic-observed text look-output)]
        attached (look/attach log 2 looks-dir :anthropic 1000)]
    (testing "entries before the index stay as they are"
      (is (= (subvec log 0 2) (subvec attached 0 2))))
    (testing "the tool result keeps its text and gets the image reference"
      (is (= [{:type "text"
               :text text}
              reference]
             (get-in attached [2 :message :content 0 :content]))))
    (is (= ["/work/.agent/looks/ab.jpg"] (look/references (attached 2))))
    (testing "a tool result without a marker is unchanged"
      (let [log [(anthropic-observed "plain" "plain")]]
        (is (= log (look/attach log 0 looks-dir :anthropic 1000)))))))

(deftest attach-openai-test
  (let [log [{:type :run/started}
             (openai-observed look-output)
             (openai-observed "no image")
             (openai-observed look-output)]
        attached (look/attach log 2 looks-dir :openai 1000)]
    (testing "a tool message is text only, the images follow in a user message"
      (is (= log (subvec attached 0 4)))
      (is (= {:type :look/attached
              :at 1000
              :message {:role "user"
                        :content [{:type "text"
                                   :text "The images of your look command, in its order:"}
                                  reference]}}
             (peek attached)))
      (is (= ["/work/.agent/looks/ab.jpg"] (look/references (peek attached)))))
    (testing "only the entries of the step count: index 1 was an earlier step"
      (is (= (subvec log 0 3)
             (look/attach (subvec log 0 3) 2 looks-dir :openai 1000))))
    (testing "a handover stays the last entry"
      (let [log [{:type :run/started}
                 (openai-observed look-output)
                 (openai-observed "COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\nDone.\n")]]
        (is (= log (look/attach log 1 looks-dir :openai 1000)))))))

(deftest inline-test
  (let [file (java.io.File/createTempFile "look" ".jpg")
        reference {:type "image"
                   :source {:type "look"
                            :path (str file)}}
        tool-result (fn [block]
                      {:role "user"
                       :content [{:type "tool_result"
                                  :tool_use_id "toolu_1"
                                  :content [{:type "text"
                                             :text "frame"}
                                            block]}]})]
    (try
      (spit file "jpeg")
      (testing "Anthropic: the reference in a tool result becomes the image"
        (is (= [{:role "system"
                 :content "You help."}
                (tool-result {:type "image"
                              :source {:type "base64"
                                       :media_type "image/jpeg"
                                       :data "anBlZw=="}})]
               (look/inline :anthropic
                            [{:role "system"
                              :content "You help."}
                             (tool-result reference)]))))
      (testing "OpenAI: the reference in a user message becomes a data URL"
        (is (= [{:role "tool"
                 :tool_call_id "call_1"
                 :content "{}"}
                {:role "user"
                 :content [{:type "image_url"
                            :image_url {:url "data:image/jpeg;base64,anBlZw=="}}]}]
               (look/inline :openai
                            [{:role "tool"
                              :tool_call_id "call_1"
                              :content "{}"}
                             {:role "user"
                              :content [reference]}]))))
      (finally
        (io/delete-file file)))
    (testing "a deleted image does not break the request"
      (is (= [(tool-result {:type "text"
                            :text "[image no longer available]"})]
             (look/inline :anthropic [(tool-result reference)]))))))
