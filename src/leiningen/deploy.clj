(ns leiningen.deploy
  (:use [leiningen.core :only [abort]]
        [leiningen.jar :only [jar]]
        [leiningen.util.maven :only [make-model]]
        [leiningen.deps :only [make-repository]])
  (:import [org.apache.maven.artifact.ant DeployTask Pom]
           [org.apache.maven.project MavenProject]
           [java.io File]))

(defn deploy
  ([project repo id]
     (doto (DeployTask.)
       ;; (.getSupportedProtocols) ;; see note re: exceptions in deps.clj
       (.setProject lancet/ant-project)
       (.setFile (File. (jar project)))
       ;; grrrr @ maven-ant-tasks; why can't you work with Maven Models?
       ;; We may have to round-trip to disk here.
       (.addPom (doto (Pom.)
                  (.setMavenProject (MavenProject. (make-model project)))))
       (.addRemoteRepository (make-repository [id repo]))
       (.execute)))
  ([project repo]
     (deploy project repo "repository"))
  ([project]
     (when-not (:deploy-to project)
       (abort "Can't deploy without :deploy-to \"url\" in project.clj."))
     (if (string? (:deploy-to project))
       (deploy project (:deploy-to project))
       (apply project (:deploy-to project)))))
