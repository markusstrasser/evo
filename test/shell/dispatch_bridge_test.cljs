(ns shell.dispatch-bridge-test
  (:require [cljs.test :refer [deftest is testing]]
            [shell.dispatch-bridge :as dispatch-bridge]))

(deftest dispatch-handler-data-routes-lifecycle-functions
  (testing "lifecycle hooks receive the full event payload"
    (let [seen (atom nil)
          event-data {:replicant/trigger :replicant.trigger/life-cycle
                      :replicant/node :mock}]
      (dispatch-bridge/dispatch-handler-data!
       event-data
       (fn [payload] (reset! seen payload)))
      (is (= event-data @seen)))))

(deftest dispatch-handler-data-routes-dom-functions
  (testing "plain DOM handlers receive the raw DOM event payload"
    (let [seen (atom nil)
          dom-event {:type "keydown"}
          event-data {:replicant/trigger :replicant.trigger/dom-event
                      :replicant/dom-event dom-event}]
      (dispatch-bridge/dispatch-handler-data!
       event-data
       (fn [payload] (reset! seen payload)))
      (is (= dom-event @seen)))))

(deftest dispatch-handler-data-ignores-stale-non-function-handlers
  (testing "stale vector handlers are ignored instead of reviving compatibility code"
    (let [actions [[:editing/navigate-up {:block-id "a"}]]
          event-data {:replicant/trigger :replicant.trigger/dom-event
                      :replicant/dom-event {:type "keydown"}}
          original-warn (.-warn js/console)]
      (set! (.-warn js/console) (fn [& _]))
      (try
        (is (nil? (dispatch-bridge/dispatch-handler-data! event-data actions)))
        (finally
          (set! (.-warn js/console) original-warn))))))
