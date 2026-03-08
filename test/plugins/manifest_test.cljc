(ns plugins.manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.api :as api]
            [kernel.derived-registry :as registry]
            [plugins.manifest :as manifest]))

(deftest test-init-registers-plugins
  (testing "Plugin manifest exposes one explicit bootstrap surface"
    (let [{:keys [loaded] plugin-count :count :as summary} (manifest/init!)
          loaded-count (clojure.core/count loaded)]
      (is (= plugin-count loaded-count))
      (is (= summary {:loaded loaded :count loaded-count}))
      (is (every? true? (vals loaded)))
      (is (api/has-handler? :enter-edit))
      (is (api/has-handler? :navigate-to-page))
      (is (contains? (registry/registered) :backlinks)))))
