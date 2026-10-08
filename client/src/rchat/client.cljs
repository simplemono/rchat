(ns rchat.client
  "The browser side of the chat: the composer. The server names it as the
  `:ui/composer` alias and decides when it appears, this side owns what it
  renders: a textarea whose draft lives in the store, so that a new frame
  from the server does not reset what the user types, and the send button.

      [:ui/composer {:ui/store-key :rchat/draft
                     :ui/send [[:data/command ...]]
                     :ui/send-label \"Send\"
                     :ui/disabled? false
                     :placeholder \"...\"}]

  Enter sends on a keyboard, Shift+Enter breaks the line. On a touch
  device Enter breaks the line and the button sends, like every chat app
  there."
  (:require [clojure.string :as str]
            [rframes.client.state :as state]
            [rframes.client.ui :as ui]))

(defn- coarse-pointer?
  []
  (boolean (some-> (js/window.matchMedia "(pointer: coarse)")
                   (.-matches))))

(defn composer
  [{:keys [ui/store-key ui/send ui/send-label ui/disabled? ui/state] :as attrs} _children]
  (let [draft (str (get state store-key ""))
        ready? (and (not disabled?)
                    (not (str/blank? draft))
                    (not (ui/kind-issued? state :agent/send)))
        send! (fn []
                (when ready?
                  (state/dispatch! send)))]
    [:div.composer-input
     [:textarea
      (-> (dissoc attrs :ui/store-key :ui/send :ui/send-label :ui/disabled? :ui/state)
          (assoc :value draft
                 :disabled (boolean disabled?)
                 :on {:input [[:store/assoc store-key :event/target.value]]
                      :keydown (fn [^js event]
                                 (when (and (= "Enter" (.-key event))
                                            (not (.-shiftKey event))
                                            (not (.-isComposing event))
                                            (not (coarse-pointer?)))
                                   (.preventDefault event)
                                   (send!)))}))]
     [:button.primary {:disabled (not ready?)
                       :on {:click (fn [_event]
                                     (send!))}}
      (or send-label "Send")]]))

(def register
  [{:ui.alias/kind :ui/composer
    :ui.alias/fn composer}])
