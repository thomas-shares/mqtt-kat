(ns mqttkat.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(set! *warn-on-reflection* true)

(defn- gen-input-stream []
  (gen/fmap #(java.io.ByteArrayInputStream. %) (gen/bytes)))

;(def ^:const byte-array-type (type (byte-array 0)))
;(defn bytes? [x] (= (type x) byte-array-type))

(def short-values (s/int-in 0 65534))
;;(def qos #{0 1 2})

(s/def :mqtt/payload bytes?)
  ;(s/with-gen #(instance? java.io.InputStream %) gen-input-stream))

(s/def :mqtt/topic string?)
(s/def :mqtt/packet-identifier short-values)

(s/def :mqtt-connect/packet-type #{:CONNECT})
(s/def :mqtt-4-5/protocol-name #{"MQTT"})
(s/def :mqtt-4-5/protocol-version #{4 5})
(s/def :mqtt/clean-session? boolean?)
(s/def :mqtt/keep-alive short-values)
(s/def :mqtt/username string?)
(s/def :mqtt/password bytes?)
(s/def :mqtt/will-topic string?)
(s/def :mqtt/will-message string?)
(s/def :mqtt/will-qos #{0 1 2})
(s/def :mqtt/will-retain boolean?)
(s/def :mqtt/user-credentials (s/keys :req-un [:mqtt/username]
                                      :opt-un [:mqtt/password]))
(s/def :mqtt/will (s/keys :req-un [:mqtt/will-topic
                                   :mqtt/will-message
                                   :mqtt/will-qos
                                   :mqtt/will-retain]))
(s/def :mqtt/client-id (s/and string? #(<= 1 (count %) 23)))
  
(s/def :mqtt/connect
  (s/keys :req-un [:mqtt-connect/packet-type
                   :mqtt-4-5/protocol-name
                   :mqtt-4-5/protocol-version
                   :mqtt/keep-alive
                   :mqtt/clean-session?
                   :mqtt/client-id]
          :opt-un [:mqtt/user-credentials
                   :mqtt/will]))

(s/def :mqtt-connack/packet-type #{:CONNACK})
(s/def :mqtt/session-present? boolean?)
(s/def :mqtt/connect-return-code  #{0x00 0x01 0x02 0x03 0x04 0x05})
(s/def :mqtt/connack
  (s/keys :req-un [:mqtt-connack/packet-type
                   :mqtt/session-present?
                   :mqtt/connect-return-code]))

;;publish
(s/def :mqtt/duplicate? boolean?)
(s/def :mqtt-qos-0/qos #{0})
(s/def :mqtt-qos-gt0/qos #{1 2})
(s/def :mqtt/retain? boolean?)
(s/def :mqtt-publish/packet-type #{:PUBLISH})

(s/def :mqtt/publish
  (s/or :qos-0 (s/keys :req-un [:mqtt-publish/packet-type
                                :mqtt-qos-0/qos
                                :mqtt/retain?
                                :mqtt/topic
                                :mqtt/payload])
        :qos-gt0 (s/keys :req-un [:mqtt-publish/packet-type
                                  :mqtt-qos-gt0/qos
                                  :mqtt/retain?
                                  :mqtt/duplicate?
                                  :mqtt/topic
                                  :mqtt/payload
                                  :mqtt/packet-identifier])))

(s/def :mqtt/topic-filter (s/and string? #(<= 1 (count %))))
(s/def :mqtt/qos #{0 1 2})
(s/def :mqtt/topic_
  (s/keys :req-un [:mqtt/topic-filter
                   :mqtt/qos]))
(s/def :mqtt-subscribe/topics (s/coll-of :mqtt/topic_))

(s/def :mqtt-subscribe/packet-type #{:SUBSCRIBE})
(s/def :mqtt/subscribe
  (s/keys :req-un [:mqtt-subscribe/packet-type
                   :mqtt/packet-identifier
                   :mqtt-subscribe/topics]))


(s/def :mqtt-suback/packet-type #{:SUBACK})
(s/def :mqtt/suback-fields #{0 1 2 128})

(s/def :mqtt-suback/response (s/coll-of :mqtt/suback-fields))
(s/def :mqtt/suback
  (s/keys :req-un [:mqtt-suback/packet-type
                   :mqtt/packet-identifier
                   :mqtt-suback/response]))

(s/def :mqtt-unsubscribe/packet-type #{:UNSUBSCRIBE})
(s/def :mqtt-unsubscribe/topic (s/and string? #(<= 1 (count %))))
(s/def :mqtt-unsubscribe/topics (s/coll-of :mqtt-unsubscribe/topic))
(s/def :mqtt/unsubscribe
  (s/keys :req-un [:mqtt-unsubscribe/packet-type
                   :mqtt/packet-identifier
                   :mqtt-unsubscribe/topics]))

(s/def :mqtt-unsuback/packet-type #{:UNSUBACK})
(s/def :mqtt/unsuback
  (s/keys :req-un [:mqtt-unsuback/packet-type
                   :mqtt/packet-identifier]))

(s/def :mqtt-pingreq/packet-type #{:PINGREQ})
(s/def :mqtt/pingreq
  (s/keys :req-un [:mqtt-pingreq/packet-type]))

(s/def :mqtt-pingresp/packet-type #{:PINGRESP})
(s/def :mqtt/pingresp
  (s/keys :req-un [:mqtt-pingresp/packet-type]))

(s/def :mqtt-disconnect/packet-type #{:DISCONNECT})
(s/def :mqtt/disconnect
  (s/keys :req-un [:mqtt-disconnect/packet-type]))
