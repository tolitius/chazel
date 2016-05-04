# chazel

Hazelcast bells and whistles under the Clojure belt

![](https://clojars.org/chazel/latest-version.svg)

- [Creating a Cluster](#creating-a-cluster)
- [Working with Data Structures](#working-with-data-structures)
- [Connecting as a Client](#connecting-as-a-client)
- [Distributed SQL Queries](#distributed-sql-queries)
  - [Jedi Order](#jedi-order)
  - [Jedi SQL](#jedi-sql)
  - [Query Results Format](#query-results-format)
- [Distributed Tasks](#distributed-tasks)
  - [Sending Runnables](#sending-runnables)
  - [Sending Callables](#sending-callables)
  - [Task Knobs](#task-knobs)
    - [Send to All](#send-to-all)
    - [Instance](#instance)
    - [Executor Service](#executor-service)
    - [All Together](#all-together)
- [Distributed Reliable Topic](#distributed-reliable-topic)
  - [Replaying Events](#replaying-events)
- [Map Event Listeners](#map-event-listeners)
- [Serialization](#serialization)
- [License](#license)

## Creating a Cluster

```clojure
user=> (require '[chazel :refer :all])
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

## Working with Data Structures

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

## Connecting as a Client

```clojure
user=> (def c (client-instance {:group-password "dev-pass", 
                                :hosts ["127.0.0.1"], 
                                :retry-ms 5000, 
                                :retry-max 720000, 
                                :group-name "dev"}))
                                
INFO: connecting to:  {:group-password dev-pass, :hosts [127.0.0.1], :retry-ms 5000, :retry-max 720000, :group-name dev}

user=> c
#<HazelcastClientProxy com.hazelcast.client.impl.HazelcastClientInstanceImpl@42b215c8>
```

In case it could not connect, it would retry according to `:retry-ms` and `:retry-max` values:

```clojure
WARNING: Unable to get alive cluster connection, try in 5000 ms later, attempt 1 of 720000.
WARNING: Unable to get alive cluster connection, try in 5000 ms later, attempt 2 of 720000.
...
```

## Distributed SQL Queries

Hazelcast has a concept of [Distributed Query](http://docs.hazelcast.org/docs/3.5/manual/html/distributedquery.html) with quite rich [SQL syntax supported](http://docs.hazelcast.org/docs/3.5/manual/html/querysql.html).

chazel embraces it into a single function `select`. Let's look at the example that is taught at Jedi Order.

### Jedi Order

Since Hazelcast internally works with Java objects, it relies on getter/setter accessors for its full SQL power. This is not that bad as it might seem at the first glance. Think Google Protobufs, or many other Java serialization protocols, the all produce objects with getters and setters.

Let's call for the Jedi Masters:

```clojure
chazel=> (require '[chazel :refer :all])
chazel=> (import '[chazel.jedis Jedi])

chazel=> (def masters {1 (Jedi. "Yoda" "vim")
                       2 (Jedi. "Mace Windu" "emacs")
                       3 (Jedi. "Qui-Gon Jinn" "cursive")
                       4 (Jedi. "Obi-Wan Kenobi" "vim")
                       5 (Jedi. "Luke Skywalker" "vim")
                       6 (Jedi. "Mara Jade Skywalker" "emacs")
                       7 (Jedi. "Leia Organa Solo" "emacs")
                       8 (Jedi. "Jaina Solo Fel" "atom")})
```

[Jedi](test/chazel/jedis.clj#L5) is an example type that has `name` and `editor` fields. 

You guessed it right, we are going to rely on SQL query powers to finally find out which editors Jedis Masters use!

### Jedi SQL

Now as we called upon the masters, let's put them into a Hazelcast map. We can use a `put-all!` for that:

```clojure
chazel=> (def jedis (hz-map "jedis"))
#'chazel/jedis

chazel=> (put-all! jedis masters)
```

Let's now run some _distributed_ SQL on the new Jedi Master database:

```clojure
chazel=> (select jedis "editor = vim")

#{#<Jedi {:name Obi-Wan Kenobi :editor vim}> 
  #<Jedi {:name Yoda :editor vim}> 
  #<Jedi {:name Luke Skywalker :editor vim}>}
```

```clojure
chazel=> (select jedis "name like %Sky%")

#{#<Jedi {:name Luke Skywalker :editor vim}> 
  #<Jedi {:name Mara Jade Skywalker :editor emacs}>}
```

```clojure
chazel=> (select jedis "name like %Sky% and editor != emacs")

#{#<Jedi {:name Luke Skywalker :editor vim}>}
```

niice!

In case a database / map is large, we can add [field indices](http://docs.hazelcast.org/docs/3.5/manual/html/queryindexing.html)

```clojure
chazel=> (add-index jedis "editor")
```

now this query will run _waaay_ faster:

```clojure
chazel=> (select jedis "editor = vim"))

#{#<Jedi {:name Obi-Wan Kenobi :editor vim}> 
  #<Jedi {:name Yoda :editor vim}> 
  #<Jedi {:name Luke Skywalker :editor vim}>}
```

for larger datasets.

#### Query Results Format

By default a distributed query will return a set:

```clojure
chazel=> (type (select jedis "editor = vim"))
clojure.lang.PersistentHashSet
```

In case you need an actual submap: i.e. all the matching map entries (k,v pairs), just ask:

```clojure
chazel=> (select jedis "editor = vim" :as :map)

{1 #object[chazel.jedis.Jedi 0x44bb1c0a "{:name Yoda :editor vim}"],
 4 #object[chazel.jedis.Jedi 0x4ad0c3c5 "{:name Obi-Wan Kenobi :editor vim}"],
 5 #object[chazel.jedis.Jedi 0x2725fbd0 "{:name Luke Skywalker :editor vim}"]}
```

```clojure
chazel=> (type (select jedis "editor = vim" :as :map))
clojure.lang.PersistentArrayMap
```

For a better interop, you can also ask for a Hazelcast "native" type:

```clojure
chazel=> (select jedis "editor = vim" :as :native)

#{#object[java.util.AbstractMap$SimpleImmutableEntry 0x69cfa867 "1={:name Yoda :editor vim}"]
  #object[java.util.AbstractMap$SimpleImmutableEntry 0x3b0a56f9 "4={:name Obi-Wan Kenobi :editor vim}"]
  #object[java.util.AbstractMap$SimpleImmutableEntry 0x3b498787 "5={:name Luke Skywalker :editor vim}"]}
```

```clojure
chazel=> (type (select jedis "editor = vim" :as :native))
com.hazelcast.map.impl.query.QueryResultCollection
```

In case a wrong / unknown format is asked for, chazel will tell you so:

```clojure
chazel=> (select jedis "editor = vim" :as :foo)

ERROR: can't return a result of a distributed query as ":foo" (an unknown format you provided). query: "editor = vim", running on: "jedis"
```

## Distributed Tasks

Sending work to be done remotely on the cluster is very useful, and Hazelcast has a [rich set of APIs](http://docs.hazelcast.org/docs/3.5/javadoc/com/hazelcast/core/IExecutorService.html) to do that.

chazel does not implement all the APIs, but it does provide a simple way of sending tasks to be executed remotely on the cluster:

```clojure
(task do-work)
```

done.

`task` here is a chazel's built-in function, and `do-work` is your function.

A couple of gotchas:

* `do-work` must exist on both: sending and "doing the work" JVMs
* in case you'd like to pass a function with arguments use `partial`

```clojure
(task (partial do-work arg1 arg2 ..))
```

### Sending Runnables

In example above `do-work` gets wrapped into a Runnable internal chazel [Task](https://github.com/tolitius/chazel/blob/6bfd0275239ea96a9240efb7abed6adaafd8ee6d/src/chazel/chazel.clj#L194)
and gets send to the cluster to execute.

Say the function we are sending is:

```clojure
(defn do-work [& args]
  (println "printing remotely..." args)
  (str "doing work remotely with args: " args))
```

If we send it with `(task do-work)`, you'll see `printing remotely... nil` in logs of a cluster member that picked up this task.
But you won't see `doing the work...` since it was silently executed on that member.

### Sending Callables

In case you do want to know when the task is done, or you'd like to own the result of the tasks, you can send a task that will return you a future back.
chazel calls this kind of task an `ftask`:

```clojure
chazel=> (ftask do-work)
#<ClientCancellableDelegatingFuture com.hazelcast.client.util.ClientCancellableDelegatingFuture@6148ce19>
```

In case of `ftask` chazel also wraps a function `do-work` into  [Task](https://github.com/tolitius/chazel/blob/6bfd0275239ea96a9240efb7abed6adaafd8ee6d/src/chazel/chazel.clj#L194), but now it cares of Task's Callable skills, hence we get a tasty future back. Let's deref it:

```clojure
chazel=> @(ftask do-work)
"doing work remotely with args: "
```

and send it some args:

```clojure
chazel=> @(ftask (partial do-work 42 "forty two"))
"doing work remotely with args: (42 \"forty two\")"
```

### Task Knobs

#### Send to All

A task that is sent with `task` of `ftask` by default will be picked up by any one member to run it.
Sometimes it is needed to send a task to be executed on all of the cluster members:

```clojure
chazel=> (ftask (partial do-work 42 "forty two") :members :all)
{#<MemberImpl Member [192.168.1.4]:5702> #<ClientCancellableDelegatingFuture com.hazelcast.client.util.ClientCancellableDelegatingFuture@2ae5cde4>,
 #<MemberImpl Member [192.168.1.4]:5701> #<ClientCancellableDelegatingFuture com.hazelcast.client.util.ClientCancellableDelegatingFuture@7db6db4>}
```

here we have a small local two node cluster, and what comes back is a {member future} map. Let's get all the results:

```clojure
chazel=> (def work (ftask (partial do-work 42 "forty two") :members :all))
#'chazel/work

chazel=> (into {} (for [[m f] work] [m @f]))
{#<MemberImpl Member [192.168.1.4]:5702>
 "doing work remotely with args: (42 \"forty two\")",
 #<MemberImpl Member [192.168.1.4]:5701>
 "doing work remotely with args: (42 \"forty two\")"}
```

#### Instance

By default chazel will look for a client instance, if it is active, it will use that, if not it will get a server instance instead.
But in case you'd like to use a concrete instance in order to send out tasks from you can:

```clojure
(task do-work :instance your-instance)
```

#### Executor Service

By default chazel will use a `"default"` executor service to submit all the tasks to.
But in case you'd like to pick a different one, you can:

```clojure
(task do-work :exec-svc-name "my-es")
```

#### All Together

All the options can be used with `task` and `ftask`:

```clojure
(task do-work :instance "my instance" :exec-svc-name "my-es")
```

```clojure
(ftask do-work :instance "my instance" :memebers :all :exec-svc-name "my-es")
```

<div id="reliable-topic"/>
## Distributed Reliable Topic

Hazelcast's Reliable Topic is backed by a [Ringbuffer](http://blog.hazelcast.com/ringbuffer-data-structure/) which amongst other benefits (i.e. not destructive operations, ttl, batching, etc.) sequences all the messages, which allows for an interesting replay use cases.

Since this is Hazelcast, we are dealing with a cluster of nodes, and depending on `backup-count` (a.k.a. quorum) this reliable topic is well _distributed_, which means it allows for better locality as well as higher availability: i.e. cluster may lose nodes, but all the topic messages will be still there to consume.

### Processing Payments

Let's say we have a system that publishes payments. We can send these payments to a reliable topic, and have some consumers that would be responsible to process these payments. So let's create this reliable topic:

```clojure
chazel=> (def payments (hz-reliable-topic :payments))
#'chazel/payments
```

and a simple functions that would process a single payment:

```clojure
chazel=> (defn process-payment [p] (info "processing payment" p))
#'chazel/process-payment
```

We can now add this function as one of the topic listeners by calling `add-message-listener` on the topic:

```clojure
chazel=> (add-message-listener payments process-payment)
"f3216455-f9c8-46ef-976a-cae942b15a8d"
```

This listener UUID can later be used to `remove-message-listener`.

Now let's publish some payments:

```clojure
chazel=> (publish payments {:name "John" :amount 4200.42M})

INFO: processing payment {:name John, :amount 4200.42M}

chazel=> (publish payments {:name "Kevin" :amount 2800.28M})

INFO: processing payment {:name Kevin, :amount 2800.28M}

chazel=> (publish payments {:name "Jessica" :amount 3400.34M})

INFO: processing payment {:name Jessica, :amount 3400.34M}
```

You can see that each payment is picked up by the listener and processed.

### Replaying Events

So far so good, but not much different from a regular pub/sub topic. Let's make it more interesting.

Say we have some problems with payments and we need to audit every payment that was sent. With a regular topic it would be hard to do (if at all possible) since we need to audit _all_ the payments: from the past and ongoing. With Hazelcast's Reliable Topic is not an issue, since it is backed by a Ringbuffer and all the messages are sequenced, we can just ask to replay the messages from an arbitrary sequence number.

First let's create a function that will do the audit work:

```clojure
chazel=> (defn audit-payment [p] (info "auditing payment" p))
#'chazel/audit-payment
```

and add it as a _reliable_ listener:

```clojure
chazel=> (add-reliable-listener payments audit-payment {:start-from 0})
"d274fab1-7f0f-47f9-a53a-58b35a4c68d1"

INFO: auditing payment {:name John, :amount 4200.42M}
INFO: auditing payment {:name Kevin, :amount 2800.28M}
INFO: auditing payment {:name Jessica, :amount 3400.34M}
```

Interesting, you see what happened? All the payments starting from the sequence `0` (the very beginning) were simply replayed and audited: niice!

Let's publish more payments:

```clojure
chazel=> (publish payments {:name "Rudolf" :amount 1234.56M})

INFO: auditing payment {:name Rudolf, :amount 1234.56M}
INFO: processing payment {:name Rudolf, :amount 1234.56M}

chazel=> (publish payments {:name "Nancy" :amount 6543.21M})

INFO: auditing payment {:name Nancy, :amount 6543.21M}
INFO: processing payment {:name Nancy, :amount 6543.21M}
```

Now _every_ ongoing payment gets processed _and_ audited, since there are two listeners attached to a topic.

Let's replay them all again, just for fun:

```clojure
chazel=> (add-reliable-listener payments audit-payment {:start-from 0})
"e2bd4912-7ccb-48b7-8102-b31e5660f68d"

INFO: auditing payment {:name John, :amount 4200.42M}
INFO: auditing payment {:name Kevin, :amount 2800.28M}
INFO: auditing payment {:name Jessica, :amount 3400.34M}
INFO: auditing payment {:name Rudolf, :amount 1234.56M}
INFO: auditing payment {:name Nancy, :amount 6543.21M}
```

niice!

_there are other options that can be provided to a reliable listener: i.e. `start-from` `store-seq` `loss-tolerant?` `terminal?` if needed_

## Map Event Listeners

Hazelcast has map entry listeners which can be attached to maps and listen on different operations, namely:

* entry added
* entry updated
* entry removed
* entry evicted

chazel has all 3 listeners available as wrapper functions and ready to roll:

* entry-added-listener
* entry-updated-listener
* entry-removed-listener
* entry-evicted-listener

A chazel map entry listener would take a function and apply it every time the event takes place:

```clojure
chazel=> (def m (hz-map "appl"))
#'chazel/m

chazel=> (put! m 42 1)

chazel=> m
{42 1}
```

nothing fancy, usual map business. now let's add an update listener:

```clojure
chazel=> (def ul (entry-updated-listener (fn [k v ov] (println "updated: " {:k k :v v :ov ov}))))
#'chazel/ul
chazel=> (def id (add-entry-listener m ul))
#'chazel/id
chazel=> id
"927b9530-630c-4bbb-995f-9c74815d9ca9"
chazel=>
```

`ov` here is an `old value` that is being updated.

When the listener is added, hazelcast assigns a `uuid` to it. We'll use it a bit later. For now let's see how the listener works:

```clojure
chazel=> (put! m 42 2)
1
updated:  {:k 42, :v 2, :ov 1}
chazel=>

chazel=> (put! m 42 3)
updated:  {:k 42, :v 3, :ov 2}
2
```

now every time an entry gets updated a function we created above gets applied.

Since we have listener id, we can use it to remove this listener from the map:

```clojure
chazel=> (remove-entry-listener m id)
true

chazel=> (put! m 42 4)
3
chazel=> m
{42 4}
```

all back to vanilla, no listeners involved, map business.

## Serialization

Serialization is a big deal when hazelcast nodes are distributed, or when you connect to a remote hazelcast cluster. 
chazel solves this problem by delegating it to an optional serializer.

To start off, chazel has a [transit](https://github.com/cognitect/transit-clj) seriailzer ready to go:

```clojure
user=> (require '[chazel.serializer :refer [transit-in transit-out]])
user=> (def m (hz-map "amzn"))

user=> (put! m "bids" {:opening [429 431 430 429] :nbbo [428 430 429 427]} transit-out)
#<byte[] [B@5d9d8664>
user=>
```
notice `transit-out`, it is an optional function to `put!` that will be applied to the value before the hazelcast `.put` is called. In this case a value will be serialized with transit.

```clojure
user=> (cget m "bids")
#<byte[] [B@638b6eec>
```

a default chazel's `cget` will return the value the way hazelcast has it stored: as a byte array. Similarly to `put!`, `cget` also takes in an optional function that is applied after the value is fetched from hazelcast:

```clojure
user=> (cget m "bids" transit-in)
{:opening [429 431 430 429], :nbbo [428 430 429 427]}

user=> (type (cget m "bids" transit-in))
clojure.lang.PersistentArrayMap
```

In case you need to use a different serializer, you can either send a pull request updating [chazel.serializer](https://github.com/tolitius/chazel/blob/master/src/chazel/serializer.clj), or by specifying your own "secret" serialize function in `put!` and `cget`.

## License

Copyright © 2015 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
