(ns chazel
  (:require [wall.hack :refer [field]]
            [cheshire.core :refer [parse-string]]
            [clojure.tools.logging :refer [warn info error]])
  (:import [java.util Collection Map]
           [java.io Serializable]
           [java.util.concurrent Callable]
           [com.hazelcast.core Hazelcast IMap EntryEvent ITopic Message MessageListener]
           [com.hazelcast.topic ReliableMessageListener]
           [com.hazelcast.query SqlPredicate PagingPredicate]
           [com.hazelcast.client HazelcastClient]
           [com.hazelcast.client.impl HazelcastClientProxy]
           [com.hazelcast.client.config ClientConfig]
           [com.hazelcast.config GroupConfig]
           [com.hazelcast.map.listener EntryAddedListener 
                                       EntryRemovedListener 
                                       EntryEvictedListener
                                       EntryUpdatedListener]
           [com.hazelcast.instance HazelcastInstanceProxy]
           [org.hface InstanceStatsTask]))

(defn new-instance 
  ([] (new-instance nil))
  ([conf]
    (Hazelcast/newHazelcastInstance conf)))

(defn all-instances []
  (Hazelcast/getAllHazelcastInstances))

(defn hz-instance 
  ([] 
     (or (first (all-instances))
         (new-instance)))
  ([conf]
    (Hazelcast/getOrCreateHazelcastInstance conf)))

(defn client-config [{:keys [hosts retry-ms retry-max group-name group-password]}]
  (let [config (ClientConfig.)
        groupConfig (GroupConfig. group-name group-password)]
    (doto config 
      (.getNetworkConfig)
      (.addAddress (into-array hosts))
      (.setConnectionAttemptPeriod retry-ms)
      (.setConnectionAttemptLimit retry-max))
      (.setGroupConfig config groupConfig)
    config))

(defn instance-active? [instance]
  (-> instance
      (.getLifecycleService)
      (.isRunning)))

(defonce 
  ;; "will only be used if no config is provided"
  default-client-config
  {:hosts ["127.0.0.1"]
   :retry-ms 5000
   :retry-max 720000
   :group-name "dev"
   :group-password "dev-pass"})                        ;; 720000 * 5000 = one hour

(defonce c-instance (atom nil))

(defn client-instance 
  ([] (client-instance default-client-config))
  ([conf]
    (let [ci @c-instance]
      (if (and ci (instance-active? ci))
        ci
        (try
          (info "connecting to: " conf)
          (reset! c-instance
                  (HazelcastClient/newHazelcastClient (client-config conf)))
          (catch Throwable t
            (warn "could not create hazelcast a client instance: " t)))))))

(defn client-instance? []
  (let [ci @c-instance]
    (and ci (instance-active? ci))))

