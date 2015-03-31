(ns chazel
  (:require [wall.hack :refer [field]]
            [cprop :refer [conf]]
            [clojure.tools.logging :refer [warn]])
  (:import [java.util Collection Map]
           [com.hazelcast.core Hazelcast IMap]
           [com.hazelcast.query SqlPredicate]
           [com.hazelcast.client HazelcastClient]
           [com.hazelcast.client.impl HazelcastClientProxy]
           [com.hazelcast.client.config ClientConfig]
           [com.hazelcast.instance HazelcastInstanceProxy]))

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

(def client-config
  (delay
    (let [config (ClientConfig.)]
      (doto config 
        (.getNetworkConfig)
        (.addAddress (into-array (conf :hz-client :addresses)))
        (.setConnectionAttemptPeriod (conf :hz-client :time-between-connection-retries-ms))
        (.setConnectionAttemptLimit (conf :hz-client :retry-max)))
      config)))

(def c-instance
  (delay (atom (HazelcastClient/newHazelcastClient @client-config))))

(defn instance-active? [instance]
  (-> instance
      (.getLifecycleService)
      (.isRunning)))

(defn client-instance []
  (let [ci @@c-instance]
    (if (instance-active? ci)
      ci
      (try
        (reset! @c-instance
                (HazelcastClient/newHazelcastClient @client-config))
        (catch Throwable t
               (warn "could not create hazelcast a client instance: " (.getMessage t)))))))

;; creates a demo cluster
(defn cluster-of [nodes & {:keys [conf]}]
  (repeatedly nodes #(new-instance conf)))

(defn distributed-objects [hz-instance]
  (.getDistributedObjects hz-instance))

(defn find-all-maps [instance]
  (filter #(instance? com.hazelcast.core.IMap %) 
          (distributed-objects instance)))

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

(defn proxy-to-instance [p]
  (condp instance? p
    HazelcastInstanceProxy (field HazelcastInstanceProxy :original p)
    HazelcastClientProxy (field HazelcastClientProxy :client p)
    p))

(defn shutdown []
  (let [instance (hz-instance)]
    (when instance
      (.shutdown instance))))

(defn put! [^IMap m k v]
  (.put m k v))

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

(defn select [m where]
  (.values m (SqlPredicate. where)))
