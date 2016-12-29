(ns chazel.core-test
  (:require [clojure.test :refer :all]
            [chazel.core :refer :all]))

(defn do-work [& args]
  (println "printing remotely..." args)
  (str "doing work remotely with args: " args))
