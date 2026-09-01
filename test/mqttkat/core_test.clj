(ns mqttkat.core-test
  "Codec round trips: a generated packet map, encoded to the wire and decoded
   again, must come back as the same map.

   No broker and no sockets. The old version of this namespace sent packets to
   a broker and blocked on `<!!` waiting for them to come back, which meant any
   codec asymmetry hung the whole suite instead of failing one test. Decoding
   is a pure function of the bytes, so it is tested as one — the network path
   is flow-test's job."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is testing]]
            [mqttkat.spec])
  (:import [java.nio ByteBuffer]
           [java.util Arrays]
           [org.mqttkat.packages
            MqttConnAck MqttConnect MqttDisconnect MqttPingReq MqttPingResp
            MqttPubAck MqttPubComp MqttPubRec MqttPubRel MqttPublish
            MqttSubAck MqttSubscribe MqttUnSubAck MqttUnsubscribe]))

(def ^:private samples-per-packet 20)

(defn- wire-bytes
  "The bytes an encoder produced. Encoders hand back a flipped buffer."
  [^ByteBuffer buf]
  (let [out (byte-array (.remaining buf))]
    (.get (.duplicate buf) out)
    out))

(defn- split-packet
  "Strip the fixed header off a packet: [flags body-bytes]. The remaining-length
   field is the MQTT variable-length integer, up to four bytes."
  [^bytes wire]
  (let [flags (bit-and (aget wire 0) 0x0f)]
    (loop [i 1, multiplier 1, length 0]
      (let [b      (aget wire i)
            length (+ length (* (bit-and b 0x7f) multiplier))]
        (if (zero? (bit-and b 0x80))
          [flags (Arrays/copyOfRange wire (int (inc i)) (int (+ (inc i) length)))]
          (recur (inc i) (* multiplier 128) length))))))

(defn- comparable
  "Drop the SelectionKey the decoder stamps on, and make byte-array payloads
   comparable by value — `=` on two byte arrays is identity."
  [m]
  (cond-> (dissoc m :client-key)
    (:payload m) (update :payload #(seq (if (string? %) (.getBytes ^String %) ^bytes %)))))

(defn- round-trip
  "Encode `m`, decode the bytes back, and return the decoded map."
  [encode decode m]
  (let [[flags body] (split-packet (wire-bytes (encode m)))]
    (decode flags body)))

(def ^:private packets
  "Each entry: the spec to generate from, how to encode, how to decode."
  [{:name :CONNECT     :spec :mqtt/connect
    :encode #(MqttConnect/encode %)     :decode #(MqttConnect/decode nil (byte %1) %2)}
   {:name :CONNACK     :spec :mqtt/connack
    :encode #(MqttConnAck/encode %)     :decode (fn [_ body] (MqttConnAck/decode nil body))}
   {:name :PUBLISH     :spec :mqtt/publish-qos-gt0
    :encode #(MqttPublish/encode %)     :decode #(MqttPublish/decode nil (byte %1) %2)}
   {:name :PUBACK      :spec :mqtt/puback
    :encode #(MqttPubAck/encode %)      :decode (fn [_ body] (MqttPubAck/decode nil body))}
   {:name :PUBREC      :spec :mqtt/pubrec
    :encode #(MqttPubRec/encode %)      :decode (fn [_ body] (MqttPubRec/decode nil body))}
   {:name :PUBREL      :spec :mqtt/pubrel
    :encode #(MqttPubRel/encode %)      :decode (fn [_ body] (MqttPubRel/decode nil body))}
   {:name :PUBCOMP     :spec :mqtt/pubcomp
    :encode #(MqttPubComp/encode %)     :decode (fn [_ body] (MqttPubComp/decode nil body))}
   {:name :SUBSCRIBE   :spec :mqtt/subscribe
    :encode #(MqttSubscribe/encode %)   :decode (fn [_ body] (MqttSubscribe/decode nil body))}
   {:name :SUBACK      :spec :mqtt/suback
    :encode #(MqttSubAck/encode %)      :decode (fn [_ body] (MqttSubAck/decode nil body))}
   {:name :UNSUBSCRIBE :spec :mqtt/unsubscribe
    :encode #(MqttUnsubscribe/encode %) :decode (fn [_ body] (MqttUnsubscribe/decode nil body))}
   {:name :UNSUBACK    :spec :mqtt/unsuback
    :encode #(MqttUnSubAck/encode %)    :decode (fn [_ body] (MqttUnSubAck/decode nil body))}])

(deftest packet-round-trips
  (doseq [{:keys [name spec encode decode]} packets]
    (testing (str name " survives encode → decode")
      (doseq [m (gen/sample (s/gen spec) samples-per-packet)]
        (is (= (comparable m) (comparable (round-trip encode decode m)))
            (str name " round trip failed for " (pr-str m)))))))

(deftest bodyless-packets-decode
  (testing "packets that are nothing but a fixed header"
    (is (= :PINGREQ    (:packet-type (MqttPingReq/decode nil))))
    (is (= :PINGRESP   (:packet-type (MqttPingResp/decode nil))))
    (is (= :DISCONNECT (:packet-type (MqttDisconnect/decode nil))))))

(deftest fixed-header-is-stripped-correctly
  (testing "the remaining-length field is read, not assumed to be one byte"
    (let [big     (apply str (repeat 200 "x"))
          payload (.getBytes ^String big)
          wire    (wire-bytes (MqttPublish/encode {:packet-type :PUBLISH :qos 0 :topic "t"
                                                   :retain? false :duplicate? false
                                                   :payload payload}))
          [_ body] (split-packet wire)]
      (is (= 2 (- (count wire) (count body) 1)) "a >127 byte packet needs a two-byte length field")
      (is (= (seq payload) (seq (:payload (MqttPublish/decode nil (byte 0) body))))))))
