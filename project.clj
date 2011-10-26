(defproject github-hook "1.0.0-SNAPSHOT"
  :description "The hook program that listens to github checkins and launches autodoc runs"
  :url "http://github.com/tomfaulhaber/github-hook"
  :main com.infolace.github-hook
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.danlarkin/clojure-json "1.2-SNAPSHOT"]
                 [ring/ring "0.3.3"]])
