(defproject mcp "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [org.clojure/data.json "2.5.2"]
                 [org.clojure/core.async "1.9.865"]
                 [clj-http "3.13.1"]
                 [rewrite-clj "1.2.55"]
                 [aysylu/loom "1.0.2"]]
  :repl-options {:init-ns mcp.server}
  :profiles {:dev {:dependencies [[criterium "0.4.6"]]
                   :plugins [[lambdaisland/kaocha "1.91.1392"]]}}
  :test-paths ["test"])
