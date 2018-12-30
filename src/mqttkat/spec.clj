(ns mqttkat.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(set! *warn-on-reflection* true)

(defn- gen-input-stream []
  (gen/fmap #(java.io.ByteArrayInputStream. %) (gen/bytes)))

;(def ^:const byte-array-type (type (byte-array 0)))
;(defn bytes? [x] (= (type x) byte-array-type))

(def short-values (s/int-in 0 65534))
(def qos #{0 1 2})

(s/def :mqtt/payload bytes?)
  ;(s/with-gen #(instance? java.io.InputStream %) gen-input-stream))

(s/def :mqtt/topic string?)
(s/def :mqtt/packet-identifier short-values)

(s/def :mqtt-connect/packet-type #{:CONNECT})
(s/def :mqtt-3/protocol-name #{"MQIsdp"})
(s/def :mqtt-3/protocol-version #{(byte 3)})
(s/def :mqtt-4-5/protocol-name #{"MQTT"})
(s/def :mqtt-4-5/protocol-version #{(byte 4) (byte 5)})
(s/def :mqtt/clean-session boolean?)
(s/def :mqtt/keep-alive short-values)
(s/def :mqtt/username string?)
(s/def :mqtt/password string?)
(s/def :mqtt/will-topic string?)
(s/def :mqtt/will-message string?)
(s/def :mqtt/will-qos qos)
(s/def :mqtt/will-retain boolean?)
(s/def :mqtt/user-credentials (s/keys :req-un [:mqtt/username]
                                      :opt-un [:mqtt/password]))
(s/def :mqtt/will (s/keys :req-un [:mqtt/will-topic
                                   :mqtt/will-message
                                   :mqtt/will-qos
                                   :mqtt/will-retain]))
(s/def :mqtt/client-id (s/and string? #(<= 1 (count %) 23)))

;(s/def ::name string?)
;(s/def ::password string?)
;(s/def ::login-present? boolean)
;(s/def ::client-id string?)

;(s/def ::connect_xxxx (s/keys :req [client-id] :opt []))
;(s/def ::user-creds
;  (s/and #(::login-present? %
;               (s/keys :req [::name ::password ::login-present]))

;(s/def ::pubsub (s/or :connect ::connect
;                      :login-present? ::user-creds)

(s/def :mqtt/connect
  (s/or :3 (s/keys :req-un [:mqtt-connect/packet-type
                            :mqtt-3/protocol-name
                            :mqtt-3/protocol-version
                            :mqtt/keep-alive
                            :mqtt/clean-session
                            :mqtt/client-id]
                   :opt-un [:mqtt/user-credentials
                            :mqtt/will])
        :4-5 (s/keys :req-un [:mqtt-connect/packet-type
                              :mqtt-4-5/protocol-name
                              :mqtt-4-5/protocol-version
                              :mqtt/keep-alive
                              :mqtt/clean-session
                              :mqtt/client-id]
                     :opt-un [:mqtt/user-credentials
                              :mqtt/will])))



;;publish
(s/def :mqtt/publish-duplicate boolean?)
(s/def :mqtt/publish-qos qos)
(s/def :mqtt/publish-retain boolean?)

(s/def :mqtt/publish
  (s/keys :req-un [:mqtt/publish-qos
                   :mqtt/publish-retain
                   :mqtt/topic
                   :mqtt/payload]
          :opt-un [:mqtt/packet-identifier]))

(s/def :mqtt/topic-filter string?)
(s/def :mqtt/topics (s/and :mqtt/topic-filter qos))

(s/def :mqtt/subscribe
  (s/keys :req-un []))
