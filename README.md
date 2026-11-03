# okay-reason

Four products on one model of a system:

1. **The architecture analyzer** — reads `docker-compose.yml`, Kubernetes manifests or a one-fact-a-line `arch.txt`;
   reports what each component's loss fails (with the chain), single points of failure, cycles of hard dependencies
   (Datalog, with a proof), unguarded dependencies, and the changes that help — each checked by simulating the loss
   before and after.
2. **The failure generator** — failure scenarios with gold answers: faults chosen in your system (or a random one),
   spread, rendered as logs in several formats (JSON lines, logfmt, plain with stack traces, access logs) with noise,
   a chat in English and Russian, partial observability. For testing an analyzer, an agent, a runbook.
3. **The failure analyzer** — from symptoms in logs and chat (read by the built-in reader, or stated by a model):
   the smallest sets of faults that explain every symptom and touch nothing seen healthy — all of them, counted; what
   each predicts; the check that tells them apart; why another fault is not the cause.
4. **The remedy generator** — actions for the faults found, each checked by simulating the faults left after it: the
   entry points it restores, what still fails, its risk.

The model does what it is good at — reading text, stating facts, leading an investigation; okay-reason does what a
model gets wrong — propagation through a graph, abduction, counting the explanations, checking that a fix fixes. Both
through an MCP server any agent can use (Claude Code, okay-agent, opencode).

```
sbt "run arch examples/docker-compose.yml"
sbt "run generate --out /tmp/scn --random 8 --seed 7"
sbt "run explain /tmp/scn"
sbt "run remedies --arch examples/shop.txt --faults 'db down'"
sbt "run bench --seeds 200 --faults 2"
sbt "run serve"                       # MCP on stdin/stdout
```

```
one explanation (k = 1):
  A. billing down — explains 3 of 3 symptoms
       payments retries [log:payments:4]: payments retries ← billing down
       web errors [log:web:7]: web errors ← billing down
remedies (each checked by simulation):
  1. restart billing — restores web (risk low)
```

Measured (`bench`, 200 random systems, 80% of components with logs): one fault — the cause found always, alone in
64%, among a few (with the check that tells them apart) in 36%; 2–3 ms a scenario. specs/reason.md §8.

Built on okay-datalog (rules with proofs), okay-mcp (the server) and okay-scala (effects). Apache-2.0.
