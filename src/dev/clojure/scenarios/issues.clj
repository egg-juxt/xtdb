(ns scenarios.issues
  (:require [java-time.api :as t]
            [scenarios.commons :refer [*node* reset-small!]]
            [xtdb.api :as xt]))

(comment
  ; Is this a gotcha?
  (xt/submit-tx *node* [[:put-docs :r1 {:xt/id 0}]])
  (xt/q *node* "SELECT r1._id FROM r1 WHERE r1._id = 0") ; => [#:xt{:id 0}]
  (xt/q *node* "FROM r1 WHERE r1._id = 0 SELECT r1._id") ; => [#:xt{:id 0}]
  (xt/q *node* "FROM r1 SELECT r1._id WHERE r1._id = 0") ; => []

  ,)

(comment
  (reset-small!)

  (xt/submit-tx *node*
    (for [id (range 10)]
      [:put-docs :f {:xt/id id, :xs (repeat 10 "x")}]))

  (xt/q *node* "FROM f")

  (xt/q *node*
    "SELECT f._id, f_x
     FROM f, UNNEST(f.xs) AS f_xs(f_x)")

  (xt/q *node*
    "SELECT f._id, f_x
     FROM f, UNNEST(f.xs) AS f_xs(f_x)
     WHERE f._id = 0"
    #_{:explain? true})

  (xt/execute-tx *node*
    (for [id (range 100000)]
      [:put-docs :f {:xt/id id, :xs (repeat 200 "x")}]))

  ; This one takes time. Filter takes place after cross joining UNNEST
  (xt/q *node*
    "SELECT f._id, f_x
     FROM f, UNNEST(f.xs) AS f_xs(f_x)
     WHERE f._id = 0"
    #_{:explain? true})

  ; This one is immediate
  (xt/q *node*
    "WITH f1 AS (
       SELECT f._id, f.xs
       FROM f
       WHERE f._id = 0
     )
     SELECT f1._id, f_x
     FROM f1, UNNEST(f1.xs) AS f_xs(f_x)"
    #_{:explain? true})

  ,)

(comment
  (reset-small!)

  ; https://github.com/xtdb/xtdb/issues/4131
  (xt/submit-tx *node*
    [[:put-docs :r1
      {:xt/id 0, :xs [10 20 30]}
      {:xt/id 1, :xs [100 200 300]}]
     [:put-docs :r2
      {:xt/id 0, :xs [11 21 31]}
      {:xt/id 1, :xs [101 201 301]}]])

  ; Correct
  (xt/q *node*
    "FROM r1, r2
     , UNNEST(r1.xs) WITH ORDINALITY AS u1(x1, i1)
     , UNNEST(r2.xs) WITH ORDINALITY AS u2(x2, i2)
     WHERE i1 = i2
     SELECT i1, i2, x1, x2")

  ; Incorrect, ordinalities keep growing
  (xt/q *node*
    "FROM r1, r2
     , UNNEST(r1.xs) WITH ORDINALITY AS u1(x1, i1)
     JOIN UNNEST(r2.xs) WITH ORDINALITY AS u2(x2, i2)
       ON i1 = i2
     SELECT i1, i2, x1, x2")

  ,)

(comment
  (reset-small!)

  ; https://github.com/xtdb/xtdb/issues/4132
  (xt/submit-tx *node*
    [[:put-docs :r1
      {:xt/id 0, :xs [1 2 3]}
      {:xt/id 1, :xs [10 20 30]}]])

  ; detailed query
  (xt/q *node*
    "FROM r1, UNNEST(r1.xs) WITH ORDINALITY AS un(x, i)
     SELECT i, x")

  ; aggregated query. Exception: "illegal copy src vector"
  (xt/q *node*
    "FROM r1, UNNEST(r1.xs) WITH ORDINALITY AS un(x, i)
     SELECT i, SUM(x)")

  ,)


(comment
  (reset-small!)

  ; https://github.com/xtdb/xtdb/issues/4133
  (xt/execute-tx *node*
    ["INSERT INTO forecast1 RECORDS {_id: 1, value: [4, 3, 2, 1]}"
     "INSERT INTO forecast2 RECORDS {_id: 1, value: [5, 4, 3, 2]}"])

  ; Correct
  (xt/q *node*
    "SELECT forecast1._id AS f1_id, v
     FROM
       forecast1
       JOIN forecast2 ON forecast1._id = forecast2._id
       , UNNEST(forecast1.value) AS uf1(v)")

  ; Changing the order in FROM returns an empty resultset, as the forecast1._id column gets "hidden" after the UNNEST:
  ; "WARN  xtdb.sql.plan - Column not found: forecast1._id"
  (xt/q *node*
    "SELECT forecast1._id AS f1_id, v
     FROM
       forecast1
       , UNNEST(forecast1.value) AS uf1(v)
       JOIN forecast2 ON forecast1._id = forecast2._id"))

(comment
  (reset-small!)

  ; Does system-time set default valid-from in put-docs?
  (xt/execute-tx *node*
    [[:put-docs {:into :t} {:xt/id 0}]]
    {:system-time (t/instant "2024-01-01T00:00:00Z")})

  (xt/q *node*
    "SELECT _id, _valid_time FROM t FOR VALID_TIME ALL")
  ; => [#:xt{:id 0, :valid-time #xt/tstz-range[#xt/zoned-date-time"2024-01-01T00:00Z" nil]}]
  ; Yes, valid-from is set to system-time!

  ,)

;;; Issues trying to build the forecast array query

(defn seed-sample-forecasts [{:keys [num-forecasts num-values]}]
  (xt/submit-tx *node* ["DELETE FROM forecast1"])
  (xt/submit-tx *node* ["DELETE FROM forecast2"])
  (doseq [id (range num-forecasts)]
    (xt/submit-tx *node*
      [["INSERT INTO forecast1 RECORDS {_id: ?, value: ?}" id (range num-values)]
       ["INSERT INTO forecast2 RECORDS {_id: ?, value: ?}" id (range num-values (* 2 num-values))]])))

(comment
  (seed-sample-forecasts {:num-forecasts 10, :num-values 10})
  (seed-sample-forecasts {:num-forecasts 100, :num-values 100})

  ;; This query takes much longer when run with 100
  ;; If we check the explained query, there is a mega-join on wholy scanned tables and unnest.
  ;; :select filters are not being pushed down through :unnest?
  (xt/q *node*
    "FROM forecast1,
      UNNEST(forecast1.value) WITH ORDINALITY AS uf1(v, i),
      forecast2,
      UNNEST(forecast2.value) WITH ORDINALITY AS uf2(v, i)
      WHERE
        forecast1._id = forecast2._id
        AND uf1.i = uf2.i
        AND forecast1._id <= 0
      SELECT
        forecast1._id AS f1_id,
        forecast2._id AS f2_id,
        uf1.v AS uf1_v,
        uf1.i AS uf1_i,
        uf2.v AS uf2_v,
        uf2.i AS uf2_i"
    #_{:explain? true})

  ;; Let's try with a JOIN condition
  ;; Now we obtain zero rows (?)
  (xt/q *node*
    "FROM forecast1,
     UNNEST(forecast1.value) WITH ORDINALITY AS uf1(v, i)
     JOIN forecast2 ON forecast1._id = forecast2._id,
     UNNEST(forecast2.value) WITH ORDINALITY AS uf2(v, i)
     WHERE
       uf1.i = uf2.i
       AND forecast1._id = 0
     SELECT
       forecast1._id AS f1_id,
       forecast2._id AS f2_id,
       uf1.v AS uf1_v,
       uf1.i AS uf1_i,
       uf2.v AS uf2_v,
       uf2.i AS uf2_i"
    #_{:explain? true})

  ;; Let's try with CTEs
  (xt/q *node*
    "WITH f1 AS (
       FROM forecast1
       , UNNEST(forecast1.value) WITH ORDINALITY AS uf1(v, i)
       WHERE forecast1._id = 0
       SELECT _id AS f1_id, v AS f1_v, i AS f1_i
     ),
     f2 AS (
       FROM forecast2
       , UNNEST(forecast2.value) WITH ORDINALITY AS uf2(v, i)
       WHERE forecast2._id = 0
       SELECT _id AS f2_id, v AS f2_v, i AS f2_i
     )
     FROM f1, f2
     WHERE f1_i = f2_i -- comment for run success/uncomment for 'illegal copy src vector'
     SELECT f1_id, f2_id, f1_i, f2_i, f1_v, f2_v"
    {:explain? true})

  ;; Let's try with JOIN, again an 'illegal copy src vector'
  (xt/q *node*
    "WITH f1 AS (
       FROM forecast1
       , UNNEST(forecast1.value) WITH ORDINALITY AS uf1(v, i)
       WHERE forecast1._id = 0
       SELECT _id AS f1_id, v AS f1_v, i AS f1_i
     ),
     f2 AS (
       FROM forecast2
       , UNNEST(forecast2.value) WITH ORDINALITY AS uf2(v, i)
       WHERE forecast2._id = 0
       SELECT _id AS f2_id, v AS f2_v, i AS f2_i
     )
     FROM f1 JOIN f2 ON f1_i = f2_i
     SELECT f1_id, f2_id, f1_i, f2_i, f1_v, f2_v"
    #_{:explain? true})

  ;; Let's try with just the filtered forecast tables as CTEs
  (xt/q *node*
    "WITH f1 AS (
       FROM forecast1
       WHERE forecast1._id <= 1
       SELECT _id AS f1_id, value
     ),
     f2 AS (
       FROM forecast2
       WHERE forecast2._id <= 1
       SELECT _id AS f2_id, value
     )
     FROM f1
     JOIN f2 ON f1_id = f2_id
     , UNNEST(f1.value) WITH ORDINALITY AS uf1(f1_v, f1_i)
     --JOIN UNNEST(f2.value) WITH ORDINALITY AS uf2(f2_v, f2_i) ON f1_i = f2_i -- report issue!
     , UNNEST(f2.value) WITH ORDINALITY AS uf2(f2_v, f2_i)
     WHERE f1_i = f2_i
     SELECT f1_id, f2_id, f1_i, f1_v, f2_i, f2_v
     --SELECT f1_id, f1_i, f1_v"
    #_{:explain? true})

  ;; This last query works. Let's do the sum:
  (xt/q *node*
    "WITH f1 AS (
       FROM forecast1
       WHERE forecast1._id <= 1
       SELECT _id AS f1_id, value
     ),
     f2 AS (
       FROM forecast2
       WHERE forecast2._id <= 1
       SELECT _id AS f2_id, value
     )
     FROM f1
     JOIN f2 ON f1_id = f2_id
     , UNNEST(f1.value) WITH ORDINALITY AS uf1(f1_v, f1_i)
     , UNNEST(f2.value) WITH ORDINALITY AS uf2(f2_v, f2_i)
     WHERE f1_i = f2_i
     SELECT f1_id, f1_i, f1_v, SUM(f2_v)"
    {:explain? true}))
