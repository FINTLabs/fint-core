# Events

An event is a question from a client that an adapter answers. The client asks the client-api, the adapter picks the
question up from the adapter-gateway, answers it, and the client asks the client-api for the outcome.

Writes (create, update, delete, validate) have always been events. A live read is an event too,
see [live-read.md](live-read.md).

## Who is involved

| Part              | Role                                                                                                                                         |
|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Client            | A county system. Sends the request and polls for the outcome.                                                                                |
| client-api        | Client-facing service. Stores the event and serves the outcome.                                                                              |
| Events collection | One Mongo collection per org, named `<org>_events`. Both services read and write it.                                                         |
| adapter-gateway   | Adapter-facing service. Serves pending events to adapters and takes their answers.                                                           |
| Adapter           | The source system's connector. Polls for events it has a contract for and answers them.                                                      |
| Kafka             | A feed only. Write requests and answers are copied there for other systems, such as the status service. Nothing in the flow waits for Kafka. |

## A write, step by step

```mermaid
sequenceDiagram
    participant C as Client
    participant A as client-api
    participant M as Events collection
    participant G as adapter-gateway
    participant D as Adapter
    C ->> A: POST /utdanning/vurdering/elevfravar (body)
    A ->> M: save event (PENDING)
    A -->> C: 202 Accepted, Location: .../status/{corrId}

    loop until an event is served
        D ->> G: GET /provider/event/utdanning/vurdering
        G ->> M: pending events the contract covers
        G -->> D: [event]
    end

    D ->> G: POST /provider/event (answer)
    G ->> M: mark ANSWERED and store the resource
    G -->> D: 200 OK

    loop until the outcome is ready
        C ->> A: GET .../status/{corrId}
        A ->> M: read the event
        A -->> C: 202 while PENDING, then the outcome
    end
```

The gateway stores the resource and marks the event answered in one Mongo transaction. A validate never stores anything,
and neither does a create that failed.

## The life of an event

```mermaid
stateDiagram-v2
    [*] --> PENDING: client-api saves the request
    PENDING --> ANSWERED: adapter answers in time
    PENDING --> EXPIRED: 15 minutes pass without an answer
    ANSWERED --> [*]: purged 30 minutes after creation
    EXPIRED --> [*]: purged 30 minutes after creation
```

| Timing                                          | Value            | Where it is set                                                             |
|-------------------------------------------------|------------------|-----------------------------------------------------------------------------|
| Time an adapter has to answer                   | 15 minutes       | `fint.consumer.event.answer-deadline` (client-api)                          |
| Time the event document lives in Mongo          | 30 minutes       | `fint.consumer.event.retention` (client-api), enforced by a Mongo TTL index |
| How often overdue events are flipped to EXPIRED | every 30 seconds | `fint.provider.event.expiry-sweep-interval` (adapter-gateway)               |

An adapter is only served events that are still PENDING and inside their deadline. Once an event has been answered or
has expired, a late answer is refused.

## What the client sees on the status endpoint

`GET /{domain}/{package}/{resource}/status/{corrId}`

| Situation                                        | Answer                                                        |
|--------------------------------------------------|---------------------------------------------------------------|
| Still waiting for the adapter                    | 202 Accepted                                                  |
| Create or update answered                        | 201 Created, `Location` of the resource, the resource as body |
| Validate answered                                | 200 OK with the adapter's answer                              |
| Delete answered                                  | 204 No Content                                                |
| Adapter rejected the request                     | 400 Bad Request                                               |
| Adapter reported a conflict                      | 409 Conflict                                                  |
| Adapter reported a failure, or the event expired | 500 Internal Server Error                                     |
| Unknown `corrId`, or the event was purged        | 410 Gone                                                      |

## What an adapter may be served

An adapter registers a contract with `POST /provider/register`. The contract decides which events it is served:

- It must hold the role for the package (for example `FINT_Adapter_utdanning_vurdering`).
- It must have a contract for the org.
- The contract's `eventCapabilities` must cover the resource and the operation. A resource that is not listed there gets
  every operation except READ, which is how adapters that know nothing about live reads keep working.

An answer is checked the same way against the stored request, so an adapter cannot answer an event it would not have
been served.

## Waiting for events (long polling)

An adapter may ask the gateway to hold an empty poll open instead of answering at once, by sending
`Prefer: wait=<seconds>` (RFC 7240) on `GET /provider/event/...`.

| Situation                           | Answer                                                                        |
|-------------------------------------|-------------------------------------------------------------------------------|
| No `Prefer: wait`, or `wait=0`      | At once, as before                                                            |
| `wait` given and events are pending | At once, with the pending events                                              |
| `wait` given and nothing is pending | Held until an event arrives or the wait runs out, then the list (maybe empty) |
| `wait` above the maximum            | Held for the maximum instead                                                  |

When the gateway holds the poll it answers with `Preference-Applied: wait=<seconds it used>`. A gateway that ignores
the preference answers at once without that header, so an adapter that gets an empty list without it should pause
before it polls again. The body, the authorization and the filtering are the same as for a normal poll.

An event stays PENDING until it is answered, so a poll returns an event the adapter was already served for as long as
the answer has not landed. An adapter that long-polls should post its answers before it polls again, or it will be
answered at once with the same events.

The gateway is not told when client-api stores an event, so a held poll asks Mongo again every recheck interval. A held
poll is let go when the gateway shuts down.

| Timing                                     | Value       | Where it is set                                                    |
|--------------------------------------------|-------------|--------------------------------------------------------------------|
| Longest a poll is held                     | 90 seconds  | `fint.provider.event.long-poll.max-wait` (adapter-gateway)         |
| How often a held poll looks for new events | 0.5 seconds | `fint.provider.event.long-poll.recheck-interval` (adapter-gateway) |

The maximum stays well below the 120-second request limit of the access gateway in front of the adapter-gateway.
