(ns scenarios.forecasts
  (:require [java-time.api :as t]
            [scenarios.commons :as commons :refer [make-node-conf *node* batch-submits for-valid-time interleave-submits-2 render-sql]]
            [scenarios.load-test :refer [periodic-instants]]
            [xtdb.api :as xt]
            [mount.core :as mount :refer [defstate]]
            [xtdb.node :as xtnode]
            [xtdb.util :as xtutil]))

(defstate ^{:on-reload :noop}
  node-rec
  "Node with forecasts represented as record per value"
  :start (xtnode/start-node (make-node-conf "forecast-rec"))
  :stop (xtutil/close node-rec))

(defstate ^{:on-reload :noop}
  node-plain-array
  "Node with forecasts represented as an array of objects"
  :start (xtnode/start-node (make-node-conf "forecast-plain-array"))
  :stop (xtutil/close node-plain-array))

(defstate ^{:on-reload :noop}
  node-obj-array
  "Node with forecasts represented as an array of objects"
  :start (xtnode/start-node (make-node-conf "forecast-obj-array"))
  :stop (xtutil/close node-obj-array))

(defn seed-forecasts--rec [{:keys [table subject-count from to]}]
  (for [forecast-release-instant (periodic-instants (t/minutes 30) from to)
        id (range subject-count)
        value-time (periodic-instants (t/minutes 30)
                     forecast-release-instant
                     (t/plus forecast-release-instant (t/days 7)))]
    [[[:put-docs {:into table, :valid-from value-time}
       {:xt/id id
        :value 1000}]]
     {:system-time forecast-release-instant}]))

(comment
  (count (seed-forecasts--rec {:table :load_forecast
                               :subject-count 1
                               :from (t/local-date-time 2024 1 1 0 0)
                               :to (t/local-date-time 2024 1 1 1 0)})))

(defn seed-forecasts--plain-array [{:keys [table subject-count from to]}]
  (for [forecast-release-instant (periodic-instants (t/minutes 30) from to)
        id (range subject-count)]
    [[[:put-docs {:into table}
       {:xt/id id
        :value (repeat (* 7 24 (/ 60 30)) 1000)}]]
     {:system-time forecast-release-instant}]))

(defn seed-forecasts--obj-array [{:keys [table subject-count from to]}]
  (for [forecast-release-instant (periodic-instants (t/minutes 30) from to)
        id (range subject-count)]
    [[[:put-docs {:into table}
       {:xt/id id
        :value (for [t (periodic-instants (t/minutes 30)
                         forecast-release-instant
                         (t/plus forecast-release-instant (t/days 7)))]
                 {:t t, :v 1000})}]]
     {:system-time forecast-release-instant}])) ; <- also sets valid-from

