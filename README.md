# chazel

Hazelcast bells and whistles under the Clojure belt

![](https://clojars.org/chazel/latest-version.svg)

## show me

```clojure
user=> (use 'chazel)
nil
```

let's start a 3 node cluster

```clojure
user=> (cluster-of 3)
Mar 26, 2015 1:46:43 PM com.hazelcast.cluster.ClusterService
INFO: [10.36.124.50]:5701 [dev] [3.4.1]

Members [3] {
    Member [10.36.124.50]:5701 this
    Member [10.36.124.50]:5702
    Member [10.36.124.50]:5703
}
(#<HazelcastInstanceProxy HazelcastInstance{name='_hzInstance_1_dev', node=Address[10.36.124.50]:5701}> #<HazelcastInstanceProxy HazelcastInstance{name='_hzInstance_2_dev', node=Address[10.36.124.50]:5702}> #<HazelcastInstanceProxy HazelcastInstance{name='_hzInstance_3_dev', node=Address[10.36.124.50]:5703}>)
```

create a map (or multimap, or queue, etc):

```clojure
user=> (def appl (hz-map :appl))
#'user/appl

user=> (type appl)
com.hazelcast.map.impl.proxy.MapProxyImpl
```

use the map:

```clojure
user=> (put! appl :apple 42)

user=> appl
{:apple 42}
```

some other cool things:

```clojure
user=> (def goog (hz-map :goog))
#'user/goog

user=> (put! goog :goog 42)

user=> (find-all-maps (hz-instance))
({:appl 42} 
 {:goog 42})
```

### Serialization

To start off chazel has [transit](https://github.com/cognitect/transit-clj) seriailzer ready to go:

```clojure
user=> (require '[chazel.serializer :refer [transit-in transit-out]])
user=> (def m (hz-map "amzn"))

user=> (put! m "bids" {:opening [429 431 430 429] :nbbo [428 430 429 427]} transit-out)
#<byte[] [B@5d9d8664>
user=>
```
notice `transit-out`, it is an optional function to `put!` that will be applied to the value before the hazelcast put is called. In this case a value will be serialized with transit.

```clojure
user=> (get m "bids")
#<byte[] [B@638b6eec>

a default chazel's `get` will return the value the way it has it stored: byte array. Similarly to `put!` `get` also takes in an optional function that is applied after the value is fetched from hazelcast:

user=> (get m "bids" transit-in)
{:opening [429 431 430 429], :nbbo [428 430 429 427]}

user=> (type (get m "bids" transit-in))
clojure.lang.PersistentArrayMap
```

Serialization is a big deal when the nodes are distributed, or when you connect to a remote hazelcast cluster. chazel solves this problem delegating it to an optional serializer.
In case you need to use a different serializer, you can either send a pull request updating a `chazel.serializer` file, or specify your own secret serialize function in `put!` and `get`.

## License

Copyright © 2015 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
