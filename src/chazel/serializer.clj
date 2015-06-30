(ns chazel.serializer
  (:require [cognitect.transit :as transit]
            [clojure.tools.logging :refer [error]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def ^:const byte-array-type 
  (type (byte-array 0)))

(defn bytes? [x] 
  (= (type x) byte-array-type))

(defn transit-out
  ([data] 
   (transit-out data :json {}))
  ([x t opts]
    (let [baos (ByteArrayOutputStream.)
          w    (transit/writer baos t opts)
          _    (transit/write w x)
          ret  (.toByteArray baos)]
      (.reset baos)
      ret)))

(defn transit-in
  ([data] 
   (transit-in data :json {}))
  ([x t opts]
    (if (bytes? x)
      (let [bais (ByteArrayInputStream. x)
            r    (transit/reader bais t opts)]
        (transit/read r))
      (error "[transit-in]: expected a byte array, but got" (str (type x) ": [" x "]") "=> can't deserialize"))))
