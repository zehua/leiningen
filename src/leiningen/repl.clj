(ns leiningen.repl
  "Start a repl session either with the current project or standalone."
  (:require [clojure.main])
  (:use [leiningen.core :only [exit]]
        [leiningen.compile :only [eval-in-project]]
        [clojure.java.io :only [copy]])
  (:import (java.net Socket InetAddress ServerSocket SocketException)
           (java.io OutputStreamWriter InputStreamReader File PrintWriter)
           (clojure.lang LineNumberingPushbackReader)))

(def *retry-limit* 100)

(defn repl-server [project host port & repl-options]
  (let [init-form [:init `#(let [is# ~(:repl-init-script project)
                                 mn# '~(:main project)]
                             (when (and is# (.exists (File. (str is#))))
                               (load-file is#))
                             (if mn#
                               (doto mn# require in-ns)
                               (in-ns '~'user)))]
        repl-options (concat init-form repl-options)]
    `(do (try ;; transitive requires don't work for stuff on bootclasspath
           (require ['~'clojure.java.shell])
           (require ['~'clojure.java.browse])
           ;; these are new in clojure 1.2, so swallow exceptions for 1.1
           (catch Exception _#))
         (use ['~'clojure.main :only ['~'repl]])
         (let [server# (ServerSocket. ~port 0 (InetAddress/getByName ~host))
               acc# (fn [s#]
                      (let [ins# (.getInputStream s#)
                            outs# (.getOutputStream s#)]
                        (doto (Thread.
                               #(binding [*in* (-> ins# InputStreamReader.
                                                   LineNumberingPushbackReader.)
                                          *out* (OutputStreamWriter. outs#)
                                          *err* (PrintWriter. outs# true)]
                                  (try
                                    (clojure.main/repl ~@repl-options)
                                    (catch SocketException e#
                                      (.printStackTrace e#))
                                    (finally (doto s#
                                               .shutdownInput
                                               .shutdownOutput
                                               .close)))))
                          .start)))]
           (doto (Thread. #(when-not (.isClosed server#)
                             (try
                               (acc# (.accept server#))
                               (catch SocketException e#
                                 (.printStackTrace e#)))
                             (recur)))
             .start)
           (symbol (format "REPL started; server listening on %s:%s."
                           ~host ~port))))))

(defn copy-out-loop [reader]
  (let [buffer (make-array Character/TYPE 1000)]
    (loop []
      (.write *out* buffer 0 (.read reader buffer))
      (flush)
      (Thread/sleep 100)
      (recur))))

(defn repl-client [reader writer & [socket]]
  (.start (Thread. #(copy-out-loop reader)))
  (loop [reader reader, writer writer]
    (let [input (read-line)]
      (when (and input (not= "" input))
        (.write writer (str input "\n"))
        (.flush writer)
        (recur reader writer)))))

(defn- connect-to-server [socket handler]
  (let [reader (InputStreamReader. (.getInputStream socket))
        writer (OutputStreamWriter. (.getOutputStream socket))]
    (handler reader writer socket)))

(defn poll-repl-connection
  ([port retries handler]
     (when (> retries *retry-limit*)
       (throw (Exception. "Couldn't connect")))
     (Thread/sleep 100)
     (let [val (try (connect-to-server (Socket. "localhost" port) handler)
                    (catch java.net.ConnectException _ ::retry))]
       (if (= ::retry val)
         (recur port (inc retries) handler)
         val)))
  ([port]
     (poll-repl-connection port 0 repl-client)))

(defn repl-socket-on [{:keys [repl-port repl-host]}]
  [(Integer. (or repl-port
                 (System/getenv "LEIN_REPL_PORT")
                 (dec (+ 1024 (rand-int 64512)))))
   (or repl-host
       (System/getenv "LEIN_REPL_HOST")
       "localhost")])

(defn repl
  "Start a repl session. A socket-repl will also be launched in the background
on a socket based on the :repl-port key in project.clj or chosen randomly.
Running outside a project directory will start a standalone repl session."
  ([] (repl {}))
  ([project]
     (let [[port host] (repl-socket-on project)
           server-form (apply repl-server project host port
                              (:repl-options project))
           ;; TODO: make this less awkward when we can break poll-repl-connection
           retries (- *retry-limit* (project :repl-retry-limit *retry-limit*))]
       (future (if (empty? project)
                 (clojure.main/with-bindings (println (eval server-form)))
                 (eval-in-project project server-form)))
       (poll-repl-connection port retries repl-client)
       (exit))))

