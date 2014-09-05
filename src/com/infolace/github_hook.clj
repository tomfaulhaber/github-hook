(ns com.infolace.github-hook
  (:gen-class)
  (:require [ring.handler.dump]
            [ring.adapter.jetty]
            [clojure.data.json :as json]
            [clojure.java.shell2 :as shell]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:use
   [clojure.core.match :only [match]]
   [com.infolace.parse-params :only (parse-params)])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.lang.ref WeakReference]))

;;; Preserve out so we can spew out logging data even when ring has
;;; rebound *out*

(def myout *out*)

(defn print-date
  "Display a formatted version of the current date and time on the stream myout"
  []
  (let [d (java.util.Date.)]
    (pp/cl-format myout "~%~{~a ~a~}:~%"
               (map #(.format % (java.util.Date.))
                    [(java.text.DateFormat/getDateInstance)
                     (java.text.DateFormat/getTimeInstance)]))))

;;; I moved fill-queue here from the old clojure-contrib seq-utils
;;; since it got dropped from during the contrib conversion

; based on work related to Rich Hickey's seque.
; blame Chouser for anything broken or ugly.
(defn fill-queue
  "filler-func will be called in another thread with a single arg
'fill'. filler-func may call fill repeatedly with one arg each
time which will be pushed onto a queue, blocking if needed until
this is possible. fill-queue will return a lazy seq of the values
filler-func has pushed onto the queue, blocking if needed until each
next element becomes available. filler-func's return value is ignored."
  ([filler-func & optseq]
    (let [opts (apply array-map optseq)
          apoll (:alive-poll opts 1)
          q (LinkedBlockingQueue. (:queue-size opts 1))
          NIL (Object.) ;nil sentinel since LBQ doesn't support nils
          weak-target (Object.)
          alive? (WeakReference. weak-target)
          fill (fn fill [x]
                 (if (.get alive?)
                   (if (.offer q (if (nil? x) NIL x) apoll TimeUnit/SECONDS)
                     x
                     (recur x))
                   (throw (Exception. "abandoned"))))
          f (future
              (try
                (filler-func fill)
                (finally
                  (.put q q))) ;q itself is eos sentinel
              nil)] ; set future's value to nil
      ((fn drain []
         weak-target ; force closing over this object
         (lazy-seq
           (let [x (.take q)]
             (if (identical? x q)
               @f ;will be nil, touch just to propagate errors
               (cons (if (identical? x NIL) nil x)
                     (drain))))))))))


