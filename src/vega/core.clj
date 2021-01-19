(ns vega.core
  (:require [clojure.core.async :refer [<!!]]
            [clojure.string :as s]
            [datahike.api :as d]
            [environ.core :refer [env]]
            [integrant.core :as ig]
            [java-time :as t]
            [morse.api :as api]
            [morse.handlers :refer [handlers command-fn message-fn]]
            [morse.polling :as polling]
            [taoensso.timbre :as timbre])
  (:gen-class))

(def config
  {:telegram/polling {:token   (env :telegram-token)
                      :handler (ig/ref :telegram/handler)}

   :telegram/handler {:token    (env :telegram-token)
                      :db-setup (ig/ref :db/setup)}

   :db/setup {:store      {:backend :file
                           :path    "/tmp/vegadb"}
              :initial-tx [{:db/ident       :friend/name
                            :db/valueType   :db.type/string
                            :db/cardinality :db.cardinality/one}
                           {:db/ident       :friend/zone-id
                            :db/valueType   :db.type/string
                            :db/cardinality :db.cardinality/one}]
              :name       "vegadb"}

   :etc/logging {:level (keyword (env :log-level))}})

(defmethod ig/init-key :db/setup [_ opts]
  (when-not (d/database-exists? opts)
    (d/create-database opts)

    (let [conn (d/connect opts)]
      (d/transact conn [#:friend {:name    "thiago"
                                  :zone-id "Europe/Lisbon"}
                        #:friend {:name    "pedrotti"
                                  :zone-id "Europe/Berlin"}
                        #:friend {:name    "castro"
                                  :zone-id "America/Campo_Grande"}])))

  opts)

(defmethod ig/init-key :etc/logging [_ {:keys [level]
                                        :or   {level :info}}]
  (timbre/set-level! level))

(def default-zone (t/zone-id "America/Sao_Paulo"))

(defn now
  []
  (t/zoned-date-time default-zone))

(defn friend-time
  [db-setup friend]
  (let [conn      (d/connect db-setup)
        [zone-id] (d/q '[:find [?z]
                         :in $ ?name
                         :where
                         [?f :friend/name ?name]
                         [?f :friend/zone-id ?z]]
                       @conn friend)]
    (t/format "HH:mm"
              (t/with-zone-same-instant (now) (or zone-id
                                                  default-zone)))))

(defmethod ig/init-key :telegram/handler [_ {:keys [db-setup
                                                    token]}]
  (when (s/blank? token)
    (timbre/info "Please provide token in TELEGRAM_TOKEN environment variable!")
    (System/exit 1))

  (handlers
    (command-fn "start"
      (fn [{{id :id :as chat} :chat}]
        (timbre/info "Bot joined new chat: " chat)
        (api/send-text token id "Vega initialized.")))

    (command-fn "help"
      (fn [{{id :id :as chat} :chat}]
        (timbre/info "Help was requested in " chat)
        (api/send-text token id "No.")))

    (command-fn "time"
      (fn [{:keys [text chat]}]
        (api/send-text token (:id chat) (friend-time db-setup
                                                     (second (s/split text #" "))))))

    (message-fn
        (fn [{:keys [text chat]}]
          (when (s/includes? (s/lower-case text) "this is the way")
            (api/send-text token (:id chat) "This is the way."))))

    (message-fn
      (fn [message]
        (println "Intercepted message: " message)
        (println "Not doing anything with this message.")))))

(defmethod ig/init-key :telegram/polling [_ {:keys [handler
                                                    token]}]
  (fn []
    (timbre/info "Starting Vega...")
    (polling/start token handler
                  {:timeout 4})))

(defn start [_]
  (let [{:telegram/keys [polling]} (ig/init config)]
    (<!! (polling))))

(defn -main
  []
  (start {}))
