(defproject chazel "0.1.11-SNAPSHOT"
  :description "hazelcast bells and whistles under the clojure belt"
  :url "https://github.com/tolitius/chazel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src" "src/chazel"]

  :profiles {:dev {:repl-options {:init-ns chazel}
                   :resource-paths ["dev-resources"]}}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [com.hazelcast/hazelcast "3.6.2"]
                 [com.hazelcast/hazelcast-client "3.6.2"]
                 [org.hface/hface-client "0.1.3"]
                 [cheshire "5.6.1"]
                 [com.cognitect/transit-clj "0.8.275"]
                 [clj-wallhack "1.0.1"]])
