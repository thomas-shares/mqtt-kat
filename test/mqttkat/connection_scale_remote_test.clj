(ns mqttkat.connection-scale-remote-test
  "How many connections the broker will hold when it is a process of its own.

   The in-process test (connection-scale-test) is limited by having both ends
   in one JVM: 25,000 connections there is 50,000 sockets in one process, and
   the kernel's buffers for those are native memory that got the JVM SIGKILLed.
   Here the broker is started from the uberjar as a subprocess, so it carries
   only its own half.

   Two things make it possible to go past the ~28,232 ephemeral ports that
   capped the in-process test:

     - the client sockets are spread across source addresses in 127.0.0.0/8.
       A connection is identified by the whole four-tuple, so each source
       address brings its own range of local ports. Linux lets any user bind
       to any 127.x.y.z without configuring it first. How many connections one
       address is asked for matters a great deal — see per-source-ip.
     - no thread and no reader per connection. The CONNACKs are left in the
       socket buffers unread; what proves the broker accepted them is the
       broker's own stats line, read from its stdout, which is a better
       witness anyway since it comes from the process under test.

   Tagged ^:performance. Run it with

     lein test :performance :only mqttkat.connection-scale-remote-test

   and watch the other side with `ss -tan | grep -c ESTAB`."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io BufferedReader]
           [java.net InetSocketAddress]
           [java.nio.channels SocketChannel]
           [java.util.concurrent ConcurrentLinkedQueue]
           [org.mqttkat.packages MqttConnect]))

(def ^:private broker-port 21883)

