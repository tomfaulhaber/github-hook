(ns com.infolace.github-hook
  (:gen-class)
  (:require [ring.handler.dump]
            [ring.adapter.jetty]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.data.json :as json])
  (:use [com.infolace.parse-params :only (parse-params)])
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
  (pp/pprint req myout)
  (pp/cl-format myout "~%")
  (if (and (= (:scheme req) :http),
           (= (:request-method req) :post),
           (= (:query-string req) nil),
           (= (:content-type req) "application/x-www-form-urlencoded"),
           (= (:uri req) "/github-post"))
    ;; TODO: respond correctly to the client when an exception is thrown
    (do (fill (json/read-str (:payload (parse-params (slurp (:body req)) #"&"))))
        {:status  200
         :headers {"Content-Type" "text/html"}})
    {:status  404
     :headers {"Content-Type" "text/html"}}))

(defn autodoc
  ([project] (autodoc project "https://github.com/clojure"))
  ([project url]
     [[:repository :url] (str url "/" project)
      [:ref] "refs/heads/master"
      {:cmd ["sh" "./run.sh" project] :dir "/home/tom/src/clj/autodoc-stable"}]))

(def action-table
  [(autodoc "clojure")
   (autodoc "incanter" "https://github.com/liebke")
   (autodoc "algo.generic")
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
   (autodoc "data.codec")
   (autodoc "data.csv")
   (autodoc "data.finger-tree")
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
   (autodoc "tools.emitter.jvm")
   (autodoc "tools.cli")
   (autodoc "tools.logging")
   (autodoc "tools.macro")
   (autodoc "tools.namespace")
   (autodoc "tools.nrepl")
   (autodoc "tools.reader")
   (autodoc "tools.trace")
   [[:repository :url] "https://github.com/tomfaulhaber/hook-test"
       [:ref] "refs/heads/master"
       {:cmd ["echo" "got here"] :dir "/home/tom/src/clj/contrib-autodoc"}]])


(defn match-elem
  "Determine whether a given request, m, matches the action table element, elem."
  [m elem]
  (loop [elem elem]
    (let [ks (first elem)
          rem (next elem)]
      (if (nil? rem)
        ks
        (when (= (apply get-in m ks []) (first rem))
          (recur (next rem)))))))

(defn match-table
  "Match a request, m, against the action-table"
  [m]
  (some #(match-elem m %) action-table))

(defn handle-payload
  "Called when a request is dequeued with the parsed json payload. Sees if the
request matches anything in the action-table and, if so, executes the associated shell
command."
  [payload]
  (pp/pprint payload myout)
  (pp/cl-format myout "~%")
  (when-let [params (match-table payload)]
    ;; The following throws an exception (in 1.1) since ~W doesn't cause myout to get wrapped.
    ;; Need to test it with clojure.pprint in master and see if it's been fixed (or fix it!)
    ;;(pp/cl-format myout "matched: ~%~W~%" params)
    (pp/cl-format myout "matched: ~%") (pp/pprint params myout) (pp/cl-format myout "~%")
    (pp/cl-format myout "~a~%" (apply shell/sh (concat  (:cmd params) [:dir (:dir params)])))
    (pp/cl-format myout "Execution complete~%----------------------------------------~%")))

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
