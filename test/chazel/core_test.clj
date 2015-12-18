(ns chazel.core-test
  (:require [clojure.test :refer :all]
            [chazel :refer :all]))

(defn do-work [& args]
  (println "printing remotely..." args)
  (str "doing work remotely with args: " args))
