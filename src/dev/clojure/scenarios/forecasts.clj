(ns scenarios.forecasts
  (:require [java-time.api :as t]
            [scenarios.commons :as commons :refer [*node* batch-submits for-valid-time interleave-submits-2]]
            [scenarios.load-test :refer [periodic-instants]]
            [xtdb.api :as xt]))

(defn seed-forecasts [{:keys [table subject-count from to]}]
  (for [forecast-release-instant #_spy/p (periodic-instants (t/minutes 30) from to)
        id (range subject-count)
        value-time (periodic-instants (t/minutes 30) ; todo: fix: 30 minutes round the hour?
                     forecast-release-instant
                     (t/plus forecast-release-instant (t/days 7)))]
    [[[:put-docs {:into table, :valid-from value-time}
       {:xt/id id
        :value 1000}]]
     {:system-time forecast-release-instant}]))

(defn seed-forecasts--array [{:keys [table subject-count from to]}]
  (for [forecast-release-instant #_spy/p (periodic-instants (t/minutes 30) from to)
        id (range subject-count)]
    [[[:put-docs {:into table}
       {:xt/id id
        :values (repeat (* 7 24 (/ 60 30)) 1000)}]]
     {:system-time forecast-release-instant}]))

(defn forecast-for-all-sites [{:keys [from system-rating-active-power]}]
  (let [valid-time-filter (for-valid-time from (t/plus from (t/days 7)))]
    (str
      "FROM
         load_forecast_7d_value " valid-time-filter " AS load
       JOIN
         pv_forecast_7d_value " valid-time-filter " AS pv
         ON load._id = pv._id AND load._valid_from = pv._valid_from
       WHERE
         load._id < 1000
       SELECT
         load._valid_from AS t,
         SUM((pv.value * " system-rating-active-power " / 1000) - load.value) AS v")))

(defn forecast-for-all-sites--array [{:keys [from system-rating-active-power]}]
  (let [valid-time-filter (for-valid-time from (t/plus from (t/days 7)))]
    (str
      "SELECT
         load._valid_from AS t,
         load.\"values\" AS load_v,
         pv.\"values\" AS pv_v
       FROM
         load_forecast_7d_value " valid-time-filter " AS load
       JOIN
         pv_forecast_7d_value " valid-time-filter " AS pv
         ON load._id = pv._id AND load._valid_from = pv._valid_from
       WHERE
         load._id < 1")))

; generate_series(DATE '2022-01-01', DATE '2192-01-05',INTERVAL '1' day) WITH ORDINALITY AS t(_valid_from,_id);

(comment
  (count (seed-forecasts {:table :load_forecast_7d_value
                          :subject-count 1
                          :from (t/local-date-time 2024 1 1 0 0)
                          :to (t/local-date-time 2024 1 1 1 0)})))


;; Manual test

(comment
  (commons/restart-small!)

  (def forecast-submits
    (let [seed-forecasts-fn seed-forecasts--array
          opts {:subject-count 50
                :from (t/local-date-time 2024 1 1 0 0)
                :to (t/local-date-time 2024 1 15 0 0)}]
      (interleave-submits-2
        (seed-forecasts-fn (merge opts {:table :load_forecast_7d_value}))
        (seed-forecasts-fn (merge opts {:table :pv_forecast_7d_value})))))

  (count forecast-submits)
  (take 10 forecast-submits)
  (take-last 10 forecast-submits)

  (doseq [submit (->> forecast-submits
                   (batch-submits 10000))]
    (apply xt/submit-tx *node* submit))

  ; How to react to submit errors?
  (xt/submit-tx *node* [[:put-docs :test {:xt/id "test1"}]] {:system-time (t/instant "2023-01-01T00:00:00Z")})

  (->> (xt/q *node*
         (forecast-for-site {:location-id 0
                             :customer-profile-id 0
                             :from (t/local-date-time 2024 1 1 0 0)
                             :system-rating-active-power 1.0}))
    (map (juxt (comp str :t) :v (comp str :sys))))

  (xt/q *node*
    (forecast-for-all-sites {:from (t/local-date-time 2024 1 1 0 0)
                             :system-rating-active-power 1.0}))

  (xt/q *node*
    (forecast-for-all-sites--array {:from (t/local-date-time 2024 1 1 0 0)
                                    :system-rating-active-power 1.0}))

  (xt/q *node* "SELECT MAX(_id) FROM load_forecast_7d_value"))