(defn forecast-for-sites--rec [{:keys [from num-sites system-rating-active-power]}]
  (let [valid-time-filter (for-valid-time from (-> from
                                                 (t/plus (t/days 7))
                                                 (t/plus (t/micros 1))))]
    (str
      "FROM
         load_forecast " valid-time-filter " AS load
           JOIN pv_forecast " valid-time-filter " AS pv
             ON load._id = pv._id AND load._valid_from = pv._valid_from
       WHERE
         load._id < " num-sites "
       SELECT
         load._valid_from AS t,
         SUM((pv.value * " system-rating-active-power " / 1000) - load.value) AS v
       ORDER BY t")))

; WIP
(defn forecast-for-sites--plain-array [{:keys [from num-sites system-rating-active-power]}]
  (str
    "WITH
     timestamps AS (
       FROM GENERATE_SERIES(1, 7*24*2) AS X(ts_i)
       SELECT ts_i, " (render-sql from) " + (ts_i-1) * INTERVAL 'PT30M' AS ts_t
     ),
     load AS (
       FROM load_forecast AS f
       WHERE f._id < " num-sites "
       SELECT f._id AS load_id, value AS load_vs
     ),
     pv AS (
       FROM pv_forecast AS f
       WHERE f._id < " num-sites "
       SELECT f._id AS pv_id, value AS pv_vs
     )
     FROM
       load
         JOIN pv ON load_id = pv_id,
     --SELECT load_id, pv_id
       UNNEST(load_vs) WITH ORDINALITY AS loadx(load_v, load_i),
       timestamps
     WHERE load_i = ts_i
     SELECT load_id, pv_id, ts_t, load_v
     --  UNNEST(pv_vs) WITH ORDINALITY AS pvx(pv_v, pv_i)
     --WHERE load_i = pv_i
     --SELECT load_id, load_i, load_v, pv_v, ts_t
     --SELECT ts_t, SUM(load_v) AS load
     --SELECT ts_t, SUM((pv_v * 1.0 / 1000) - load_v) AS v"))

(defn forecast-for-sites--obj-array [{:keys [from num-sites system-rating-active-power]}]
  (str
    "WITH
     load AS (
       FROM load_forecast FOR VALID_TIME AS OF " (render-sql from) " AS f
       WHERE f._id < " num-sites "
       SELECT f._id AS load_id, value AS load_vs
     ),
     pv AS (
       FROM pv_forecast FOR VALID_TIME AS OF " (render-sql from) " AS f
       WHERE f._id < " num-sites "
       SELECT f._id AS pv_id, value AS pv_vs
     )
     FROM
       load JOIN pv ON load_id = pv_id,
       UNNEST(load_vs) AS loadx(load_p),
       UNNEST(pv_vs) AS pvx(pv_p) -- We can't use JOIN ON for now. See
     WHERE (load_p).t = (pv_p).t -- Not efficient
     --SELECT load_id, (load_p).t AS load_t, (load_p).v AS load_v, (pv_p).v AS pv_v
     --SELECT (load_p).t AS load_t, SUM((load_p).v) AS load_v, SUM((pv_p).v) AS pv_v
     SELECT (load_p).t, SUM(((pv_p).v * " system-rating-active-power " / 1000) - (load_p).v) AS v
     ORDER BY (load_p).t
     "))


;; Manual test

(defn forecast-submits-seq [{:keys [seed-fn]}]
  (let [opts {:subject-count 50
              :from (t/local-date-time 2024 1 1 0 0)
              :to (t/local-date-time 2024 1 15 0 0)}]
    (interleave-submits-2
      (seed-fn (merge opts {:table :load_forecast}))
      (seed-fn (merge opts {:table :pv_forecast})))))

(defn submit-in-batches! [node s]
  (doseq [submit (batch-submits 10000 s)]
    (apply xt/submit-tx node submit)))

(comment
  ; seeding
  (mount/start #'node-rec)
  (submit-in-batches! node-rec
    (forecast-submits-seq {:seed-fn seed-forecasts--rec}))

  (mount/start #'node-plain-array)
  (submit-in-batches! node-plain-array
    (forecast-submits-seq {:seed-fn seed-forecasts--plain-array}))

  (mount/start #'node-obj-array)
  (submit-in-batches! node-obj-array
    (forecast-submits-seq {:seed-fn seed-forecasts--obj-array}))

  ; comparing number of records
  22680100
  (float (/ 22680100 (* 7 24 (/ 60 30))))
  67300

  ,)

(defn run-forecast-query [node q-fn]
  (time
    (xt/q node
      (q-fn {:from (t/local-date-time 2024 1 1 0 0)
             :num-sites 50
             :system-rating-active-power 1.0})
      #_{:explain? true})))

(comment
  (def res-rec (run-forecast-query node-rec forecast-for-sites--rec)) ; Elapsed time: 18329.652222 msecs
  (run-forecast-query node-plain-array forecast-for-sites--plain-array) ; WIP. Elapsed time: ? msecs
  (def res-obj-array (run-forecast-query node-obj-array forecast-for-sites--obj-array)) ; Elapsed time: 2950.321188 msecs

  (= res-rec res-obj-array)

  ,)
