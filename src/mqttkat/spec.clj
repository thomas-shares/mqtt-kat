(ns mqttkat.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(set! *warn-on-reflection* true)

(defn- gen-input-stream []
  (gen/fmap #(java.io.ByteArrayInputStream. %) (gen/bytes)))

;(def ^:const byte-array-type (type (byte-array 0)))
;(defn bytes? [x] (= (type x) byte-array-type))

(s/def :mqtt/packet-type #{:CONNECT :CONACK :PUBLISH :PUBACK :PUBREC :PUBREL :PUBCOMP :SUBSCRIBE :SUBACK :UNSUBSCRIBE :UNSUBACK :PINGREQ :PINGRESP :AUTHENTICATE})

(def short-values (s/int-in 0 65534))
(def qos #{0 1 2})

(s/def :mqtt/payload bytes?)
  ;(s/with-gen #(instance? java.io.InputStream %) gen-input-stream))


(s/def :mqtt/topic string?)
(s/def :mqtt/packet-identifier short-values)


(s/def :mqtt/protocol-name #{"MQIsdp" "MQTT"})
(s/def :mqtt/protocol-version #{3 4 5})
(s/def :mqtt/connect-flags-username-flag boolean?)
(s/def :mqtt/connect-flags-password-flag boolean?)
(s/def :mqtt/connect-flags-will-retain boolean?)
(s/def :mqtt/connect-flags-will-qos qos)
(s/def :mqtt/connect-flags-will-flag boolean?)
(s/def :mqtt/connect-flags-clean-session boolean?)

(s/def :mqtt/keep-alive short-values)
(s/def :mqtt/flags #{0})
(s/def :mqtt/username string?)
(s/def :mqtt/password string?)

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
  (s/keys :req-un [:mqtt/flags
                   :mqtt/protocol-name
                   :mqtt/protocol-version
                   :mqtt/keep-alive
                   :mqtt/connect-flags-will-qos
                   :mqtt/connect-flags-will-flag
                   :mqtt/connect-flags-will-retain
                   :mqtt/connect-flags-username-flag
                   :mqtt/connect-flags-password-flag
                   :mqtt/connect-flags-clean-session]))

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
