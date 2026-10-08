(ns rchat.canvas-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [rchat.canvas :as canvas]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "rchat-canvas"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- nodes
  [view]
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when (and (vector? x) (keyword? (first x)))
                       (swap! found conj x))
                     x)
                   view)
    @found))

(deftest refresh-test
  (let [dir (temp-dir)
        db (atom {})
        c (canvas/canvas {:db db
                          :dir dir})]
    (testing "nothing to show yet"
      (is (= {:canvas/present? false
              :canvas/rev nil}
             (canvas/refresh! c)))
      (is (some #(= :div.canvas-placeholder (first %)) (nodes (canvas/view @db c {}))))
      (is (not-any? #(= :ui/canvas (first %)) (nodes (canvas/view @db c {})))))
    (testing "the page appears with the time of the newest file as its revision"
      (spit (io/file dir "index.html") "<h1>hi</h1>")
      (.setLastModified (io/file dir "index.html") 1000000)
      (let [{:keys [canvas/present? canvas/rev]} (canvas/refresh! c)]
        (is (true? present?))
        (is (= 1000000 rev))
        (is (some #(= [:ui/canvas {:ui/src "/canvas/index.html" :ui/rev 1000000}] %)
                  (nodes (canvas/view @db c {}))))))
    (testing "a change of any file bumps the revision, a dot file does not"
      (.mkdirs (io/file dir "img"))
      (spit (io/file dir "img" "a.css") "x")
      (.setLastModified (io/file dir "img" "a.css") 2000000)
      (is (= 2000000 (:canvas/rev (canvas/refresh! c))))
      (spit (io/file dir ".agent-note") "x")
      (.setLastModified (io/file dir ".agent-note") 3000000)
      (is (= 2000000 (:canvas/rev (canvas/refresh! c)))))))

(defn- get-response
  [c subpath]
  (let [{:keys [ring/handler]} (first (canvas/register c))]
    (:ring/response (handler {:ring/request {:request-method :get
                                             :uri (str "/canvas/" subpath)
                                             :headers {}}
                              :ring/route-params {:* subpath}}))))

(deftest routes-test
  (let [dir (temp-dir)
        c (canvas/canvas {:db (atom {})
                          :dir dir
                          :prepare (fn [html] (str html "<!-- runtime -->"))})]
    (spit (io/file dir "index.html") "<h1>hi</h1>")
    (.mkdirs (io/file dir ".secret"))
    (spit (io/file dir ".secret" "key") "s3cret")
    (spit (io/file dir "style.css") "h1 {}")
    (testing "the html goes through prepare"
      (is (= "<h1>hi</h1><!-- runtime -->" (:body (get-response c "index.html"))))
      (is (= "<h1>hi</h1><!-- runtime -->" (:body (get-response c "")))))
    (testing "other files are served as they are"
      (is (= 200 (:status (get-response c "style.css"))))
      (is (= "text/css; charset=utf-8" (get-in (get-response c "style.css") [:headers "Content-Type"]))))
    (testing "dot paths and paths outside the directory are not served"
      (is (= 404 (:status (get-response c ".secret/key"))))
      (is (= 404 (:status (get-response c "../../etc/passwd"))))
      (is (= 404 (:status (get-response c "missing.html")))))
    (testing "the route is under the path of the canvas"
      (is (= [:get "/canvas/*"] (:ring/route (first (canvas/register c)))))
      (is (= [:get "/design/*"] (:ring/route (first (canvas/register (canvas/canvas {:db (atom {}) :dir dir :path "/design"})))))))))
