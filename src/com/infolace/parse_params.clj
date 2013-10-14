(ns com.infolace.parse-params
  (:require [clojure.string :as str])
  (:import java.net.URLDecoder))

;;; This code was lifted from Compojure and allows us to parse the posted params

(defn assoc-vec
  "Associate a key with a value. If the key already exists in the map, create a
  vector of values."
  [map key val]
  (assoc map key
    (if-let [cur (map key)]
      (if (vector? cur)
        (conj cur val)
        [cur val])
      val)))

(defn urldecode
  "Decode a urlencoded string using the default encoding."
  [s]
  (URLDecoder/decode s "UTF-8"))

(defn parse-params
  "Parse parameters from a string into a map."
  [param-string separator]
  (reduce
    (fn [param-map s]
      (let [[key val] (str/split s #"=")]
        (assoc-vec param-map
          (keyword (urldecode key))
          (urldecode (or val "")))))
    {}
    (remove #(or (nil? %) (= % ""))
      (str/split param-string separator))))
