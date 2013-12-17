(defproject github-hook "1.0.0-SNAPSHOT"
  :description "The hook program that listens to github checkins and launches autodoc runs"
  :url "http://github.com/tomfaulhaber/github-hook"
  :main com.infolace.github-hook
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.match "0.2.0"]
                 [org.clojure/data.json "0.2.3"]
                 [ring/ring "1.2.0"]
                 [com.climate/java.shell2 "0.1.0"]])
