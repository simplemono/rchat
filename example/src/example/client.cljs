(ns example.client
  "The browser bundle: the rframes runtime with the composer and the
  canvas of rchat, plus the motion pane for the browser mode."
  (:require [rchat.client :as rchat]
            [rchat.client.motion :as motion]
            [rframes.client :as rframes]))

(defn init!
  []
  (rframes/init! {:register (concat rchat/register motion/register)}))
