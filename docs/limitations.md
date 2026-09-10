# Known limitations

- Gateway HTTP correlation is single-process and in-memory. A Gateway restart drops pending HTTP requests.
- Gateway terminal delivery currently uses a core NATS subscription and has no durable per-Gateway redelivery.
- There is currently a registration race between Invocation creation/READY publication and Gateway pending-response registration; a very fast Invocation may complete before its pending HTTP correlation is registered.
- Invocation state transition and JetStream terminal-event publication are not atomic. A terminal DB update may succeed while event publication fails.
- Runtime completion transport failure after ACCEPTED can currently leave a StepExecution RUNNING and runtime capacity reserved.
- Gateway Invocation persistence/lookups currently perform blocking JDBC work on Netty request/event-loop paths and should be moved to worker executors before production concurrency.
- An unsupported next component type currently stops progression while leaving the Invocation PENDING instead of failing it explicitly.
- FUNCTION and RESPONSE failures have no retry/backoff yet; the first execution failure is terminal.
