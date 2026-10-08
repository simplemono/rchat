(ns rchat.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rchat.auth :as auth]))

(def handler
  (auth/wrap (fn [_request]
               {:status 200
                :body "ok"})
             {:token "s3cret"}))

(deftest wrap-test
  (testing "without the cookie the page asks for the link"
    (is (= 403 (:status (handler {:uri "/"
                                  :headers {}})))))
  (testing "the link sets the cookie and redirects to the path without it"
    (let [{:keys [status headers]} (handler {:uri "/"
                                             :query-string "token=s3cret&x=1"
                                             :headers {}})
          cookie (first (str/split (get headers "Set-Cookie") #";"))]
      (is (= 302 status))
      (is (= "/" (get headers "Location")))
      (is (str/starts-with? cookie "rchat="))
      (is (not (str/includes? cookie "s3cret")))
      (testing "the cookie is the proof from then on"
        (is (= 200 (:status (handler {:uri "/looks/a.jpg"
                                      :headers {"cookie" (str "other=1; " cookie)}})))))))
  (testing "a wrong token or cookie is refused"
    (is (= 403 (:status (handler {:uri "/"
                                  :query-string "token=wrong"
                                  :headers {}}))))
    (is (= 403 (:status (handler {:uri "/"
                                  :headers {"cookie" "rchat=0000"}})))))
  (testing "the cookie is Secure behind https"
    (is (str/includes? (get-in (handler {:uri "/"
                                         :query-string "token=s3cret"
                                         :headers {"x-forwarded-proto" "https"}})
                               [:headers "Set-Cookie"])
                       "; Secure")))
  (testing "no token, no auth"
    (is (= 200 (:status ((auth/wrap (fn [_] {:status 200}) {:token nil})
                         {:uri "/"
                          :headers {}}))))))

(deftest link-test
  (is (= "http://localhost:8080/?token=a+b%26c" (auth/link "http://localhost:8080" "a b&c"))))
