(ns rchat.markdown-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [rchat.markdown :as markdown]
            [rframes.transit :as transit]))

(deftest inline-test
  (is (= [[:p "Built " [:strong "index.html"] ", " [:em "soon"] ", " [:code "cat x"] " and "
           [:a {:href "http://x.y" :target "_blank" :rel "noopener"} "docs"] "."]]
         (markdown/children "Built **index.html**, _soon_, `cat x` and [docs](http://x.y)."))))

(deftest blocks-test
  (is (= [[:p "One."]
          [:p "Two"]
          [:ul [:li "a " [:strong "b"]] [:li "c"]]
          [:ol {:start 3} [:li "x"]]
          [:blockquote [:p "q"]]
          [:pre [:code "(+ 1 2)\n"]]
          [:h2 "Head"]
          [:p "line" [:br] "break soft"]]
         (markdown/children (str "One.\n\nTwo\n\n- a **b**\n- c\n\n3. x\n\n> q\n\n"
                                 "```clj\n(+ 1 2)\n```\n\n## Head\n\nline  \nbreak\nsoft")))))

(deftest no-markup-from-the-model-test
  (testing "raw HTML is text"
    (is (= [[:p "<script>alert(1)</script>"]
            [:p "a <b>bold</b> b"]]
           (markdown/children "<script>alert(1)</script>\n\na <b>bold</b> b"))))
  (testing "an image is a link, the page loads nothing the model chose"
    (is (= [[:p [:a {:href "http://i/x.png" :target "_blank" :rel "noopener"} "alt"]]]
           (markdown/children "![alt](http://i/x.png)")))))

(deftest plain-text-test
  (is (= [[:p "snake_case_name and a * star, 0:21.5"]]
         (markdown/children "snake_case_name and a * star, 0:21.5")))
  (is (= [] (markdown/children "")))
  (is (= [] (markdown/children nil))))

(deftest leaf-test
  (testing "the app's leaf function runs inside the markdown"
    (is (= [[:p "At " [:ui/time {:ui/t 21.5} "0:21.5"] " the " [:strong "card"]]]
           (markdown/children "At 0:21.5 the **card**"
                              {:leaf (fn [text]
                                       (if (= "At 0:21.5 the " text)
                                         (list "At " [:ui/time {:ui/t 21.5} "0:21.5"] " the ")
                                         (list text)))}))))
  (testing "inline code is left alone"
    (is (= [[:p [:code "x"]]]
           (markdown/children "`x`" {:leaf (fn [_] (list "changed"))})))))

(deftest data-test
  (let [children (markdown/children "**a** [b](http://c) `d`\n\n- e\n\n```\nf\n```\n\n| a |\n|---|\n| 1 |")
        found (atom [])]
    (is (seq? children))
    (walk/postwalk (fn [x]
                     (when (fn? x)
                       (swap! found conj x))
                     x)
                   children)
    (is (empty? @found))
    (is (= children (transit/read-str (transit/write-str children))))))
