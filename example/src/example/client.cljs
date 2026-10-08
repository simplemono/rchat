(ns example.client
  "The browser bundle: the rframes runtime with the composer of rchat."
  (:require [rchat.client :as rchat]
            [rframes.client :as rframes]))

(defn init!
  []
  (rframes/init! {:register rchat/register}))
