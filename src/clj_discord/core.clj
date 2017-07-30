(ns clj-discord.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [gniazdo.core :as ws])
  (:import (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.websocket.client WebSocketClient)))

(defonce the-token (atom nil))
(defonce the-gateway (atom nil))
(defonce the-socket (atom nil))
(defonce the-heartbeat-interval (atom nil))
(defonce the-keepalive (atom false))
(defonce the-seq (atom nil))
(defonce reconnect-needed (atom false))

(def default-max-text-message-size (* 64 1024))

(defn disconnect []
  (reset! reconnect-needed false)
  (reset! the-keepalive false)
  (if (not (nil? @the-socket)) (ws/close @the-socket))
  (reset! the-token nil)
  (reset! the-gateway nil)
  (reset! the-socket nil)
  (reset! the-seq nil)
  (reset! the-heartbeat-interval nil))

(defn connect
  ([token functions log-events]
   (connect token functions log-events default-max-text-message-size))
  ([token functions log-events max-text-message-size]
   (disconnect)
   (reset! the-keepalive true)
   (reset! the-token (str "Bot " token))
   (reset! the-gateway (str
                        (get
                         (json/read-str
                          (:body (http/get "https://discordapp.com/api/gateway"
                                           {:headers {:authorization @the-token}})))
                         "url")
                        "?v=6&encoding=json"))
   (reset! the-socket
           (let [client (new WebSocketClient (new SslContextFactory))]
             (.setMaxTextMessageSize (.getPolicy client) max-text-message-size)
             (.start client)
             (ws/connect
              @the-gateway
              :client client
              :on-receive #(let [received (json/read-str %)
                                 logevent (if log-events (println "\n" %))
                                 op (get received "op")
                                 type (get received "t")
                                 data (get received "d")
                                 seq (get received "s")]
                             (if (= 10 op) (reset! the-heartbeat-interval (get data "heartbeat_interval")))
                             (if (not (nil? seq)) (reset! the-seq seq))
                             (if (not (nil? type)) (doseq [afunction (get functions type (get functions "ALL_OTHER" []))]
                                                     (afunction type data)))))))
   (.start (Thread. (fn []
                      (try
                        (while @the-keepalive
                          (if (nil? @the-heartbeat-interval)
                            (Thread/sleep 100)
                            (do
                              (if log-events (println "\nSending heartbeat " @the-seq))
                              (ws/send-msg @the-socket (json/write-str {:op 1, :d @the-seq}))
                              (Thread/sleep @the-heartbeat-interval)
                              )))
                        (catch Exception e (do
                                             (println "\nCaught exception: " (.getMessage e))
                                             (reset! reconnect-needed true)
                                             ))))))
   (Thread/sleep 1000)
   (ws/send-msg @the-socket (json/write-str {:op 2, :d {"token" @the-token
                                                        "properties" {"$os" "linux"
                                                                      "$browser" "clj-discord"
                                                                      "$device" "clj-discord"
                                                                      "$referrer" ""
                                                                      "$referring_domain" ""}
                                                        "compress" false}}))
   (while (not @reconnect-needed) (Thread/sleep 1000))
   (connect token functions log-events)))

(defn post-message [channel_id message]
  (http/post (str "https://discordapp.com/api/channels/" channel_id "/messages")
             {:body (json/write-str {:content message
                                     :nonce (str (System/currentTimeMillis))
                                     :tts false})
              :headers {:authorization @the-token}
              :content-type :json
              :accept :json}))

(defn post-message-with-file [channel_id message file_name]
  (http/post (str "https://discordapp.com/api/channels/" channel_id "/messages")
             {:multipart [{:name "content" :content message}
                          {:name "nonce" :content (str (System/currentTimeMillis))}
                          {:name "tts" :content "false"}
                          {:name file_name :part_name "file" :content (io/file file_name)}]
              :headers {:authorization @the-token}}))

(defn post-message-with-mention [channel_id message user_id]
  (post-message channel_id (str "<@" user_id ">" message)))

(defn answer-command [data command answer]
  (if (= command (get data "content"))
    (post-message-with-mention
      (get data "channel_id")
      (str " " answer)
      (get (get data "author") "id"))))
