(ns com.infolace.github-hook
  (:require (ring dump jetty) 
            [clojure.contrib.repl-utils :as ru]
            (org.danlarkin [json :as json]))
  (:use 
   [clojure.contrib.pprint :only (pprint cl-format)]
   [clojure.contrib.duck-streams :only (slurp*)]
   [clojure.contrib.seq-utils :only (fill-queue)]
   [clojure.contrib.shell-out :only (sh)]
   [com.infolace.parse-params :only (parse-params)]))

(def myout *out*)

(defn print-date []
  (let [d (java.util.Date.)]
    (cl-format myout "~%~{~a ~a~}:~%"
               (map #(.format % (java.util.Date.)) 
                    [(java.text.DateFormat/getDateInstance)
                     (java.text.DateFormat/getTimeInstance)]))))
(defn app [fill req]
  (print-date)
  (pprint req myout)
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


(comment
  (ring.jetty/run {:port 8080} app)
)

(def action-table
     [[[:repository :url] "http://github.com/richhickey/clojure-contrib"
       [:ref] "refs/heads/master"
       {:cmd ["ant"] :dir "/home/tom/src/clj/contrib-autodoc"}]
      [[:repository :url] "http://github.com/tomfaulhaber/hook-test"
       [:ref] "refs/heads/master"
       {:cmd ["echo" "got here"] :dir "/home/tom/src/clj/contrib-autodoc"}]])

(defn match-elem [m elem]
  (loop [elem elem]
    (let [ks (first elem)
          rem (next elem)]
      (if (nil? rem)
        ks
        (when (= (reduce #(get %1 %2) m ks) (first rem))
          (recur (next rem)))))))

(defn match-table [m]
  (some #(match-elem m %) action-table))

(defn handle-payload [payload]
  (pprint payload myout)
  (when-let [params (match-table payload)]
    (cl-format myout "~a~%" (apply sh (concat  (:cmd params) [:dir (:dir params)])))))

(defn hook-server [port]
  (doseq [payload (fill-queue (fn [fill]
                                (cl-format myout "here ~d~%" port)
                                (ring.jetty/run {:port port} (partial app fill))))]
    (handle-payload payload)))
