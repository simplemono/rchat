(ns rchat.upload-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [rchat.upload :as upload]
            [rframes.transit :as transit]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "rchat-upload"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- request
  [content-type ^bytes bytes]
  {:ring/request {:request-method :post
                  :uri "/upload"
                  :headers {"content-type" content-type}
                  :body (java.io.ByteArrayInputStream. bytes)}})

(deftest handler-test
  (let [looks-dir (temp-dir)
        runner {:looks-dir looks-dir}
        png (.getBytes "not really a png" "UTF-8")]
    (testing "an image is stored by its content and answered with its name and URL"
      (let [{:keys [status body]} (:ring/response (upload/handler runner (request "image/png; charset=binary" png)))
            {:keys [name url]} (transit/read-str body)]
        (is (= 200 status))
        (is (re-matches #"[0-9a-f]{32}\.png" name))
        (is (= (str "/looks/" name) url))
        (is (.isFile (io/file looks-dir name)))
        (testing "the same bytes again are the same file"
          (is (= name (:name (transit/read-str (:body (:ring/response (upload/handler runner (request "image/png" png)))))))))))
    (testing "only images"
      (is (= 415 (:status (:ring/response (upload/handler runner (request "text/plain" png)))))))
    (testing "not empty, not huge"
      (is (= 400 (:status (:ring/response (upload/handler runner (request "image/jpeg" (byte-array 0)))))))
      (is (= 413 (:status (:ring/response (upload/handler runner (request "image/jpeg" (byte-array (inc upload/max-bytes)))))))))))
