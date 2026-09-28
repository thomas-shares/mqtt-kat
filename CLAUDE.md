# mqtt-kat

MQTT 3.1.1 and 5 broker: Java NIO in `src/java`, Clojure handlers in `src`,
Rama for durable sessions. Leiningen, Java 21. Clojure stays at 1.12.4 because
Rama refuses any other version.

## Build and test

- `lein test` runs the unit tests (the `:default` selector leaves out the load
  simulations).
- `lein test :performance` runs the load simulations in `client-generator{,-2}`.
- `lein test :portable` runs only the `^:portable` tests. With
  `MQTT_BROKER_HOST` and `MQTT_BROKER_PORT` set, the suite starts no broker and
  runs them against that one instead.
- `lein test :mosquitto` is what CI runs against Mosquitto: the portable tests
  minus those tagged `:diverges-on-mosquitto`.
- `lein test :only mqttkat.some-test` runs one namespace.
- `lein uberjar`, then `java -jar target/mqtt-kat-0.0.1-standalone.jar 1883 8081`
  runs the broker.

Dependencies come from nexus.redplanetlabs.com (Rama), Clojars and Maven
Central. In Claude Code on the web, `.claude/hooks/session-start.sh` installs
lein and runs `lein deps` at session start.

CI (`.github/workflows/test.yml`) runs `lein test`, `lein test :mosquitto`
against eclipse-mosquitto, and the Paho MQTT 5 suite via `scripts/paho_v5.py`.

## Tagging tests `^:portable`

A test is `^:portable` when it reaches the broker only over its socket and
asserts only what the MQTT spec requires. A test that pins a choice the spec
leaves open (one copy versus a copy per subscription, 0x94 versus 0x82) is not
portable, and gets a comment saying so. A portable test that Mosquitto fails
where the spec does decide keeps `^:portable` and gains
`:diverges-on-mosquitto "<reason>"`. Decide this whenever you add a
broker-facing test.

## thoughts.md

`thoughts.md` holds the author's design notes, newest first, under `## YYYYMMDD`
headings. Add new entries at the top.
