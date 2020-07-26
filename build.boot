(def +version+ "0.1.20")

(set-env!
  :source-paths #{"src"}
  :dependencies '[[org.clojure/clojure "1.10.1"]
                  [org.clojure/tools.logging "0.4.1"]
                  [com.hazelcast/hazelcast "4.0.2"]
                  [org.hface/hface-client "0.1.7"]
                  [org.clojure/data.json "1.0.0"]
                  [com.cognitect/transit-clj "0.8.275"]
                  [clj-wallhack "1.0.1"]

                  ;; boot clj
                  [boot/core               "2.7.1"           :scope "provided"]
                  [adzerk/boot-logservice  "1.2.0"           :scope "test"]
                  [adzerk/bootlaces        "0.2.0"           :scope "test"]])

(require '[adzerk.bootlaces :refer :all]
         '[clojure.tools.logging :as log]
         '[adzerk.boot-logservice :as log-service])

(def log4b
  [:configuration
   [:appender {:name "STDOUT" :class "ch.qos.logback.core.ConsoleAppender"}
    [:encoder [:pattern "%-5level %logger{36} - %msg%n"]]]
   [:root {:level "TRACE"}
    [:appender-ref {:ref "STDOUT"}]]])

(deftask dev-env []
  ;; (import  '[chazel.jedis Jedi])
  (require '[chazel.core :refer :all]
           '[chazel.serializer :refer [transit-in transit-out]])

  (alter-var-root #'log/*logger-factory*
                  (constantly (log-service/make-factory log4b)))
  identity)

(deftask dev []
  (set-env! :source-paths #{"src" "dev"})
  (comp (aot :namespace '#{chazel.jedis})
        (dev-env)
        (repl)))

(bootlaces! +version+)

(task-options!
  push {:ensure-branch nil}
  pom {:project     'chazel
       :version     +version+
       :description "hazelcast bells and whistles under the clojure belt"
       :url         "https://github.com/tolitius/chazel"
       :scm         {:url "https://github.com/tolitius/chazel"}
       :license     {"Eclipse Public License"
                     "http://www.eclipse.org/legal/epl-v10.html"}})
