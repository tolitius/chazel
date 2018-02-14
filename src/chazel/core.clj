(ns chazel.core
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
           [com.hazelcast.config Config GroupConfig
                                 InMemoryFormat
                                 EvictionConfig EvictionPolicy EvictionConfig$MaxSizePolicy
                                 NearCacheConfig NearCachePreloaderConfig NearCacheConfig$LocalUpdatePolicy]
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

(defmacro call [m o v]
  "calls (m o v) iff v is there"
  `(when ~v
     (~m ~o ~v)))

(defmacro k->enum [value-of k]
  "calls (enum/valueOf k) iff k is there"
  `(when ~k
     (~value-of (name ~k))))

(defn eviction-config [{:keys [eviction-policy
                               max-size-policy
                               size]}]
  (let [config (EvictionConfig.)
        max-size-policy (k->enum EvictionConfig$MaxSizePolicy/valueOf
                                 max-size-policy)
        eviction-policy (k->enum EvictionPolicy/valueOf
                                 eviction-policy)]
    (call .setMaximumSizePolicy config max-size-policy)
    (call .setEvictionPolicy config eviction-policy)
    (call .setSize config size)
    config))

(defn preloader-config [{:keys [enabled
                                directory
                                store-initial-delay-seconds
                                store-interval-seconds]}]
  (let [config (NearCachePreloaderConfig.)]
    (call .setEnabled config enabled)
    (call .setDirectory config directory)
    (call .setStoreInitialDelaySeconds config store-initial-delay-seconds)
    (call .setStoreIntervalSeconds config store-interval-seconds)
    config))

(defn near-cache-config [{:keys [name
                                 eviction
                                 preloader
                                 in-memory-format
                                 invalidate-on-change
                                 time-to-live-seconds
                                 max-idle-seconds
                                 cache-local-entries
                                 local-update-policy]}]
  (let [config (NearCacheConfig.)
        eviction (when eviction (eviction-config eviction))
        preloader (when preloader (preloader-config preloader))
        local-update-policy (k->enum NearCacheConfig$LocalUpdatePolicy/valueOf
                                     local-update-policy)
        in-memory-format (k->enum InMemoryFormat/valueOf
                                  in-memory-format)]
    (call .setName config name)
    (call .setEvictionConfig config eviction)
    (call .setPreloaderConfig config preloader)
    (call .setInMemoryFormat config in-memory-format)
    (call .setInvalidateOnChange config invalidate-on-change)
    (call .setTimeToLiveSeconds config time-to-live-seconds)
    (call .setMaxIdleSeconds config max-idle-seconds)
    (call .setLocalUpdatePolicy config local-update-policy)
    (call .setCacheLocalEntries config cache-local-entries)
    config))

(defn client-config [{:keys [hosts retry-ms retry-max group-name group-password near-cache]
                      :or {hosts ["127.0.0.1"]
                           retry-ms 5000
                           retry-max 720000
                           group-name "dev"
                           group-password "dev-pass"}}]
  (let [config (ClientConfig.)
        groupConfig (GroupConfig. group-name group-password)
        near-cache (when near-cache
                     (near-cache-config near-cache))]
    (doto config
      (.getNetworkConfig)
      (.addAddress (into-array hosts))
      (.setConnectionAttemptPeriod retry-ms)
      (.setConnectionAttemptLimit retry-max))
    (.setGroupConfig config groupConfig)
    (call .addNearCacheConfig config near-cache)  ;; only set near cache config if provided
    config))

(defn with-creds
  ([creds]
   (with-creds creds (Config.)))
  ([{:keys [group-name group-password]} config]
   (.setGroupConfig config (GroupConfig. group-name
                                         group-password))))

(defn with-near-cache
  ([nc-config map-name]
   (with-near-cache nc-config map-name (Config.)))
  ([nc-config map-name hz-config]
   (.setNearCacheConfig (.getMapConfig hz-config map-name)
                        (near-cache-config nc-config))
   hz-config))

(defn instance-active? [instance]
  (-> instance
      (.getLifecycleService)
      (.isRunning)))

(defonce c-instance (atom nil))

(defn secrefy [{:keys [group-name
                       group-password] :as conf}]
  (-> conf
      (cond-> group-name (assoc :group-name "********")
              group-password (assoc :group-password "********"))))

(defn client-instance
  ([] (client-instance {}))
  ([conf]
    (let [ci @c-instance]
      (if (and ci (instance-active? ci))
        ci
        (try
          (info "connecting to: " (secrefy conf))
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

(defn- run-query [m where as pred]
  (case as
    :set (into #{} (if pred (.values m pred)
                            (.values m)))
    :map (into {} (if pred (.entrySet m pred)
                           (.entrySet m)))
    :native (if pred (.entrySet m pred)
                     (.entrySet m))
    (error (str "can't return a result of a distributed query as \"" as "\" (an unknown format you provided). "
                "query: \"" where "\", running on: \"" (.getName m) "\""))))

(defprotocol Pageable
  (next-page [_]))

;; TODO: implement Seqable, mark with Sequential
(deftype Pages [m where as pred]
  Pageable
  (next-page [_]
             (.nextPage pred)
             (run-query m where as pred)))

(def comp-keys
  (comparator (fn [a b]
                (> (compare (.getKey a)
                            (.getKey b))
                   0))))

(defn with-paging [n & {:keys [order-by pred]
                        :or {order-by comp-keys}}]
  (if-not pred
    (PagingPredicate. order-by n)
    (PagingPredicate. pred order-by n)))

;; TODO: QUERY_RESULT_SIZE_LIMIT
(defn select [m where & {:keys [as order-by page-size]
                         :or {as :set
                              order-by comp-keys}}]
  (let [sql-pred (if-not (= "*" where)
                   (SqlPredicate. where))
        pred (if-not page-size
               sql-pred
               (with-paging page-size :order-by order-by
                                      :pred sql-pred))
        rset (run-query m where as pred)]
    (if-not page-size
      rset
      {:pages (Pages. m where as pred) :results rset})))

(defn query-cache
  "continuous query cache: i.e. (query-cache m \"vim-cache\" \"editor = vim\")"
  ([m cname]
   (.getQueryCache m cname))
  ([m cname pred]
   (query-cache m cname pred true))
  ([m cname pred include-value?]
   (.getQueryCache m cname (SqlPredicate. pred) include-value?))
  ([m cname pred listener include-value?]
   (.getQueryCache m cname listener (SqlPredicate. pred) include-value?)))

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

(defn mtake [n m]
  (into {} (take n (hz-map m))))

(defn ->mtake [n mname]                 ;; for clients
  @(ftask (partial mtake n mname)))

;; to be a bit more explicit about these tasks (their futures) problems
;; good idea to call it before executing distributed tasks
(defn set-default-exception-handler []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (error ex "Uncaught exception on" (.getName thread))))))
