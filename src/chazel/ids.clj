(ns chazel.ids
  (:import [com.hazelcast.flakeidgen FlakeIdGenerator]))

(defn safe-generator
  ^FlakeIdGenerator
  [^HazelcastInstance hz ^String gname]
  (.getFlakeIdGenerator hz gname))

(defn new-id!
  "Given a hazelcast instance <hz> and IdGenerator name,
  returns a new ID. Uniqueness is controlled by the last
  argument and default to true. If you choose to pass false
  be aware that the underlying generator has been deprecated."
  (^long [hz]
         (new-id! hz "IDS"))
  (^long [hz generator-name]
         (.newId (safe-generator hz generator-name))))

(defn parse-flake-id
  "Parses a FlakeId into its 3 constituent parts."
  [^long n]
  (let [binary (Long/toBinaryString n)
        ts   (-> (subs binary 0 41)   ;; 41 bits - timestamp
                 (BigInteger. 2))
        sequ (-> (subs binary 41 47)  ;; 6 bits - sequence number
                 (BigInteger. 2))
        node (-> (subs binary 47 63)  ;; 16 bits - nodeID
                 (BigInteger. 2))]
    {:millis-since-1-1-2018 (long ts)
     :seq-no  (long sequ)
     :node-id (long node)}))
