(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            vega.consumer
            [vega.core :refer [config]]
            vega.infrastructure.db
            vega.infrastructure.reddit
            vega.infrastructure.sentry
            vega.infrastructure.telegram))

(integrant.repl/set-prep! (constantly config))
