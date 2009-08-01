(ns com.infolace.github-hook
  (:require (ring dump jetty) 
            [clojure.contrib.repl-utils :as ru]
            (org.danlarkin [json :as json]))
  (:use 
   [clojure.contrib.pprint :only (pprint cl-format)]
   [clojure.contrib.duck-streams :only (slurp*)]
   [com.infolace.parse-params :only (parse-params)])
  (:import java.net.URLDecoder)
  (:import java.util.concurrent.ArrayBlockingQueue))

(def myout *out*)

(defn print-date []
  (let [d (java.util.Date.)]
    (cl-format true "~%~{~a ~a~}:~%"
               (map #(.format % (java.util.Date.)) 
                    [(java.text.DateFormat/getDateInstance)
                     (java.text.DateFormat/getTimeInstance)]))))
(defn app [q req]
  (print-date)
  (pprint req myout)
  (if (and (= (:scheme req) :http),
           (= (:request-method req) :post),
           (= (:query-string req) nil),
           (= (:content-type req) "application/x-www-form-urlencoded"),
           (= (:uri req) "/github-post")) 
    (do (.add q [(json/decode-from-str (:payload (parse-params (slurp* (:body req)) #"&")))])
        {:status  200
         :headers {"Content-Type" "text/html"}})
    {:status  404
     :headers {"Content-Type" "text/html"}}))


(comment
  (ring.jetty/run {:port 8080} app)
)

(defn processor [f]
  (let [q (ArrayBlockingQueue. 1000)
        thread (Thread. (fn [] 
                          (loop [item (.take q)]
                            (apply f item)
                            (recur (.take q)))))]
    (.start thread)
    [q thread]))

(defn handle-hook [payload]
  (pprint payload myout))

(defn hook-server [port]
  (let [[q thread] (processor handle-hook)]
    (ring.jetty/run {:port port} (partial app q))))
