(ns scenarios.load-test
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [print-table]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [java-time.api :as t]
            [mount.core :as mount :refer [defstate]]
            [xtdb.api :as xt]
            [xtdb.node :as xtnode]
            [xtdb.util :as xtutil])
  (:import (java.nio.file CopyOption Files Path)
           (java.time LocalDate)))


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

(defn rm-dataset [name]
  (sh "rm" "-rf" (str "datasets/" name)))

;; Seeding

(defn periodic-instants [duration from-local-date to-local-date]
  (->> (iterate #(t/plus % duration) from-local-date)
    (take-while #(t/not-after? % to-local-date))
    (map #(t/instant % (t/zone-id "UTC")))))

(comment
  (t/instant (t/local-date-time 2024 1 1 0 0) (t/zone-id "UTC")))
(comment
  (take 10 (periodic-instants
             (t/duration 5 :minutes)
             (t/local-date-time 2024 1 1 0 0)
             (t/local-date-time 2024 1 15 0 0)))
  (take-last 10 (periodic-instants
                  (t/duration 5 :minutes)
                  (t/local-date-time 2024 1 1 0 0)
                  (t/local-date-time 2024 1 15 0 0))))

(defn rand-int-between [minv maxv]
  (+ minv (rand-int (- maxv minv))))

(defn seed-readings-tx [system-count dates]
  (let [values (repeat 1000) #_(repeatedly 1000 #(rand-int-between 1000 5000))]
    (for [[from to value] (map vector dates (next dates) values)
          id (range 0 system-count)]
      [:put-docs {:into :reading
                  :valid-from from
                  :valid-to to}
       {:xt/id id
        :value value}])))

(defn random-days-off [from to]
  (let [total-days (t/time-between from to :days)]
    (for [d (range 0 total-days 7)]
      (+ d (rand-int 7)))))

(comment
  (random-days-off (t/instant "2024-01-01T00:00:00Z")
                   (t/instant "2024-01-20T00:00:00Z")))

(defn add-days-off-txs [put-docs-tx off-data]
  (let [table (-> put-docs-tx second :into)
        data (nth put-docs-tx 2)
        v-from (-> put-docs-tx second :valid-from)
        v-to (-> put-docs-tx second :valid-to)]
    (concat
      [put-docs-tx]
      (for [day-off (random-days-off v-from v-to)]
        (let [day-off-from (-> v-from (t/plus (t/duration day-off :days)))
              day-off-to (-> day-off-from (t/plus (t/duration 1 :days)))]
          [:put-docs {:into table
                      :valid-from day-off-from
                      :valid-to day-off-to}
           (merge data off-data)])))))

(defn seed-system-tx [id vfrom vto]
  (concat
    (add-days-off-txs [:put-docs {:into :site, :valid-from vfrom, :valid-to vto}
                       {:xt/id (str "site" id)
                        :prop1 "yes"}]
                      {:prop1 "no"})
    (add-days-off-txs [:put-docs {:into :system, :valid-from vfrom, :valid-to vto}
                       {:xt/id id
                        :nmi (str "site" id)
                        :prop1 "yes"}]
                      {:prop1 "no"})
    (add-days-off-txs [:put-docs {:into :device, :valid-from vfrom, :valid-to vto}
                       {:xt/id (str "device" id "-0")
                        :system_id id
                        :prop1 "yes"}]
                      {:prop1 "no"})
    (add-days-off-txs [:put-docs {:into :device, :valid-from vfrom, :valid-to vto}
                       {:xt/id (str "device" id "-1")
                        :system_id id
                        :prop1 "yes"}]
                      {:prop1 "no"})))

(defn interleave-submit-args [submit-args-1 submit-args-2]
  (cond
    (empty? submit-args-1) submit-args-2
    (empty? submit-args-2) submit-args-1
    :else
    (lazy-seq
      (let [d1 (-> submit-args-1 first second :system-time)
            d2 (-> submit-args-2 first second :system-time)]
        (assert (t/instant? d1))
        (assert (t/instant? d2))
        (cond
          (t/not-after? d1 d2)
          (cons (first submit-args-1)
                (interleave-submit-args (rest submit-args-1) submit-args-2))

          :else
          (cons (first submit-args-2)
            (interleave-submit-args submit-args-1 (rest submit-args-2))))))))

(comment
  (interleave-submit-args
    [[[:put-docs {:into :site} {:xt/id "site2"}] {:system-time (t/instant "2024-01-02T00:00:00Z")}]
     [[:put-docs {:into :site} {:xt/id "site4"}] {:system-time (t/instant "2024-01-04T00:00:00Z")}]]
    [[[:put-docs {:into :site} {:xt/id "site1"}] {:system-time (t/instant "2024-01-01T00:00:00Z")}]
     [[:put-docs {:into :site} {:xt/id "site5"}] {:system-time (t/instant "2024-01-05T00:00:00Z")}]]))

(defn batch-submit-args [node max-batch-size submit-args-seq]
  (let [submitted-count (atom 0)]
    (for [same-system-time (partition-by #(-> % second :system-time) submit-args-seq)
          batch (partition-all max-batch-size same-system-time)]
      (let [tx-opts (-> batch first second)
            submit-args [(map first batch) tx-opts]]
        (println "submitted"
                 (swap! submitted-count (partial + (count batch))))
        submit-args))))


; Seed scenario of only forecasts

(comment
  (mount/stop #'node-small)
  (rm-dataset "small")

  (mount/start #'node-small)

  (doseq [submit-args (batch-submit-args "node" 1000
                        (seed-forecasts {:customer-profile-count 1
                                         :from (t/local-date-time 2024 1 1 0 0)
                                         :to (t/local-date-time 2024 1 2 0 0)}))]
    ;(pprint submit-args)
    (apply xt/submit-tx node-small submit-args))

  (xt/q node-small
    "SELECT DISTINCT _valid_from FROM load_forecast_7d_value
     FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-10'
     ORDER BY _valid_from")

  (xt/q node-small
    "SELECT DISTINCT _system_from FROM load_forecast_7d_value
     FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-10'
     FOR SYSTEM_TIME ALL
     ORDER BY _system_from")

  (->> (xt/q node-small
         "SELECT _valid_from, _system_from, * FROM load_forecast_7d_value
          FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-10'
          ORDER BY _valid_from")
    (map (juxt :xt/valid-from :value :xt/system-from)))


  ,)


; Small scenario ------------------------------------------

(defn seed-readings--submit-args [{:keys [system-count from to]}]
  (->> (seed-readings-tx system-count (periodic-instants (t/duration 5 :minutes) from to))
    (map (fn submit-as-of-valid-to [put-docs]
           [put-docs {:system-time (-> put-docs second :valid-to)}]))))

(defn seed-systems--submit-args [{:keys [system-count from to]}]
  (->> (for [system-id (range system-count)]
         (seed-system-tx system-id (t/instant from (t/zone-id "UTC")) (t/instant to (t/zone-id "UTC"))))
    (apply concat)
    (map (fn submit-as-of-valid-from [put-docs]
           [put-docs {:system-time (-> put-docs second :valid-from)}]))
    (sort-by #(-> % second :system-time))))

(defn seed-scenario [node opts]
  (doseq [submit-args (batch-submit-args "node" 1000
                        (interleave-submit-args
                          (seed-systems--submit-args opts)
                          (seed-readings--submit-args opts)))]
    (apply xt/submit-tx node submit-args)))


(comment
  (mount/start #'node-small)
  (mount/stop #'node-small)

  (seed-scenario node-small {:system-count 1
                             :from (t/local-date-time 2024 1 1 0 0)
                             :to (t/local-date-time 2024 1 15 0 0)})

  (mount/start #'node-load1)
  (mount/stop #'node-load1)

  (seed-scenario node-load1 {:system-count 500
                             :from (t/local-date-time 2024 1 1 0 0)
                             :to (t/local-date-time 2024 7 1 0 0)}) ; => 26262000 records

  ,)


;; Issue

(defn seed-systems-only [node opts]
  (doseq [submit-args (batch-submit-args "node" 1000
                        (seed-systems--submit-args opts))]
    (apply xt/submit-tx node submit-args)))


;; Queries

(defn render-sql [d]
  (cond
    (t/local-date? d) (str "DATE '" d "'")
    (t/local-date-time? d) (str "TIMESTAMP '" d "'")
    :else (assert false)))

(defn for-valid-time [from to]
  (str "FOR VALID_TIME FROM " (render-sql from) " TO " (render-sql to)
       #_" FOR SYSTEM_TIME ALL"))

(comment
  (for-valid-time (t/local-date 2024 1 1) (t/local-date 2024 1 1)))

(defn vpp-systems-raw [{:keys [system-count from to]}]
  (str
    "SELECT
       site._id AS site_id,
       system._id AS system_id,
       --device._id AS device_id,
       --site._valid_time * system._valid_time * device._valid_time AS overlap,
       site._valid_time AS site_valid_time,
       system._valid_time AS system_valid_time,
       --device._valid_time AS device_valid_time,
       site._valid_time * system._valid_time AS overlap,
       site.prop1 AS site_prop,
       system.prop1 AS system_prop,
     FROM site " (for-valid-time from to) "
     JOIN system " (for-valid-time from to) "
       ON system.nmi = site._id
     --JOIN device
     --  FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-30'
     --  ON device.system_id = system._id
     WHERE
       system._id < " system-count "
       --AND site._valid_time * system._valid_time IS NOT NULL
       --AND site.prop1 = 'yes'
       --AND system.prop1 = 'yes'
       --AND device.prop1 = 'yes'"))

(defn vpp-systems-raw [{:keys [from to]}]
  (str
    "SELECT
       site._id AS site_id,
       system._id AS system_id,
       site._valid_time AS site_valid_time,
       system._valid_time AS system_valid_time,
       site.prop1 AS site_prop,
       system.prop1 AS system_prop,
     FROM site " (for-valid-time from to) "
     JOIN system " (for-valid-time from to) "
       ON system.nmi = site._id
     WHERE
       system._id = 0"))


; Filtering issue

(def my-from (t/local-date 2024 1 1))
(def my-to (t/local-date 2024 1 8))

(comment
  (mount/start #'node-small)

  ; issue takes place on datasets of considerable size, presumable > 100000 records
  (seed-systems-only node-small {:system-count 2000
                                 :from (t/local-date-time 2024 1 1 0 0)
                                 :to (t/local-date-time 2024 7 1 0 0)}) ; => 216000

  (def my-node node-small)

  ; Correct
  (->> (xt/q my-node
         (str
           "SELECT _valid_time, *--, _system_time
            FROM system " (for-valid-time my-from my-to) "
            WHERE _id = 0" #_" AND system.prop1 = 'yes'" "
            ORDER BY _valid_from"))
    (map (juxt :xt/id :xt/valid-time :xt/system-time :prop1)))

  ; With WHERE filter, incorrect
  (->> (xt/q my-node
         (str
           "SELECT _valid_time, *--, _system_time
            FROM system " (for-valid-time my-from my-to) "
            WHERE _id = 0 AND system.prop1 = 'yes'
            ORDER BY _valid_from"))
    (map (juxt :xt/id :xt/valid-time :xt/system-time :prop1)))

  (mount/stop #'node-small)

  ,)

(defn vpp-systems-range--query [{:keys [system-count]}]
  (str
    "SELECT site_id, system_id, overlap
       FROM (
         SELECT
           site._id AS site_id,
           system._id AS system_id,
           device._id AS device_id,
           system._valid_time * site._valid_time * device._valid_time AS overlap,
         FROM site
           FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-30'
         JOIN system
           FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-30'
           ON system.nmi = site._id
         JOIN device
           FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-30'
           ON device.system_id = system._id
         WHERE
           system._id < " system-count "
           AND site.prop1 = 'yes'
           AND system.prop1 = 'yes'
           AND device.prop1 = 'yes'
     ) AS results
     WHERE overlap IS NOT NULL
     GROUP BY site_id, system_id, overlap"))

(defn vpp-metric--query [{:keys [] :as opts}]
  (str
    "WITH vpp_system_range AS (" (vpp-systems-range--query opts) ")
     FROM (
       FROM reading
         FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-30'
       JOIN vpp_system_range
         ON vpp_system_range.system_id = reading._id
       WHERE
         vpp_system_range.overlap CONTAINS reading._valid_time -- is this correct?
       SELECT
         reading._valid_from AS t,
         reading._id AS system_id,
         AVG(reading.value) AS v,
       ORDER BY
         reading._valid_from
     ) AS r
     SELECT t, SUM(v),"))

(comment
  (->> (xt/q node-load1 (vpp-systems-range--query {:system-count 1}))
    (sort-by :system-id)
    (map (juxt :system-id :overlap)))

  ; check system devices
  (->> (xt/q node-load1
         "SELECT _valid_from, _valid_to, *
          FROM device FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-30'
          WHERE system_id = 0 AND prop1 = 'no'
          ORDER BY _valid_from")
    (sort-by (juxt :xt/valid-from :xt/valid-to))
    (map (juxt :xt/id :xt/valid-from :xt/valid-to :prop1)))


  (xt/q node-small (vpp-count--query))
  (xt/q node-small (vpp-metric--query))
  (xt/q node-small
    "SELECT
       site._id, prop1, _valid_from, _valid_to
     FROM site FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-07'")
  (xt/q node-small
    "SELECT *, _valid_from, _valid_to
     FROM device FOR VALID_TIME FROM DATE '2024-01-01' TO DATE '2024-01-07'"))

(defn sum-system-readings--sql [{:keys [systems days]}]
  (let [start-date (LocalDate/of 2024 1 1)
        end-date (-> start-date (.plusDays days))
        render-date (fn [d] (str "DATE '" d "'"))]
    (str/join " "
      ["SELECT _valid_from, SUM(value)"
       "FROM reading FOR VALID_TIME FROM" (render-date start-date) "TO" (render-date end-date)
       "WHERE _id <=" systems
       "ORDER BY _valid_from"])))

(comment
  (sum-system-readings--sql {:systems 10, :days 1}))

(defn run-readings
  "Runs load test, storing results in a file.
   Results previously found in the file will be reused and not re-queried. This is convenient
   for interrupting the test anytime and resuming it later."
  [node {:keys [timeout results-file]}]
  (doseq [systems (range 1 1002 200)
          days (range 1 62 15)]
    (let [cache-k {:systems systems
                   :days days}
          cache (io/file results-file)
          cached-map (if (.exists cache)
                       (read-string (slurp cache))
                       {})]
      (println "--------------------")
      (println cache-k)
      (if-let [result (get cached-map cache-k)]
        (do
          (println "[from cache]")
          (println result))
        (let [sql (sum-system-readings--sql {:systems systems, :days days})]
          (println sql)
          (let [completed (promise)
                task (future
                       (try
                         (let [t (System/nanoTime)
                               records (xt/plan-q node sql)
                               records-count (reduce (fn [count _] (inc count)) 0 records)
                               ellapsed (-> (System/nanoTime) (- t) double (/ 1000000.0))]
                           {:records-count records-count
                            :ellapsed ellapsed})
                         (finally
                           (deliver completed true))))
                result (deref task timeout :timeout)]
            (case result
              :timeout (do
                         (println "[timed out, aborting]")
                         (future-cancel task)
                         (deref completed) ; ensure task has completed totally before running next
                         (println "[aborted]"))
              (do
                (spit cache (assoc cached-map cache-k result))
                (println result)))))))))

(defn print-results [{:keys [filename]}]
  (->> (read-string (slurp filename))
    (map (fn [[k v]] (merge k v)))
    (sort-by (fn [{:keys [days systems]}]
               [days systems]))
    (print-table [:days :systems :ellapsed :records-count])))

(defn mv [path1 path2]
  (Files/move (Path/of path1 (make-array String 0))
    (Path/of path2 (make-array String 0))
    (make-array CopyOption 0)))


; Manual use
(comment
  (mount/start #'node-load1)
  (xt/status node-load1)
  (mount/stop #'node-load1)

  (time (seed-readings node-load1))
  (xtdb.api/q node-load1 "SELECT COUNT(*) FROM reading FOR VALID_TIME ALL")
  (xtdb.api/q node-load1 "SELECT DISTINCT _id FROM reading FOR VALID_TIME ALL")

  (mv "readings-results.edn"
      "readings-results.backup3.edn")

  (.delete (io/file "system-readings-backup.edn"))

  (time (count (xt/q node-load1 (sum-system-readings--sql {:systems 2, :days 2})))) ; warm-up

  (run-readings node-load1 {:results-file "readings-results.edn"
                            :timeout (* 1 60 1000)})

  (print-results {:filename "readings-results.edn"})

  ,)
