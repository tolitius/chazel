(ns chazel.jedis
  (:require [chazel :refer [hz-map add-index put-all! select]])
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
