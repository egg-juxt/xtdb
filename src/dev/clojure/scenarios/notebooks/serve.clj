(ns scenarios.notebooks.serve
  (:require [nextjournal.clerk :as clerk]))


;; start Clerk's built-in webserver on the default port 7777, opening the browser when done
(comment
  (clerk/serve! {:browse? true})

  ;; either call `clerk/show!` explicitly to show a given notebook, or use the File Watcher described below.
  (clerk/serve! {:watch-paths ["src/dev/clojure"]}))
  ;(clerk/show! "notebooks/rule_30.clj"))