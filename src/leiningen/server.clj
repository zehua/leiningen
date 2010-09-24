(ns leiningen.server
  (:use [clojure.java.io :only [file writer]]
        [leiningen.compile :only [eval-in-project]])
  (:import [java.net Socket]
           [java.io OutputStreamWriter InputStreamReader File]))

(defn repl-form [project host port]
  (let [init-form [:init `#(let [is# ~(:repl-init-script project)
                                 mn# '~(:main project)]
                             (when (and is# (.exists (File. (str is#))))
                               (load-file is#))
                             (if mn#
                               (doto mn# require in-ns)
                               (in-ns '~'user)))]]
    `(do (ns ~'user
           (:import [java.net ~'InetAddress ~'ServerSocket ~'Socket
                     ~'SocketException]
                    [java.io ~'InputStreamReader ~'OutputStream
                     ~'OutputStreamWriter ~'PrintWriter]
                    [clojure.lang ~'LineNumberingPushbackReader]))
           (try (require ['~'clojure.java.shell])
                (require ['~'clojure.java.browse])
                (catch Exception _#))
           (use ['~'clojure.main :only ['~'repl]])
         (let [server# (ServerSocket. ~port 0 (~'InetAddress/getByName ~host))
               acc# (fn [s#]
                      (let [ins# (.getInputStream s#)
                            outs# (.getOutputStream s#)]
                        (doto (Thread.
                               #(binding [*in* (-> ins# InputStreamReader.
                                                   LineNumberingPushbackReader.)
                                          *out* (OutputStreamWriter. outs#)
                                          *err* (PrintWriter. outs# true)]
                                  (try
                                    (clojure.main/repl ~@init-form)
                                    (catch ~'SocketException _#
                                      (doto s#
                                        .shutdownInput
                                        .shutdownOutput
                                        .close)))))
                          .start)))]
           (spit "/tmp/lein-server" "ran")
           (doto (Thread. #(when-not (.isClosed server#)
                             (try
                               (acc# (.accept server#))
                               (catch ~'SocketException _#))
                             (recur)))
             .start)
           (format "REPL started; server listening on %s:%s." ~host ~port)))))

(defn get-socket-file [project]
  (file (:root project) ".lein-server-socket"))

(defn running? [project]
  (let [socket-file (get-socket-file project)]
    (and (.exists socket-file)
         (try (.close (Socket. "localhost" (Integer. (slurp socket-file))))
              (catch Exception _)))))

(defn server-host-port [{:keys [repl-port repl-host]}]
  [(Integer. (or repl-port
                 (System/getenv "LEIN_REPL_PORT")
                 (dec (+ 1024 (rand-int 64512)))))
   (or repl-host
       (System/getenv "LEIN_REPL_HOST")
       "localhost")])

(defmulti ^{:arglists '[(project subcommand & args)]} server
  (fn [project subcommand & args] (keyword subcommand)))

(defmethod server :start [project _ & args]
  (if (running? project)
    (println "Already running a server.")
    (let [[port host] (server-host-port project)]
      (println "Starting server on" host "port" port)
      (spit (get-socket-file project) port)
      (eval-in-project project (repl-form project host port)
                       (fn [java]
                         ;; TODO: this has no effect. AAAAAAAAAAAANT!!!
                         (.setSpawn java true))))))

(defmethod server :stop [project _ & args]
  (let [socket (get-socket-file project)]
    (.write "(System/exit 0)\n" (writer (Socket. (Integer. (slurp socket)))))
    (.delete socket)))
