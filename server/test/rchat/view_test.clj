(ns rchat.view-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [rchat.feed-test :as feed-test]
            [rchat.view :as view]
            [rframes.transit :as transit]))

(defn- functions
  [view]
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when (fn? x)
                       (swap! found conj x))
                     x)
                   view)
    @found))

(defn- nodes
  "Every hiccup node of a view."
  [view]
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when (and (vector? x) (keyword? (first x)))
                       (swap! found conj x))
                     x)
                   view)
    @found))

(defn- button-labels
  [view]
  (->> (nodes view)
       (filter #(re-matches #"button(\..*)?" (name (first %))))
       (map last)))

(deftest page-is-data-test
  (doseq [db [{:agent/log []
               :agent/status :idle}
              {:agent/log feed-test/log
               :agent/status :waiting
               :agent/pending ["Make it shorter."]}
              {:agent/log feed-test/log
               :agent/status :working
               :agent/stopping? true}
              {:agent/log (conj feed-test/log {:type :run/exited
                                               :status "RuntimeException"
                                               :exception "boom"})
               :agent/status :stopped}]]
    (let [view (view/page db {:title "test"
                              :card [:p "a card"]
                              :text-fn (fn [text] (list [:em text]))})]
      (testing "a frame holds no functions and survives transit"
        (is (empty? (functions view)))
        (is (= view (transit/read-str (transit/write-str view))))))))

(deftest composer-test
  (testing "the buttons follow the status"
    (is (= [] (button-labels (view/composer {:agent/status :idle} {}))))
    (is (= "Start" (:ui/send-label (second (first (filter #(= :ui/composer (first %))
                                                           (nodes (view/composer {} {}))))))))
    (is (= ["Stop"] (button-labels (view/composer {:agent/status :working} {}))))
    (is (= ["Stopping…"] (button-labels (view/composer {:agent/status :working
                                                        :agent/stopping? true} {}))))
    (is (= ["Continue"] (button-labels (view/composer {:agent/status :stopped} {})))))
  (testing "the composer alias carries the send action and the draft key"
    (let [[_ attrs] (first (filter #(= :ui/composer (first %))
                                   (nodes (view/composer {:agent/status :idle} {}))))]
      (is (= :rchat/draft (:ui/store-key attrs)))
      (is (= "Start" (:ui/send-label attrs)))
      (is (= view/send-actions (:ui/send attrs))))))

(deftest text-fn-test
  (let [view (view/feed {:agent/log feed-test/log
                         :agent/status :waiting}
                        {:text-fn (fn [text] (list [:strong text]))})]
    (is (some #(= [:strong "First result."] %) (nodes view)))))
