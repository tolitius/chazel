(defproject chazel "0.1.0-SNAPSHOT"
  :description "hazelcast bells and whistles under the clojure belt"
  :url "https://github.com/tolitius/chazel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src" "src/chazel"]

  :profiles {:dev {:repl-options {:init-ns chazel}}}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [cprop "0.1.0-SNAPSHOT"]
                 [org.clojure/tools.logging "0.3.1"]
                 [com.hazelcast/hazelcast "3.5-EA"]
                 [com.hazelcast/hazelcast-client "3.5-EA"]
                 [clj-wallhack "1.0.1"]])