;; creates a demo cluster
(defn cluster-of [nodes & {:keys [conf]}]
  (repeatedly nodes #(new-instance conf)))

(defn distributed-objects [hz-instance]
  (.getDistributedObjects hz-instance))

(defn find-all-maps
  ([] (find-all-maps (hz-instance)))
  ([instance]
  (filter #(instance? com.hazelcast.core.IMap %) 
          (distributed-objects instance))))

(defn map-sizes 
  ([] (map-sizes (hz-instance)))
  ([instance]
  (reduce (fn [m o]
            (if (instance? com.hazelcast.core.IMap o)
              (assoc m (.getName o) {:size (.size o)})
              m)) {} (distributed-objects instance))))

(defn cluster-stats 
  ([] (cluster-stats (hz-instance)))
  ([instance]
   (try
     (as-> instance $
           (.getExecutorService $ "stats-exec-service")
           (.submitToAllMembers $ (InstanceStatsTask.))
           (for [[m f] $]
             [(str m) (parse-string @f true)])
           (into {} $))
     (catch Throwable t
       (warn "could not submit a \"collecting stats\" task via hazelcast instance [" instance "]: " (.getMessage t))))))

;; adds a string kv pair to the local member of this hazelcast instance
(defn add-member-attr [instance k v]
  (-> instance 
    (.getCluster)
    (.getLocalMember)
    (.setStringAttribute k v)))

(defn local-member-by-instance [instance]
  (-> instance 
    (.getCluster)
    (.getLocalMember)))

(defn members-by-instance [instance]
  (-> instance 
    (.getCluster)
    (.getLocalMember)))

(defn hz-map 
  ([m]
    (hz-map (name m) (hz-instance)))
  ([m instance]
    (.getMap instance (name m))))

(defn hz-mmap 
  ([m]
    (hz-mmap (name m) (hz-instance)))
  ([m instance]
    (.getMultiMap instance (name m))))

(defn hz-queue
  ([m]
    (hz-queue (name m) (hz-instance)))
  ([m instance]
    (.getQueue instance (name m))))

(defn ^ITopic hz-reliable-topic
  ([t]
    (hz-reliable-topic (name t) (hz-instance)))
  ([t instance]
    (.getReliableTopic instance (name t))))

(defn message-listener [f]
  (when (fn? f)
    (reify 
      MessageListener
        (^void onMessage [this ^Message msg]
          (f (.getMessageObject msg))))))     ;; TODO: {:msg :member :timestamp}

(defn reliable-message-listener [f {:keys [start-from store-seq loss-tolerant? terminal?]
                                    :or {start-from -1 store-seq identity loss-tolerant? false terminal? true}}]
  (when (fn? f)
    (reify 
      ReliableMessageListener
        (^long retrieveInitialSequence [this] start-from)
        (^void storeSequence [this ^long sq] (store-seq sq))
        (^boolean isLossTolerant [this] loss-tolerant?)
        (^boolean isTerminal [this ^Throwable failure] 
          (throw failure)
          terminal?)
      MessageListener
        (^void onMessage [this ^Message msg]
          (f (.getMessageObject msg))))))     ;; TODO: {:msg :member :timestamp}

(defprotocol Topic
  (add-message-listener [t f])
  (remove-message-listener [t id])
  (publish [t msg])
  (local-stats [t])
  (hz-name [t]))

(defprotocol ReliableTopic
  (add-reliable-listener [t f opts]))

;; reason for both "add-message-listener" and "add-reliable-listener": http://dev.clojure.org/jira/browse/CLJ-1024
;; i.e. can't do: "(add-message-listener t f & opts)" in protocol

(extend-type com.hazelcast.topic.impl.reliable.ReliableTopicProxy
  ReliableTopic
  (add-reliable-listener [t f opts]
    (.addMessageListener t (reliable-message-listener f opts)))
  Topic
  (add-message-listener [t f]
    (.addMessageListener t (message-listener f)))
  (remove-message-listener [t id]
    (.removeMessageListener t id))
  (publish [t msg]
    (.publish t msg))
  (local-stats [t]
    (.getLocalTopicStats t))
  (hz-name [t]
    (.getName t)))

(defn proxy-to-instance [p]
  (condp instance? p
    HazelcastInstanceProxy (field HazelcastInstanceProxy :original p)
    HazelcastClientProxy (field HazelcastClientProxy :client p)
    p))

(defn shutdown-client [instance]
  (HazelcastClient/shutdown instance))

(defn shutdown []
  (let [instance (hz-instance)]
    (when instance
      (.shutdown instance))))

(defn put! 
  ([^IMap m k v f]
    (put! m k (f v)))
  ([^IMap m k v]
    (.put m k v)))

(defn cget 
  ([^IMap m k f]
    (f (cget m k)))
  ([^IMap m k]
    (.get m k)))

(defn put-all! [^IMap dest ^Map src]
  (.putAll dest src))

(defn remove! [^IMap m k]
  (.remove m k))

(defn delete! [^IMap m k]
  (.delete m k))

(defn add-index 
  ([^IMap m index]
   (add-index m index false))
  ([^IMap m index ordered?]
   (.addIndex m index ordered?)))



(defn- run-query [m pred where as]
  (case as
    :set (into #{} (.values m pred))
    :map (into {} (.entrySet m pred))
    :native (.entrySet m pred)
    (error (str "can't return a result of a distributed query as \"" as "\" (an unknown format you provided). "
                "query: \"" where "\", running on: \"" (.getName m) "\""))))

(defprotocol Pageable
  (next-page [_]))

(deftype Pages [m pred where as]
  Pageable
  (next-page [_]
             (.nextPage pred)
             (run-query m pred where as))) 

;; TODO: QUERY_RESULT_SIZE_LIMIT
(defn select [m where & {:keys [as comp-fn page-size]
                         :or {as :set
                              comp-fn (fn [e1 e2]
                                        (compare (.getKey e1)
                                                 (.getKey e2)))}}]
  (let [sql-pred (SqlPredicate. where)
        pred (if-not page-size
               sql-pred
               (PagingPredicate. sql-pred comp-fn page-size))
        rset (run-query m pred where as)]
    (if-not page-size
      rset
      {:pages (Pages. m pred where as) :results rset})))

(defn add-entry-listener [m ml]
  (.addEntryListener m ml true))

(defn remove-entry-listener [m listener-id]
  (.removeEntryListener m listener-id))

(defn entry-added-listener [f]
  (when (fn? f)
    (reify 
      EntryAddedListener
        (^void entryAdded [this ^EntryEvent entry]
          (f (.getKey entry) (.getValue entry) (.getOldValue entry))))))

(defn entry-removed-listener [f]
  (when (fn? f)
    (reify 
      EntryRemovedListener
        (^void entryRemoved [this ^EntryEvent entry]
          (f (.getKey entry) (.getValue entry) (.getOldValue entry))))))

(defn entry-updated-listener [f]
  (when (fn? f)
    (reify 
      EntryUpdatedListener
        (^void entryUpdated [this ^EntryEvent entry]
          (f (.getKey entry) (.getValue entry) (.getOldValue entry))))))

(defn entry-evicted-listener [f]
 (when (fn? f)
   (reify
     EntryEvictedListener
     (^void entryEvicted [this ^EntryEvent entry]
       (f (.getKey entry) (.getValue entry) (.getOldValue entry))))))

(deftype Task [fun]
  Serializable

  Runnable
  (run [_] (fun))

  Callable
  (call [_] (fun)))

(defn- task-args [& {:keys [members instance es-name]
                   :or {members :any
                        instance (if (client-instance?)
                                  (client-instance)
                                  (hz-instance))
                        es-name :default}
                   :as args}]
  (assoc args :exec-svc (.getExecutorService instance (name es-name))))

(defn task [fun & args]
  (let [{:keys [exec-svc members]} (apply task-args args)]
    (if (= :all members)
      (.executeOnAllMembers exec-svc (Task. fun))
      (.execute exec-svc (Task. fun)))))

(defn ftask [fun & args]
  (let [{:keys [exec-svc members]} (apply task-args args)]
    (if (= :all members)
      (.submitToAllMembers exec-svc (Task. fun))
      (.submit exec-svc (Task. fun)))))

;; to be a bit more explicit about these tasks (their futures) problems
;; good idea to call it before executing distributed tasks
(defn set-default-exception-handler []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (error ex "Uncaught exception on" (.getName thread))))))
