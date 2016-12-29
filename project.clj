(defproject chazel "0.1.12-SNAPSHOT"
  :description "hazelcast bells and whistles under the clojure belt"
  :url "https://github.com/tolitius/chazel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src"]

  :profiles {:dev {:repl-options {:init-ns chazel.core}
                   :resource-paths ["dev-resources" "dev"]
                   :aot [chazel.jedis]}}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [com.hazelcast/hazelcast "3.7.4"]
                 [com.hazelcast/hazelcast-client "3.7.4"]
                 [org.hface/hface-client "0.1.3"]
                 [cheshire "5.6.3"]
                 [com.cognitect/transit-clj "0.8.275"]
                 [clj-wallhack "1.0.1"]])
