(ns scenarios.notebooks.graph
  (:require [nextjournal.clerk :as clerk]))

(def single-color-scale
  [[0.0 "rgb(230, 247, 255)"]  ;; Light blue at the start
   [1.0 "rgb(0, 76, 153)"]])   ;; Dark blue at the end

^{:nextjournal.clerk/visibility :hide}
(defn line-graph-data [filename]
  (->> (read-string (slurp filename))
    (map (fn [[k v]] (merge k v)))
    (group-by :systems)
    (sort-by first >)
    (map (fn [[systems results]]
           (let [results (sort-by :days results)]
             {:name (str "systems=" systems)
              :x (map :days results)
              :y (map (comp #(/ % 1000) :ellapsed) results)
              :type "scatter"
              :mode "lines+markers"
              #_#_:marker {:color systems
                           :colorscale single-color-scale
                           :cmin 0
                           :cmax 1100}})))))

^{:nextjournal.clerk/visibility :hide}
(comment
  (line-graph-data "readings-results.edn"))

(clerk/plotly {:data (line-graph-data "readings-results.edn")
               :layout {:xaxis {:title "period size (days)"}
                        :yaxis {:title "query time (s)"}}})
