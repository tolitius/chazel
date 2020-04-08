(ns chazel.core
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :refer [warn info error]])
  (:import [java.util Collection Map Comparator]
           [java.io Serializable]
           [java.util.concurrent Callable]
           [com.hazelcast.core Hazelcast IMap ICollection EntryEvent ITopic Message MessageListener ExecutionCallback HazelcastInstance Cluster IExecutorService LifecycleService Member]
           [com.hazelcast.topic ReliableMessageListener]
           [com.hazelcast.query SqlPredicate PagingPredicate]
           [com.hazelcast.client HazelcastClient]
           [com.hazelcast.client.impl.clientside HazelcastClientProxy]
           [com.hazelcast.client.config ClientConfig]
           [com.hazelcast.config Config GroupConfig
                                 InMemoryFormat
                                 EvictionConfig EvictionPolicy EvictionConfig$MaxSizePolicy
                                 NearCacheConfig NearCachePreloaderConfig NearCacheConfig$LocalUpdatePolicy]
           [com.hazelcast.map.listener EntryAddedListener
                                       EntryRemovedListener
                                       EntryEvictedListener
                                       EntryUpdatedListener
                                       EntryLoadedListener
                                       EntryExpiredListener
                                       EntryMergedListener MapListener]
           [com.hazelcast.instance HazelcastInstanceProxy]
           [org.hface InstanceStatsTask]
           (com.hazelcast.topic.impl.reliable ReliableTopicProxy)))

