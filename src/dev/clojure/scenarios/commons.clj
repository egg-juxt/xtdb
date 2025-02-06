(ns scenarios.commons
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [java-time.api :as t]
            [mount.core :as mount :refer [defstate]]
            [xtdb.api :as xt]
            [xtdb.node :as xtnode]
            [xtdb.util :as xtutil]))

; SQL utils

(defn render-sql [d]
  (cond
    (t/local-date? d) (str "DATE '" d "'")
    (t/instant? d) (str "TIMESTAMP '" d "'")
    (t/local-date-time? d) (render-sql (t/instant d (t/zone-id "UTC")))
    :else (assert false)))

(defn for-valid-time [from to]
  (str "FOR VALID_TIME FROM " (render-sql from) " TO " (render-sql to)
       #_" FOR SYSTEM_TIME ALL"))

(comment
  (for-valid-time (t/local-date 2024 1 1) (t/local-date 2024 1 1)))


;;; Submits (= args for xt/submit)

; TODO assert that seqs are ordered
(defn interleave-submits-2 [submits-1 submits-2]
  (cond
    (empty? submits-1) submits-2
    (empty? submits-2) submits-1
    :else
    (lazy-seq
      (let [d1 (-> submits-1 first second :system-time)
            d2 (-> submits-2 first second :system-time)]
        (assert (t/instant? d1))
        (assert (t/instant? d2))
        (cond
          (t/not-after? d1 d2)
          (cons (first submits-1)
            (interleave-submits-2 (rest submits-1) submits-2))

          :else
          (cons (first submits-2)
            (interleave-submits-2 submits-1 (rest submits-2))))))))

; TODO batch single ops (now batching full tx-ops, as received)
(defn batch-submits [max-batch-size submits]
  (let [submitted-count (atom 0)]
    (for [part-by-system-time (partition-by #(-> % second :system-time) submits)
          batch (partition-all max-batch-size part-by-system-time)]
      (let [tx-ops (apply concat (map first batch))
            tx-opts (-> batch first second)
            new-submitted-count (swap! submitted-count (partial + (count batch)))]
        (println "submits" new-submitted-count)
        [tx-ops tx-opts]))))


;;; Nodes

(defn make-node-conf [node-name]
  #spy/d {:log [:local {:path (io/file "datasets" node-name "log")}]
          :storage [:local {:path (io/file "datasets" node-name "objects")}]})

(defstate ^{:on-reload :noop}
  node-load1
  "Node with random system readings for year 2024, for 1000 systems"
  :start (xtnode/start-node (make-node-conf "load1"))
  :stop (xtutil/close node-load1))

(defstate ^{:on-reload :noop}
  node-small
  "Node for small tests"
  :start (xtnode/start-node (make-node-conf "small"))
  :stop (xtutil/close node-small))

;; Nodes

(defn make-node-conf [node-name]
  #spy/d {:log [:local {:path (io/file "datasets" node-name "log")}]
          :storage [:local {:path (io/file "datasets" node-name "objects")}]})

(defstate ^{:on-reload :noop}
  node-load1
  "Node with random system readings for year 2024, for 1000 systems"
  :start (xtnode/start-node (make-node-conf "load1"))
  :stop (xtutil/close node-load1))

(defstate ^{:on-reload :noop}
  node-small
  "Node for small tests"
  :start (xtnode/start-node (make-node-conf "small"))
  :stop (xtutil/close node-small))

(def ^:dynamic *node*)

(defn set-node! [node]
  (alter-var-root #'*node* (constantly node)))

(defn rm-dataset! [name]
  (sh "rm" "-rf" (str "datasets/" name)))

(defn reset-small! []
  (mount/stop #'node-small)
  (rm-dataset! "small")
  (mount/start #'node-small)
  (set-node! node-small))

(comment
  (reset-small!)

  (mount/stop #'node-small)
  (rm-dataset! "small")

  (mount/start #'node-small)
  (set-node! node-small)
  (xt/status *node*))
