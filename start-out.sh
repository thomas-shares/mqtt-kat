set -x

pkill -f "mqtt-kat[-]0.0.1-standalone" ;
lein clean;
lein uberjar && java -Dmqttkat.sysInterval=30 -Dmqttkat.rama=external  -jar target/mqtt-kat-0.0.1-standalone.jar  1883 8081


