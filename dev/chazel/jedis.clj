(ns chazel.jedis
  (:import [java.io Serializable]))

(defprotocol JediProtocol
  (getName [this])
  (getEditor [this]))

(deftype Jedi [name editor]
  Serializable
  JediProtocol
    (getName [_] name)
    (getEditor [_] editor)
  Object
    (toString [_]
      (str "{:name " name " :editor " editor "}")))
