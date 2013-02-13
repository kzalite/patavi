(ns clinicico.R.analysis
  (:use validateur.validation
        clojure.walk
        clinicico.config
        clinicico.util.util)
  (:require [clinicico.R.util :as R]
            [clojure.java.io :as io]
            [clojure.string :as strs]
            [clojure.tools.logging :as log])
  (:import (org.rosuda.REngine REXP RList)
           (org.rosuda.REngine REXPDouble REXPLogical 
                               REXPFactor REXPInteger 
                               REXPString REXPGenericVector
                               REXPNull REngineException)))

(def validators (atom {"consistency" (validation-set 
                                       (presence-of :network))}))

(defn copy-to-r 
  [R file filename]
  (with-open [r-file (.createFile R filename)] 
    (io/copy file r-file)))

(defn load-analysis! 
  "Finds the R file with the associated analysis 
   name and load its into an RConnection."
  [R analysis]
  (let [script (io/as-file (io/resource (str "R/" analysis ".R")))]
    (if (nil? script)
      (throw (IllegalArgumentException. (str "Could not find specified analysis " analysis)))
      (do
        (copy-to-r R script analysis) 
        (.voidEval R (str "source('"script"')"))
        (.removeFile R analysis)))))

(defn- parse-results-list [^RList lst] 
  (let [names (.keys lst)
        conv {"matrix" #(R/parse-matrix %)
              "data.frame" #(map-cols-to-rows (R/into-clj %))}]
    (map (fn [k] (let [itm (R/as-list (.at lst k))
                       data (.at itm "data")
                       desc (R/into-clj (.at itm "description"))
                       data-type (R/into-clj (.at itm "type"))]
                   {:name k 
                    :description  desc
                    :data ((get conv data-type R/into-clj) data)})) names)))

(defn- url-for-img-path [path]
 (let [exploded (strs/split path #"\/")
       workspace (first (filter #(re-matches #"conn[0-9]*" %) exploded))
       img (last exploded)]
   (str base-url "generated/" workspace "/" img)))

(defn- parse-results [^REXP results]
  (try 
    (let [data (R/as-list results)
          images (R/as-list (.at data "images"))
          results (R/as-list (.at data "results"))]
      {:images (map-cols-to-rows 
                 {:url (map #(url-for-img-path %) 
                            (map #(.asString (.at (R/as-list %) "url")) images))
                  :description (map #(.asString (.at (R/as-list %) "description")) images)})
       :results (parse-results-list results)})
    (catch Exception e (R/into-clj results)))) ;Fallback to generic structure

(defn dispatch 
  [analysis params]
  (if (not (valid? (get @validators analysis (validation-set)) params))
    (throw (IllegalArgumentException. 
             (str "Provided parameters were not valid for analysis " analysis)))
    (let [files (select-keys params (for [[k v] params :when (contains? v :file)] k))
          options (into {} (map (fn [[k v]]
                                  (if (contains? v :file) 
                                    [k {"file" (get-in v [:file :filename])}] 
                                    [k v])) params))]
      (with-open [R (R/connect)]
        (doall (map 
                 (fn [[k v]] 
                   (copy-to-r R (get-in v [:file :tempfile]) (get-in v [:file :filename]))) files))
        (load-analysis! R analysis)
        (R/assign R "params" options)
        (log/debug params)
        {:results {(keyword analysis) (parse-results (R/parse R (str analysis "(params)") false))}}))))