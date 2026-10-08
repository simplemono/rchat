(ns example.bench
  "What a server-pushed frame costs: the page rendered at 600 times over
  the six-second scripted clip, encoded as rframes does (transit, then
  gzip against the frame before), with the time per frame and the bytes
  per frame.

      RCHAT_MOTION=server bb -m example.bench"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [example.main :as main]
            [rchat.repl :as repl]
            [rframes.replicant :as replicant]
            [rframes.sse :as sse]))

(defn- evaluate-script-step!
  "Evaluates the forms of the scripted model's message `index` in user."
  [steps index]
  (binding [*ns* (the-ns 'user)]
    (doseq [form (:forms (repl/read-forms (nth steps index)))]
      (eval form))))

(defn -main
  [& _args]
  (repl/install! (merge (repl/design-helpers main/db)
                        {'db main/db
                         'set-command! main/set-command!}))
  (let [steps (edn/read-string (slurp (io/resource "repl-script.edn")))]
    ;; the card (step 0) and the clip (step 4) of the script
    (evaluate-script-step! steps 0)
    (evaluate-script-step! steps 4))
  (swap! main/db assoc :agent/status :waiting :agent/log [])
  (let [encode (sse/gzip-encoder)
        duration (double (get-in @main/db [:design :duration]))
        render (fn [t]
                 (swap! main/db assoc :motion/t t)
                 (replicant/frame-event (main/page-view {})))
        n 600
        _ (encode (render 0.0))
        started (System/nanoTime)
        sizes (vec (for [i (range n)]
                     (let [frame (render (* duration (/ i (double n))))]
                       [(count frame) (count (encode frame))])))
        ms (/ (- (System/nanoTime) started) 1e6)
        transit-bytes (double (/ (reduce + (map first sizes)) n))
        gzip-bytes (double (/ (reduce + (map second sizes)) n))]
    (println (format "mode %s, %d frames over %.1f s of video" (name main/motion-mode) n duration))
    (println (format "render + encode: %.0f ms total, %.2f ms per frame" ms (/ ms n)))
    (println (format "transit per frame: %.0f bytes; gzip against the previous frame: %.0f bytes" transit-bytes gzip-bytes))
    (println (format "at 60 fps: %.0f kB/s per watching browser, %.0f%% of one core on this machine"
                     (/ (* 60 gzip-bytes) 1000.0)
                     (* 100 (/ (* 60 (/ ms n)) 1000.0))))
    (System/exit 0)))
