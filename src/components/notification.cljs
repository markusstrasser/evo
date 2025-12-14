(ns components.notification
  "Toast notification component with undo action support.

   Uses the Popover API for top-layer rendering and CSS animations.
   Positioned at bottom center, auto-dismisses after timeout."
  (:require [shell.view-state :as vs]))

(defn Notification
  "Toast notification component.

   Renders at bottom center with slide-up animation.
   Supports optional action button (e.g., Undo).

   Usage:
     (Notification) ; reads from view-state"
  []
  (when-let [{:keys [message type action]} (vs/notification)]
    (let [type-class (case type
                       :success "notification--success"
                       :error "notification--error"
                       :warning "notification--warning"
                       :info "notification--info"
                       "notification--success")]
      [:div.notification
       {:class type-class
        :popover "manual"
        :replicant/on-mount (fn [{:replicant/keys [node]}]
                              ;; Show popover on mount
                              (when (.-showPopover node)
                                (.showPopover node)))
        :replicant/on-unmount (fn [{:replicant/keys [node]}]
                                ;; Hide popover on unmount
                                (when (.-hidePopover node)
                                  (.hidePopover node)))}

       [:span.notification__message message]

       (when action
         [:button.notification__action
          {:on {:click (fn [e]
                         (.stopPropagation e)
                         (when-let [handler (:on-click action)]
                           (handler))
                         (vs/dismiss-notification!))}}
          (:label action)])

       [:button.notification__dismiss
        {:on {:click (fn [_] (vs/dismiss-notification!))}
         :aria-label "Dismiss"}
        "\u00D7"]]))) ; × character
