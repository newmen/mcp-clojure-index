(defproject mcp "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [org.clojure/data.json "2.5.2"]
                 [org.clojure/tools.logging "1.3.1"]
                 [ch.qos.logback/logback-classic "1.5.38"]
                 [clj-http "3.13.1"]
                 [rewrite-clj "1.2.55"]
                 [clj-kondo/clj-kondo "2026.10.26"]]
  :main mcp.server
  :repl-options {:init-ns mcp.server}
  :profiles {:dev {:plugins [[lambdaisland/kaocha "1.91.1392"]]}
             ;; Own compile path, see "AOT and the REPL" in AGENTS.md.
             :uberjar {:aot :all
                       :compile-path "target/uberjar/classes"}}
  :test-paths ["test"]
  :test-selectors {:default (complement :integration)
                   :integration :integration})