(def ^:private ramp
  "Connection counts to try. -Dmqttkat.remoteScaleRamp=10000,50000 to change."
  (if-let [override (System/getProperty "mqttkat.remoteScaleRamp")]
    (mapv #(Long/parseLong (str/trim %)) (str/split override #","))
    [10000 25000 50000]))

(defn- ephemeral-range
  "net.ipv4.ip_local_port_range, or nil.

   slurp rather than Files/readString: a /proc file reports a size of zero, and
   readString sizes its buffer from that and comes back with a single
   character. That is what made /proc look unreadable from this JVM earlier."
  []
  (try
    (let [[lo hi] (map parse-long (str/split (str/trim (slurp "/proc/sys/net/ipv4/ip_local_port_range"))
                                             #"\s+"))]
      (when (and lo hi (< lo hi)) [lo hi]))
    (catch Exception _ nil)))

(def ^:private bind-port-capacity
  "Source ports one address can actually give a bind(addr, 0) — HALF the
   ephemeral range, not all of it.

   Linux splits the range between its two allocators: bind() is handed the odd
   ports and connect() the even ones. Measured on this machine, 400 sockets
   each way: bind(src,0)+connect got 400 odd ports and 0 even, a plain
   connect() got 400 even and 0 odd. Since these sockets bind a source address
   explicitly — that is the whole point of spreading them — only the odd half
   is available, so a 32768-60999 range is 14,116 ports per address and not
   28,232."
  (if-let [[lo hi] (ephemeral-range)]
    (quot (inc (- hi lo)) 2)
    14000))

(def ^:private per-source-ip
  "Connections per source address: 70% of what one address can supply.

   This was 20,000, which is 40% more than exists. The allocator stays fast
   while it can find a free odd port quickly and collapses as they run out —
   opening 25,000 sockets measured 9,000-11,000/s up to 12,500 on one address,
   then 537/s, then 220/s for the rest of that address's share, and 12,700/s
   again the instant it moved to the next one. 25,000 connections took 29.1s;
   at 70% occupancy they take 2.5s and 50,000 take 4.1s.

   70% rather than 95% because the knee is at roughly 88% and the rest of the
   machine is drawing on the same range."
  (long (* 0.7 bind-port-capacity)))

(def ^:private uberjar "target/mqtt-kat-0.0.1-standalone.jar")

;; ── the broker, over there ───────────────────────────────────────────────

(defn- start-broker!
  "Run the uberjar as a subprocess and wait for it to be listening.

   Its stdout is drained on a thread of its own into `lines`: the stats line it
   prints every ten seconds is how this test sees what the broker thinks, and
   an undrained pipe would eventually block the broker itself."
  [lines]
  ;; The command goes through a hinted local rather than being hinted in
  ;; place: ProcessBuilder has both a List and a String... constructor, and a
  ;; tag on the vector literal is not enough to pick between them — it compiles
  ;; but resolves the call reflectively every time.
  (let [^java.util.List command ["java" "-Xmx3G" "-jar" uberjar (str broker-port)]
        pb (doto (ProcessBuilder. command)
             (.redirectErrorStream true))
        proc (.start pb)]
    (.start (Thread. (fn []
                       (with-open [^BufferedReader r (io/reader (.getInputStream proc))]
                         (doseq [line (line-seq r)]
                           (.add ^ConcurrentLinkedQueue lines line))))))
    proc))

(defn- broker-connected
  "The connected count from the most recent stats line the broker has printed,
   or nil if it has not printed one since `lines` was last drained."
  [^ConcurrentLinkedQueue lines]
  (loop [latest nil]
    (if-let [line (.poll lines)]
      (recur (or (some-> (re-find #":connected (\d+)" line) second Long/parseLong)
                 latest))
      latest)))

(defn- wait-for-broker-count [lines n ms what]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [latest nil]
      (let [seen (or (broker-connected lines) latest)]
        (cond
          (= n seen) true
          (> (System/currentTimeMillis) deadline)
          (do (is false (str "timed out waiting for " what "; broker last reported " seen))
              false)
          :else (do (Thread/sleep 250) (recur seen)))))))

;; ── the clients, over here ───────────────────────────────────────────────

(defn- connect-bytes [id]
  (let [buf (MqttConnect/encode {:packet-type :CONNECT :protocol-name "MQTT"
                                 :protocol-version 4 :keep-alive 0
                                 :clean-session? true :client-id id})
        a (byte-array (.remaining buf))]
    (.get (.duplicate buf) a)
    a))

(defn- source-address
  "The address the i-th socket of a rung binds to.

   The rung gets its own block of addresses. Rungs share a machine but not
   ports: closing a rung's sockets leaves its local ports in TIME-WAIT for a
   minute, and the next rung starting on the same addresses would be picking
   from a range that is still half full."
  [rung i]
  (str "127.0." rung "." (inc (quot i per-source-ip))))

(defn- source-ip-count [rung n]
  (count (into #{} (map #(source-address rung %)) (range n))))

(defn- open-connections!
  "Open `n` sockets, each with a CONNECT written into it, spreading the source
   address so the ephemeral port range is not the limit."
  [n rung]
  (let [started (System/nanoTime)
        target (InetSocketAddress. "127.0.0.1" ^int (int broker-port))
        socks (loop [i 0, acc (transient [])]
                (if (= i n)
                  (persistent! acc)
                  (let [src (source-address rung i)
                        ch (doto (SocketChannel/open)
                             (.bind (InetSocketAddress. ^String src 0))
                             (.connect target))]
                    (.write ch (java.nio.ByteBuffer/wrap (connect-bytes (str "remote-" i))))
                    (recur (inc i) (conj! acc ch)))))]
    {:socks socks :ms (quot (- (System/nanoTime) started) 1000000)}))

(defn- rss-mb
  "Resident size of a pid, via ps. /proc is not readable from this JVM."
  [pid]
  (try
    (let [^java.util.List command ["ps" "-o" "rss=" "-p" (str pid)]
          p (.start (doto (ProcessBuilder. command)
                      (.redirectErrorStream true)))]
      (with-open [r (io/reader (.getInputStream p))]
        (some-> (first (line-seq r)) str/trim not-empty Long/parseLong (quot 1024))))
    (catch Exception _ nil)))

(defn- heap-mb []
  (let [rt (Runtime/getRuntime)]
    (quot (- (.totalMemory rt) (.freeMemory rt)) (* 1024 1024))))

(defn- run-step!
  "One rung of the ramp. Returns true if the broker held them all, so the
   caller knows whether it is worth asking for more."
  [n rung ^Process proc lines]
  (testing (str n " connections, broker out of process")
    (let [{:keys [socks ms]} (open-connections! n rung)
          ok (wait-for-broker-count lines n 180000 (str n " connections"))]
      (is ok (str "the broker should be holding " n " connections"))
      (println
       (format "  %6d connections in %6d ms  (%6.0f/s)  client heap %4d MB  broker RSS %5s MB  source ips %d"
               n ms (/ (double n) (max 1 (/ ms 1000.0)))
               (heap-mb) (or (rss-mb (.pid proc)) "?")
               (source-ip-count rung n)))
      (doseq [^SocketChannel c socks]
        (try (.close c) (catch Exception _ nil)))
      (wait-for-broker-count lines 0 180000 "the broker to notice they had gone")
      (System/gc)
      ;; Let the ports out of TIME-WAIT before asking for more.
      (Thread/sleep 5000)
      ok)))

(deftest the-source-ip-budget-fits-the-ports-that-exist
  (testing "no address is asked for more ports than bind() can give it"
    ;; Not tagged :performance, so it runs in the ordinary suite: the failure
    ;; it guards against is silent. Asking one address for more ports than
    ;; exist does not error, it just gets slow — 25,000 connections took 29.1s
    ;; instead of 2.5s, and the number that had to change is a constant three
    ;; lines away from the comment explaining it.
    (is (<= per-source-ip bind-port-capacity)
        (str "per-source-ip is " per-source-ip " but one address can only "
             "supply " bind-port-capacity " — bind() gets the odd half of "
             "net.ipv4.ip_local_port_range"))
    (is (<= (/ (double per-source-ip) bind-port-capacity) 0.8)
        "and it should stay clear of the knee, which is at roughly 88%"))

  (testing "each rung gets source addresses of its own"
    ;; So a rung never starts on ports the previous rung left in TIME-WAIT.
    (is (empty? (set/intersection
                 (into #{} (map #(source-address 0 %)) (range 50000))
                 (into #{} (map #(source-address 1 %)) (range 50000))))))

  (testing "the addresses stay inside 127.0.0.0/8"
    ;; Beyond it they are someone else's, and the bind would fail rather than
    ;; quietly using the wrong interface.
    (doseq [rung (range (count ramp))]
      (let [last-octet (inc (quot (dec (long (apply max ramp))) per-source-ip))]
        (is (< last-octet 256) "the ramp needs more addresses than an octet holds")
        (is (< rung 256) "and more rungs than an octet holds")))))

(deftest ^:performance connection-scale-out-of-process
  (if-not (.exists (io/file uberjar))
    (println "  skipped:" uberjar "is not built — run `lein uberjar` first")
    (let [lines (ConcurrentLinkedQueue.)
          ^Process proc (start-broker! lines)]
      (try
        (is (wait-for-broker-count lines 0 30000 "the broker to start reporting")
            "the broker should come up and print its first stats line")
        ;; Stops at the first rung it cannot reach, rather than piling more on
        ;; a machine that has just told us it is full.
        (loop [[n & more] ramp, rung 0]
          (when (and n (run-step! n rung proc lines))
            (recur more (inc rung))))
        (finally
          (.destroyForcibly proc)
          (.waitFor proc))))))
