(ns leiningen.repl
  (:require [clojure.main])
  (:use [leiningen.core :only [exit]]
        [leiningen.compile :only [eval-in-project]]
        [leiningen.server :only [repl-form server-host-port]]
        [clojure.java.io :only [copy]])
  (:import [java.net Socket]
           [java.io OutputStreamWriter InputStreamReader File]))

(defn- copy-out [reader]
  (Thread/sleep 100)
  (.write *out* (.read reader))
  (while (.ready reader)
    (.write *out* (.read reader)))
  (flush))

(defn repl-client [reader writer]
  (copy-out reader)
  (let [eof (Object.)
        input (try (read *in* false eof)
                   (catch Exception e
                     (println "Couldn't read input.")))]
    (when-not (= eof input)
      (.write writer (str (pr-str input) "\n"))
      (.flush writer)
      (recur reader writer))))

(defn- connect-to-server [socket]
  (repl-client (InputStreamReader. (.getInputStream socket))
               (OutputStreamWriter. (.getOutputStream socket))))

(defn poll-repl-connection [port]
  (Thread/sleep 100)
  (when (try (connect-to-server (Socket. "localhost" port))
             (catch java.net.ConnectException _ :retry))
    (recur port)))

(defn repl
  "Start a repl session. A socket-repl will also be launched in the
background on a socket based on the :repl-port key in project.clj or
chosen randomly."
  ([] (repl {}))
  ([project]
     (let [[port host] (server-host-port project)
           server-form (repl-form project host port)]
       (future (try (if (empty? project)
                      (clojure.main/with-bindings
                        (println (eval server-form)))
                      (eval-in-project project server-form))
                    (catch Exception _)))
       (poll-repl-connection port)
       (exit 0))))
