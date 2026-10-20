# What mqtt-kat promises

What the cluster promises a client, per QoS and per kind of topic, and
under which failures. This document decides what counts as a bug. A
lost or duplicated message that this document allows is not a bug; one
that it forbids is, however rare it is. The chaos checker
(`mqttkat.chaos.check`) and the simulation (`mqttkat.sim.*`) check these
rules and no others.

Each rule has a **target**, what we are building towards, and a
**today**, what the code currently does, as far as the runs have shown.
Where they differ, the gap is known work, not a surprise.

This is a proposal (20261020). The open decisions are marked **open**.

## Words

- **Kept session:** one that outlives its connection. In 3.1.1 that is
  Clean Session 0; in 5 it is a Session Expiry Interval above 0 that has
  not yet passed. Everything else is a **clean session**.
- **Accepted:** the broker has sent the PUBACK (QoS 1) or the PUBREC
  (QoS 2). From then on the message is the cluster's responsibility, not
  the publisher's (§4.3.2, §4.3.3). A message the publisher never had
  accepted is still the publisher's to send again, and nothing below is
  owed for it.
- **Owed:** a message is owed to a subscription when it is accepted while
  the subscription exists. Messages accepted around the SUBSCRIBE or
  UNSUBSCRIBE are a grey zone, as `mqttkat.chaos.check` already defines
  it (`:subscribe-settle`, `:clean-grace`).
- **Delivered:** the subscriber's client hands the message to the
  application. For QoS 2 that happens once per packet identifier: a
  PUBLISH sent again under an identifier the client still holds
  unreleased is the protocol retrying, not a second delivery.
- **Exactly-once topic:** one matched by `mqttkat.exactlyOnceTopics`.

## Failures

| failure | what it means here |
|---|---|
| client disconnect | a connection ends, cleanly or not, and the client connects again, to the same broker or another |
| broker crash | a broker process dies without warning. Its memory is gone, along with every connection and bridge it had. It may come back empty |
| Rama lag | appends land and are processed late, by seconds, but in order and eventually |
| bridge drop | a link between two brokers breaks while both stay up |
| overload | more arrives than can be delivered: subscribers are slow, or Rama is behind |

**Out of scope:**

- **Rama losing data it acknowledged:** Rama is the durable store, and
  we rely on it.
- **Every broker down at once for good:** nothing can be delivered then.
- **Clients that break the protocol:** for example, a publisher that
  forgets its QoS 2 identifiers on a kept-session reconnect.

## The rules

### R0: QoS 0

At most once. Not kept, and lost freely under any failure. Never
delivered twice.

- **Today:** holds.

### R1: QoS 1 to a kept session

At least once. Every accepted message is eventually delivered to every
kept session it is owed to.

- **Target:** this holds across client disconnects, Rama lag and
  overload, and also across a broker crash.
- **Open:** how much a broker crash may cost. One option is "nothing".
  The other is "at most what the crashed broker held in memory and had
  not yet handed to Rama: the crash window", provided the window is
  bounded and measured.
- **Today:** holds across client disconnects. A broker crash loses what
  was only in its memory: the in-flight window and pending queue of each
  subscriber connected there, and the bridge backlog it had not
  forwarded yet (broker-kill run 111704: 602 lost on non-exactly-once
  topics).

### R2: QoS 2 to a kept session, other topics

The same as R1, and in addition **exactly once while no broker crashes**
(across client disconnects, Rama lag and overload).

- **Target, if R1 is "nothing":** a broker crash may cause a duplicate
  but never a loss.
- **Today:** a crash both loses (as in R1) and duplicates.

This is the deliberate relaxation of §4.3.3. The specification asks for
exactly once whatever happens. We promise it across a crash only on the
exactly-once topics, because it costs a write to Rama for every delivery
(R3).

### R3: QoS 2 to a kept session, exactly-once topics

Exactly once, across every failure in the table: client disconnects,
broker crashes, Rama lag, bridge drops and overload. Never lost and
never delivered twice.

- **Today:**
  - Broker-kill run 111704 had no loss and no duplicate on these topics.
  - Load runs had duplicates: 35 from stale reads (fixed), and some from
    a publisher's resend that was given a second message key (open).
  - Load run 152413 lost 88,821, all left on queues nobody read
    (believed fixed, not yet re-run).

### R4: clean sessions

A clean session's state ends with its connection (§4.1). What was still
in flight to it when the connection ended may be lost, and so may what
was in flight when its broker crashed. While the connection lasts, R0 to
R2 apply as for a kept session, except that a broker crash is the end
of the connection.

- **Today:** holds.

### R5: liveness

Once failures stop and Rama has caught up, every message still owed is
delivered within **T** (proposed: 2 minutes), to subscribers that are
connected and reading.

This is what tells a loss from a backlog. A run that ends with messages
still queued and Rama idle has broken R5, which is a bug, not a capacity
limit. A run that ends with Rama still behind has not shown anything
either way, and has to drain longer.

- **Today:** load run 152413 broke it (queues left unread with Rama
  idle).

### R6: protocol

Nothing MQTT forbids, whatever the QoS:

- no PUBLISH before the CONNACK;
- no PUBREL for an identifier the client does not hold;
- no reuse of an identifier the client still holds for a different
  message (it drops that message as a repeat, which is a silent loss
  that R1 would count, but this rule names the cause).

### Not promised

- **Order across a reconnect or a broker crash.** Order per topic is kept
  only within one connection (§4.6), and the checker does not test it.
- **Delivery to a session whose expiry passed.**
- **Overload without back-pressure.**
  - A clean session behind its pending limit is refused messages and
    counted (`droppedMessages`).
  - A kept session overflows to Rama and is not refused.

## Assumptions the promises rest on

- Rama keeps what it acknowledged, processes each depot partition in
  order, and catches up eventually.
- At least one broker is up and reachable, and clients connect again
  after a drop.
- Publishers keep their in-flight QoS 1 and 2 messages across a
  reconnect of a kept session and send them again (§4.4): PUBLISH with
  DUP before the PUBREC, and PUBREL after it.
- Subscribers keep their QoS 2 identifiers until they get the PUBREL,
  across a reconnect of a kept session.

## Where each rule is checked

| rule | chaos run (`mqttkat.chaos.check`) | simulation (`mqttkat.sim.check`) |
|---|---|---|
| R0 | `:duplicate` at QoS 0 | not modelled (QoS 1 and 2 only) |
| R1 | `:lost` | `:lost` |
| R2 | `:lost`, `:duplicate` | `:lost`, `:duplicate` when no broker crashed |
| R3 | `:lost`, `:duplicate`, `:qos2-by-exactly-once` | `:lost`, `:duplicate` |
| R4 | `:clean-grace` | not modelled (kept sessions only) |
| R5 | the drain at the end, `:left-queued` | `:stuck`: owed, undelivered, and nothing left to happen |
| R6 | `:protocol` | `:identifier-reused` |