(defmacro k->enum
  [t k]
  `(some->> ~k name (. java.lang.Enum ~'valueOf ~t)))

(defn new-instance
  ([]
   (new-instance nil))
  ([conf]
    (Hazelcast/newHazelcastInstance conf)))

(defn all-instances []
  (Hazelcast/getAllHazelcastInstances))

(defn hz-instance
  (^HazelcastInstance []
     (or (first (all-instances))
         (new-instance)))
  (^HazelcastInstance [conf]
    (Hazelcast/getOrCreateHazelcastInstance conf)))

(defn eviction-config
  ^EvictionConfig
  [{:keys [eviction-policy
           max-size-policy
           size]}]
  (let [max-size-policy (k->enum EvictionConfig$MaxSizePolicy max-size-policy)
        eviction-policy (k->enum EvictionPolicy eviction-policy)]

    (cond-> (EvictionConfig.)
            max-size-policy (.setMaximumSizePolicy max-size-policy)
            eviction-policy (.setEvictionPolicy eviction-policy)
            size            (.setSize size))))

(defn preloader-config
  ^NearCachePreloaderConfig
  [{:keys [enabled
           directory
           store-initial-delay-seconds
           store-interval-seconds]}]
  (cond-> (NearCachePreloaderConfig.)
          enabled                     (.setEnabled enabled)
          directory                   (.setDirectory directory)
          store-initial-delay-seconds (.setStoreInitialDelaySeconds store-initial-delay-seconds)
          store-interval-seconds      (.setStoreIntervalSeconds store-interval-seconds)))

(defn near-cache-config
  ^NearCacheConfig
  [{:keys [^String name
           eviction
           preloader
           in-memory-format
           invalidate-on-change
           time-to-live-seconds
           max-idle-seconds
           cache-local-entries
           local-update-policy]}]
  (let [^EvictionConfig eviction            (some-> eviction  eviction-config)
        ^NearCachePreloaderConfig preloader (some-> preloader preloader-config)
        ^NearCacheConfig$LocalUpdatePolicy local-update-policy (k->enum NearCacheConfig$LocalUpdatePolicy
                                                                        local-update-policy)
        ^InMemoryFormat in-memory-format (k->enum InMemoryFormat in-memory-format)]
    (cond-> (NearCacheConfig.)
            name                 (.setName name)
            eviction             (.setEvictionConfig eviction)
            preloader            (.setPreloaderConfig preloader)
            in-memory-format     (.setInMemoryFormat in-memory-format)
            invalidate-on-change (.setInvalidateOnChange invalidate-on-change)
            time-to-live-seconds (.setTimeToLiveSeconds time-to-live-seconds)
            max-idle-seconds     (.setMaxIdleSeconds max-idle-seconds)
            local-update-policy  (.setLocalUpdatePolicy local-update-policy)
            cache-local-entries  (.setCacheLocalEntries cache-local-entries))))

(defn with-creds
  ([creds]
   (with-creds creds (Config.)))
  ([{:keys [group-name group-password]} config]
   (println group-name group-password )
   (let [group-config (GroupConfig. group-name group-password)]
     (condp instance? config
       Config       (.setGroupConfig ^Config config group-config)
       ClientConfig (.setGroupConfig ^ClientConfig config group-config)
       ;; reflective call (the only one and will probably never be reached anyway)
       (.setGroupConfig config group-config)))))

(defn client-config
  ^ClientConfig
  [{:keys [hosts retry-ms retry-max group-name group-password near-cache smart-routing]
    :or {hosts ["127.0.0.1"]
         retry-ms 5000
         retry-max 720000
         group-name "dev"
         group-password "dev-pass"
         smart-routing true}}]
  (let [^ClientConfig config (with-creds
                               {:group-name group-name
                                :group-password group-password}
                               (ClientConfig.))
        ^NearCacheConfig near-cache (some-> near-cache near-cache-config)]
    (-> config
      (.getNetworkConfig)
      (.addAddress (into-array String hosts))
      (.setConnectionAttemptPeriod retry-ms)
      (.setConnectionAttemptLimit retry-max)
      (.setSmartRouting smart-routing))

    (cond-> config near-cache (.addNearCacheConfig near-cache)))) ;; only set near cache config if provided


(defn with-near-cache
  ([nc-config map-name]
   (with-near-cache nc-config map-name (Config.)))
  ([nc-config map-name ^Config hz-config]
   (-> hz-config
       (.getMapConfig map-name)
       (.setNearCacheConfig (near-cache-config nc-config)))
   hz-config))

(defn instance-active?
  "Checks that the provided <instance> is running,
   and if so returns it - otherwise returns falsey."
  [^HazelcastInstance instance]
  (and (some-> instance .getLifecycleService .isRunning)
       instance))

(defonce c-instance (atom nil))

(def client-instance?
  "Checks that the current instance is running
   via `instance-active?`."
  (comp instance-active? (partial deref c-instance)))

(defn secrefy
  [{:keys [group-name
           group-password] :as conf}]
  (cond-> conf
          group-name     (assoc :group-name "********")
          group-password (assoc :group-password "********")))

(defn client-instance
  ([]
   (client-instance {}))
  ([conf]
   (if-let [ci (client-instance?)]
     ci
     (try
       (info "connecting to: " (secrefy conf))
       (reset! c-instance
               (HazelcastClient/newHazelcastClient (client-config conf)))
       (catch Throwable t
         (warn "could not create hazelcast a client instance: " t))))))



;; creates a demo cluster
(defn cluster-of
  [nodes & {:keys [conf]}]
  (repeatedly nodes (partial new-instance conf)))

(defn distributed-objects
  [^HazelcastInstance hz-instance]
  (.getDistributedObjects hz-instance))

(defn find-all-maps
  ([]
   (find-all-maps (hz-instance)))
  ([instance]
  (filter (partial instance? IMap)
          (distributed-objects instance))))

(defn map-sizes
  ([]
   (map-sizes (hz-instance)))
  ([instance]
   (into {}
         (keep
           (fn [o]
             (when (instance? IMap o)
               [(.getName ^IMap o)
                {:size (.size ^IMap o)}])))
         (distributed-objects instance))))

(defn cluster-stats
  ([]
   (cluster-stats (hz-instance)))
  ([^HazelcastInstance instance]
   (try
     (into {}
           (map
             (fn [[m f]]
               [(str m)
                (json/read-str @f :key-fn keyword)]))
           (-> instance
               (.getExecutorService "stats-exec-service")
               (.submitToAllMembers (InstanceStatsTask.))))
     (catch Throwable t
       (warn "could not submit a \"collecting stats\" task via hazelcast instance [" instance "]: " (.getMessage t))))))


(defn local-member-by-instance
  ^Member [^HazelcastInstance instance]
  (-> instance .getCluster .getLocalMember))

;; adds a string kv pair to the local member of this hazelcast instance
(defn add-member-attr
  [instance k v]
  (-> instance
      local-member-by-instance
      (.setStringAttribute k v)))

#_(defn members-by-instance
  [instance]
  (-> instance
      (.getCluster)
      (.getLocalMember)))

(defn hz-list
  ([m]
    (hz-list (name m) (hz-instance)))
  ([m ^HazelcastInstance instance]
    (.getList instance (name m))))

(defn hz-map
  ([m]
    (hz-map (name m) (hz-instance)))
  ([m ^HazelcastInstance instance]
    (.getMap instance (name m))))

(defn hz-mmap
  ([m]
    (hz-mmap (name m) (hz-instance)))
  ([m ^HazelcastInstance instance]
    (.getMultiMap instance (name m))))

(defn hz-queue
  ([m]
    (hz-queue (name m) (hz-instance)))
  ([m ^HazelcastInstance instance]
    (.getQueue instance (name m))))

(defn hz-reliable-topic
  (^ITopic [t]
    (hz-reliable-topic (name t) (hz-instance)))
  (^ITopic [t ^HazelcastInstance instance]
    (.getReliableTopic instance (name t))))

(defn message-listener [f]
  (when (fn? f)
    (reify
      MessageListener
        (^void onMessage [this ^Message msg]
          (f (.getMessageObject msg))))))     ;; TODO: {:msg :member :timestamp}

(defn reliable-message-listener
  [f {:keys [start-from store-seq loss-tolerant? terminal?]
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

(extend-type ReliableTopicProxy
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

(defn unproxy
  [p]
  (condp instance? p
    HazelcastInstanceProxy (.getOriginal ^HazelcastInstanceProxy p)
    HazelcastClientProxy (.client ^HazelcastClientProxy p)
    p))

(defn shutdown-client
  [instance]
  (if (string? instance)
    (HazelcastClient/shutdown ^String instance)
    (HazelcastClient/shutdown ^HazelcastInstance instance)))

(defn shutdown
  []
  (some-> (hz-instance) .shutdown))

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

(defn put-all!
  [^IMap dest ^Map src]
  (.putAll dest src))

(defn remove!
  [^IMap m k]
  (.remove m k))

(defn delete!
  [^IMap m k]
  (.delete m k))

(defn add-all!
  [^ICollection hc ^Collection c]
  (.addAll hc c))

(defn add-index
  ([^IMap m index]
   (add-index m index false))
  ([^IMap m index ordered?]
   (.addIndex m index ordered?)))

(defn- run-query
  [^IMap m where as pred]
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
    (.nextPage ^PagingPredicate pred)
    (run-query m where as pred)))

(def comp-keys
  (comparator
    (fn [^EntryEvent a ^EntryEvent b]
      (pos?
        (compare
          (.getKey a)
          (.getKey b))))))

(defn with-paging
  [^Long n & {:keys [order-by pred]
              :or {order-by comp-keys}}]
  (if pred
    (PagingPredicate. pred order-by n)
    (PagingPredicate. ^Comparator order-by n)))

;; TODO: QUERY_RESULT_SIZE_LIMIT
(defn select
  [m where & {:keys [as order-by page-size]
              :or {as :set
                   order-by comp-keys}}]
  (let [sql-pred (when-not (= "*" where)
                   (SqlPredicate. where))
        pred (if page-size
               (with-paging
                 page-size
                 :order-by order-by
                 :pred sql-pred)
               sql-pred)
        rset (run-query m where as pred)]
    (if page-size
      {:pages (Pages. m where as pred)
       :results rset}
      rset)))

(defn query-cache
  "continuous query cache: i.e. (query-cache m \"vim-cache\" \"editor = vim\")"
  ([^IMap m cname]
   (.getQueryCache m cname))
  ([m cname pred]
   (query-cache m cname pred true))
  ([^IMap m cname pred include-value?]
   (.getQueryCache m cname (SqlPredicate. pred) include-value?))
  ([^IMap m cname pred listener include-value?]
   (.getQueryCache m cname listener (SqlPredicate. pred) include-value?)))

(defn add-entry-listener
  ^String [^IMap m ^MapListener ml]
  (.addEntryListener m ml true))

(defn remove-entry-listener
  [^IMap m ^String listener-id]
  (.removeEntryListener m listener-id))

(defn- entry-op*
  [op ^EntryEvent entry]
  (op (.getKey entry)
      (.getValue entry)
      (.getOldValue entry)))

(defn entry-added-listener [f]
  (when (fn? f)
    (reify
      EntryAddedListener
        (^void entryAdded [this ^EntryEvent entry]
          (entry-op* f entry)))))

(defn entry-removed-listener
  [f]
  (when (fn? f)
    (reify
      EntryRemovedListener
        (^void entryRemoved [this ^EntryEvent entry]
          (entry-op* f entry)))))

(defn entry-updated-listener
  [f]
  (when (fn? f)
    (reify
      EntryUpdatedListener
        (^void entryUpdated [this ^EntryEvent entry]
          (entry-op* f entry)))))

(defn entry-evicted-listener
  [f]
 (when (fn? f)
   (reify
     EntryEvictedListener
     (^void entryEvicted [this ^EntryEvent entry]
       (entry-op* f entry)))))

(defn entry-expired-listener
  [f]
 (when (fn? f)
   (reify
     EntryExpiredListener
     (^void entryExpired [this ^EntryEvent entry]
       (entry-op* f entry)))))

(defn entry-loaded-listener
  [f]
 (when (fn? f)
   (reify
     EntryLoadedListener
     (^void entryLoaded [this ^EntryEvent entry]
       (entry-op* f entry)))))

(defn entry-merged-listener
  [f]
 (when (fn? f)
   (reify
     EntryMergedListener
     (^void entryMerged [this ^EntryEvent entry]
       (entry-op* f entry)))))

(deftype Rtask [fun]
  Serializable

  Runnable
  (run [_] (fun)))

(deftype Ctask [fun]
  Serializable

  Callable
  (call [_] (fun)))

(defn- task-args
  [& {:keys [members instance es-name]
      :or {members :any
           instance (if-let [ci (client-instance?)]
                      ci
                      (hz-instance))
           es-name :default}
      :as args}]
  (assoc args
    :exec-svc
    (.getExecutorService ^HazelcastInstance instance (name es-name))))

(defn execution-callback
  ^ExecutionCallback
  [{:keys [on-response on-failure]
    :or {on-response identity
         on-failure identity}}]
  (reify
    ExecutionCallback
    (onFailure [this throwable]
      (on-failure throwable))
    (onResponse [this response]
      (on-response response))))

(defn task [fun & args]
  (let [{:keys [^IExecutorService exec-svc members]} (apply task-args args)]
    (if (= :all members)
      (.executeOnAllMembers exec-svc (Rtask. fun))
      (.execute exec-svc (Rtask. fun)))))

(defn ftask [fun & args]
  (let [{:keys [^IExecutorService exec-svc members callback]} (apply task-args args)]
    (if (= :all members)
      (.submitToAllMembers exec-svc (Ctask. fun))    ;; TODO: add MultiExecutionCallback
      (if callback
        (.submit exec-svc
                 (Ctask. fun)
                 (execution-callback callback))
        (.submit exec-svc
                 (Ctask. fun))))))

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
