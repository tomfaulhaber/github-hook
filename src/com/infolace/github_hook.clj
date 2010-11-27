(ns com.infolace.github-hook
  (:gen-class)
  (:require [ring.handler.dump]
            [ring.adapter.jetty]
            [clojure.contrib.repl-utils :as ru]
            (org.danlarkin [json :as json]))
  (:use 
   [clojure.contrib.pprint :only (pprint cl-format)]
   [clojure.contrib.duck-streams :only (slurp*)]
   [clojure.contrib.seq-utils :only (fill-queue)]
   [clojure.contrib.shell-out :only (sh)]
   [com.infolace.parse-params :only (parse-params)]))

;;; Preserve out so we can spew out logging data even when ring has
;;; rebound *out*

(def myout *out*)

(defn print-date 
  "Display a formatted version of the current date and time on the stream myout"
  []
  (let [d (java.util.Date.)]
    (cl-format myout "~%~{~a ~a~}:~%"
               (map #(.format % (java.util.Date.)) 
                    [(java.text.DateFormat/getDateInstance)
                     (java.text.DateFormat/getTimeInstance)]))))

(defn app 
  "The function invoked by ring to process a single request, req. It does a check to make
sure that it's really a webhook request (post to the right address) and, if so, calls fill
with the parsed javascript parameters (this will queue up the request for later processing.
Then it returns the appropriate status and header info to be sent back to the client."
  [fill req]
  (print-date)
  (pprint req myout)
  (cl-format myout "~%")
  (if (and (= (:scheme req) :http),
           (= (:request-method req) :post),
           (= (:query-string req) nil),
           (= (:content-type req) "application/x-www-form-urlencoded"),
           (= (:uri req) "/github-post")) 
    ;; TODO: respond correctly to the client when an exception is thrown
    (do (fill (json/decode-from-str (:payload (parse-params (slurp* (:body req)) #"&"))))
        {:status  200
         :headers {"Content-Type" "text/html"}})
    {:status  404
     :headers {"Content-Type" "text/html"}}))

(def action-table
     [[[:repository :url] "https://github.com/clojure/clojure-contrib"
       [:ref] "refs/heads/master"
       {:cmd ["sh" "./run.sh" "clojure-contrib"] :dir "/home/tom/src/clj/autodoc-stable"}]
      [[:repository :url] "https://github.com/clojure/clojure"
       [:ref] "refs/heads/master"
       {:cmd ["sh" "./run.sh" "clojure"] :dir "/home/tom/src/clj/autodoc-stable"}]
      [[:repository :url] "https://github.com/liebke/incanter"
       [:ref] "refs/heads/master"
       {:cmd ["sh" "./run.sh" "incanter"] :dir "/home/tom/src/clj/autodoc-stable"}]
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
  (pprint payload myout)
  (cl-format myout "~%")
  (when-let [params (match-table payload)]
    ;; The following throws an exception (in 1.1) since ~W doesn't cause myout to get wrapped.
    ;; Need to test it with clojure.pprint in master and see if it's been fixed (or fix it!)
    ;;(cl-format myout "matched: ~%~W~%" params)
    (cl-format myout "matched: ~%") (pprint params myout) (cl-format myout "~%")
    (cl-format myout "~a~%" (apply sh (concat  (:cmd params) [:dir (:dir params)])))
    (cl-format myout "Execution complete~%----------------------------------------~%")))

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