(defn app
  "The function invoked by ring to process a single request, req. It does a check to make
sure that it's really a webhook request (post to the right address) and, if so, calls fill
with the parsed javascript parameters (this will queue up the request for later processing.
Then it returns the appropriate status and header info to be sent back to the client."
  [fill req]
  (print-date)
  (match [req]
         [{:scheme :http, :request-method :post, :query-string nil,
           :content-type "application/x-www-form-urlencoded", :uri "/github-post"}]
         (do (fill (json/read-str (:payload (parse-params (slurp (:body req)) #"&"))))
             {:status  200
              :headers {"Content-Type" "text/html"}})
         :else
         (do
           (pp/cl-format myout "Spurious request received:~%~w~%" req)
           (pp/cl-format myout "Input ignored~%----------------------------------------~%")
           {:status  404
            :headers {"Content-Type" "text/html"}})))

(defn run
  "Run a command and print the output"
  [cmd-and-args dir]
  (let [{:keys [exit]} (apply shell/sh (concat cmd-and-args
                                               [:dir dir :out :pass :err :pass]))]
    (when-not (zero? exit)
      (pp/cl-format myout "ERROR: non-zero exit code: ~d~%" exit))))

(defn autodoc
  ([project] (autodoc project "clojure"))
  ([project owner]
     [[project owner]
      {:cmd ["sh" "./run.sh" project] :dir "/home/tom/src/clj/autodoc-stable"}]))

(def commit-actions
  (into {}
        [(autodoc "clojure")
         (autodoc "incanter" "incanter")
         (autodoc "algo.generic")
         (autodoc "algo.graph")
         (autodoc "algo.monads")
         (autodoc "core.async")
         (autodoc "core.cache")
         (autodoc "core.contracts")
         (autodoc "core.incubator")
         (autodoc "core.logic")
         (autodoc "core.match")
         (autodoc "core.memoize")
         (autodoc "core.rrb-vector")
         (autodoc "core.typed")
         (autodoc "core.unify")
         (autodoc "data.avl")
         (autodoc "data.codec")
         (autodoc "data.csv")
         (autodoc "data.finger-tree")
         (autodoc "data.fressian")
         (autodoc "data.generators")
         (autodoc "data.json")
         (autodoc "data.priority-map")
         (autodoc "data.xml")
         (autodoc "data.zip")
         (autodoc "java.classpath")
         (autodoc "java.data")
         (autodoc "java.jdbc")
         (autodoc "java.jmx")
         (autodoc "math.combinatorics")
         (autodoc "math.numeric-tower")
         (autodoc "test.generative")
         (autodoc "tools.analyzer")
         (autodoc "tools.analyzer.jvm")
         (autodoc "tools.analyzer.js")
         (autodoc "tools.emitter.jvm")
         (autodoc "tools.cli")
         (autodoc "tools.logging")
         (autodoc "tools.macro")
         (autodoc "tools.namespace")
         (autodoc "tools.nrepl")
         (autodoc "tools.reader")
         (autodoc "tools.trace")
         [["hook-test" "tomfaulhaber"]
          {:cmd ["echo" "got here"] :dir "."}]]))

(defn handle-commit
  "Handle a commit message, if it's on our list"
  [name owner commits]
  (if-let [action (get commit-actions [name owner])]
    (let [commit-info (map #(vector (.substring (% "id") 0 6)
                                    ((% "committer") "name")
                                    (str/split-lines (% "message")))
                           commits)]
      (pp/cl-format myout "Received master commit for ~a/~a~%" owner name)
      (pp/cl-format myout "Commits: ~<~:i~:{~a ~a    ~<~:i~@{~a~^~@:_~}~:>~@:_~}~:>" [commit-info])
      (run (:cmd action) (:dir action)))
    (pp/cl-format myout "WARNING: master commit received for unsupported project ~a/~a~%" owner name)))

(defn handle-gh-pages
  "When a gh-pages branch is updated, we may need to regenerate the master index"
  [name owner]
  (if-let [action (get commit-actions [name owner])]
    (do
      (pp/cl-format myout "Starting a Clojure master index update because gh-pages was updated for ~a/~a~%" owner name)
      (pp/cl-format myout "WARNING: Index building is currently disabled, you must run this step by hand.~%")
      #_(run ["bash" "./run.sh"]  "/home/tom/src/clj/contrib-index"))
    (pp/cl-format myout "WARNING: gh-pages commit received for unsupported project ~a/~a~%" owner name)))

(defn handle-payload
  "Called when a request is dequeued with the parsed json payload. Sees if the
request matches anything in the action-table and, if so, executes the associated shell
command."
  [payload]
  (match [payload]
         [{"repository" {"name" name "owner" {"name" owner}}
           "commits" commits
           "ref" "refs/heads/master"}]
         (handle-commit name owner commits)

         [{"repository" {"name" name "owner" {"name" owner}}
           "ref" "refs/heads/gh-pages"}]
         (handle-gh-pages name owner)

         [{"repository" {"name" name "owner" {"name" owner} "commits" commits}
           "ref" ref}]
         (pp/cl-format myout "Commit to ignored branch for ~a/~a (ref: ~a)" owner name ref)

         :else
         (pp/cl-format myout "Payload not understood:~%~w~%" payload))
  (pp/cl-format myout "Payload processing complete~%----------------------------------------~%"))

(defn hook-server
  "Build a simple webhook server on the specified port. Invokes ring to fill a blocking queue,
whose elements are processed by handle-payload."
  [port]
  (doseq [payload (fill-queue (fn [fill]
                                (ring.adapter.jetty/run-jetty (partial app fill) {:port port}))
                              :queue-size 10)]
    (handle-payload payload)))

(defn -main []
  (hook-server 8080))
