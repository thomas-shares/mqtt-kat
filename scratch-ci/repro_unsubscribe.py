# Throwaway: replays what test_unsubscribe inherits when test_subscribe_options
# loses its race -- both clients left connected, bclient subscribed to TopicA --
# then runs test_unsubscribe's body. Run from paho.mqtt.testing/interoperability.
import sys, time, logging
sys.path.insert(0, ".")
import mqtt.clients.V5 as mqtt_client
import mqtt.formats.MQTTV5 as MQTTV5
from client_test5 import Callbacks

host, port = "localhost", 1883
p = "client_test5/"
topics = [p + t for t in ["TopicA", "TopicA/B", "Topic/C", "TopicA/C", "/TopicA"]]

def run(i, leave_connected):
    cb, cb2 = Callbacks(), Callbacks()
    a = mqtt_client.Client(b"myclientid"); a.registerCallback(cb)
    b = mqtt_client.Client(b"myclientid2"); b.registerCallback(cb2)
    if leave_connected:
        a.connect(host=host, port=port, cleanstart=True)
        b.connect(host=host, port=port, cleanstart=True)
        a.subscribe([topics[0]], [MQTTV5.SubscribeOptions(2, noLocal=True)])
        b.subscribe([topics[0]], [MQTTV5.SubscribeOptions(2, noLocal=True)])
        time.sleep(0.2)
        a.publish(topics[0], b"noLocal test", 1, retained=False)
        time.sleep(0.5)
        # no disconnect: the failed assertion skipped it
    cb2.clear()
    b.connect(host=host, port=port, cleanstart=True)
    b.subscribe([topics[0]], [MQTTV5.SubscribeOptions(2)])
    b.subscribe([topics[1]], [MQTTV5.SubscribeOptions(2)])
    b.subscribe([topics[2]], [MQTTV5.SubscribeOptions(2)])
    time.sleep(1)
    b.unsubscribe([topics[0]])
    cb2.clear()
    a.connect(host=host, port=port, cleanstart=True)
    a.publish(topics[0], b"topic 0 - unsubscribed", 1, retained=False)
    a.publish(topics[1], b"topic 1", 1, retained=False)
    a.publish(topics[2], b"topic 2", 1, retained=False)
    time.sleep(2)
    ok = True
    try:
        b.disconnect(); a.disconnect()
    except Exception as e:
        print("disconnect raised", repr(e)); ok = False
    n = len(cb2.messages)
    good = ok and n == 2
    print("run %d leave_connected=%s -> %d messages %s %s" % (i, leave_connected, n,
          "OK" if good else "FAIL", "" if good else [(m[0], m[1]) for m in cb2.messages]), flush=True)
    return good

if __name__ == "__main__":
    logging.basicConfig(level=logging.ERROR)
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    res = {True: 0, False: 0}
    for i in range(n):
        for lc in (False, True):
            if not run(i, lc):
                res[lc] += 1
    print("FAILURES clean=%d after-leftover=%d of %d each" % (res[False], res[True], n))
