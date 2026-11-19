(ns mcp.test-project
  "Test support: this project's own code base as the indexed project,
   with the shipped `resources/config.edn`. The code index is built once
   per test run."
  (:require [clojure.test :refer [is]]
            [mcp.code-index :as code-index]
            [mcp.config :as config])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def root
  "Absolute path of this project's root directory."
  (.getCanonicalPath (File. ".")))

(def config
  "Resolved config for indexing this project."
  (-> (config/load-config "resources/config.edn")
      (assoc :index/root-path root)
      config/resolve-config))

(def ^:private code-index-delay
  (delay (code-index/build config)))

(defn code-index
  "The code index of this project, built on first use."
  []
  @code-index-delay)

(defn answer-text
  "The text of the `tools/call` result `tool-result`, asserting that it
   fits the Output budget of `cfg` (every acceptance case checks this)."
  [cfg tool-result]
  (let [text (-> tool-result :content first :text)]
    (is (<= (count text) (:output/max-chars cfg))
        (str "answer of " (count text) " characters exceeds the Output budget"))
    text))
