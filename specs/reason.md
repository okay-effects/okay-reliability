# okay-reason — architecture, failures, diagnosis, remedies: four products on one model (spec, 2026-11-04)

Status: v0 being built. The operator's decision (2026-11-04): four products in one — an architecture analyzer, a
failure generator for testing, a failure analyzer, a remedy generator — on ONE model of a system and ONE simulator of
how a failure spreads. It grows out of okay-inlay `specs/reason.md` (exact reasoning as a tool the model uses): a model
does what it is good at (reading architecture text, logs, chat; stating facts; leading an investigation), okay-reason
does what a model gets wrong (propagation through a graph, abduction, counting the explanations, uniqueness, checking
that a fix fixes).

## 1. One model

**A system** (`System`): components and dependencies.

- `Component(name, kind, replicas, entry)` — kind: `service`, `database`, `cache`, `queue`, `balancer`, `external`;
  `replicas` its instances (1 = one); `entry` whether users reach it directly (a web front, an API gateway).
- `Dependency(from, to, mode)` — `hard` (the caller fails with it), `soft` (a feature degrades: warnings), `cached`
  (the caller serves stale data while it is unreachable), `async` (through a queue: delays, no errors).
- `Fallback(from, to, alternative)` — when `to` is bad, `from` uses `alternative` (warnings, not errors), unless the
  alternative is bad too.

**A fault** (`Fault(component, kind, instance?)`): `down`, `slow`, `errors`, `full` (a disk, a queue), or one instance
down (`instance` set).

**An effect** (`Effect(component, kind)`): what a component shows — `down` (unreachable), `errors`, `slow`
(timeouts), `stale` (old data), `retries` (warnings: a fallback used, a soft dependency or a replica lost), `backlog`
(a queue growing). Each effect carries its cause: the fault, or the dependency and the effect it came through — a
proof, read back as a chain.

## 2. The simulator (`Spread`)

Given a system and a set of faults, every component's effects, to a fixpoint: a fault's own effect (`full` on a
queue → `backlog` and its producers `slow`; one instance of several down → `retries`; all instances → `down`); then
along dependencies, callee → caller:

| callee | hard | soft | cached | async |
|---|---|---|---|---|
| down | errors | retries | stale | backlog |
| errors | errors | retries | stale | — |
| slow | slow | retries | — | backlog |

A fallback whose alternative is not bad turns the caller's `errors`/`slow` from that callee into `retries`. Monotone in
the faults (more faults, never fewer effects), so the fixpoint is the least one and a diagnosis may prune by it.
Deterministic: the generator, the analyzer and the remedies run the same `Spread`.

## 3. Product 1 — the architecture analyzer (`arch`)

Reads a system from: our text format (`arch.txt`, below), `docker-compose.yml` (services, `depends_on`, `deploy.
replicas`, URLs in the environment naming another service: `postgres://db:5432` → a hard dependency on `db`),
Kubernetes manifests (Deployments' replicas, Services, environment naming a Service), or facts a model states (MCP
`assert`). Reports, each with its proof:

- **blast radius**: for each component, the entry points that fail (errors/down) when it is down — by `Spread`;
- **single points of failure**: a component of one replica whose loss fails an entry point;
- **cycles** of hard dependencies (Datalog `reach(X, X)` over okay-datalog, with its proof);
- **unguarded paths**: an entry point's hard path to an `external` or a single replica with no fallback, no cache;
- a score: the share of (component, entry) pairs that survive the component's loss.

```
# arch.txt: one line a fact
web: service entry replicas 2
checkout: service replicas 2
payments: external
redis: cache
db: database
web -> checkout hard
checkout -> payments hard fallback payments-backup
checkout -> redis cached
checkout -> db hard
```

## 4. Product 2 — the failure generator (`generate`)

From a system (read, or generated: `--random N` components, seeded), scenarios with gold answers, for testing the
analyzer and any agent (the benchmark of okay-inlay §6):

- 1–3 faults chosen (seeded), spread, rendered as **observations**: logs per component in several formats (JSON lines,
  logfmt, plain with a Java stack trace, an access log), timestamps by depth with jitter, noise lines, a share of
  components unobserved (partial observability), healthy notes in a chat (ru/en);
- the **gold**: the faults, the effects, the facts an ideal reader would extract (`facts.txt`) — arm E;
- written to a directory: `arch.txt`, `logs/<component>.log`, `chat.txt`, `facts.txt`, `gold.json`.

## 5. Product 3 — the failure analyzer (`explain`)

Input: a system, and **observations** — `symptom(component, kind, source)`, `healthy(component, source)` — stated by
a model (MCP `assert`) or read by `Read` (a deterministic reader of common log lines: connection refused, timeouts,
5xx, stale cache, retries, queue full; in any of the generator's formats and the usual real ones).

- **Candidates**: every fault of every component (each kind, each instance) — kept when alone it explains at least one
  symptom and contradicts no healthy observation (monotone: a contradiction alone is one in every superset).
- **Minimal explanations**: the smallest sets (k = 1, 2, 3) whose spread covers every symptom and contradicts no
  healthy observation; all of them at the smallest k, counted (unique or several).
- **Next check**: the unobserved component whose predicted state best splits the remaining explanations (the most
  even split), with what each explanation predicts there.
- **Why not X**: the healthy observation X contradicts (with the chain), or the symptoms X cannot explain.
- Every claim cites its sources (`log:checkout:17`, `chat:5`).

## 6. Product 4 — the remedy generator (`remedies`)

For a diagnosis (or given faults), **actions** — each with a precondition, what it changes in the model, its risk:

| fault | actions |
|---|---|
| a service down / errors | restart; roll back (if a deploy was observed); scale out (a stateless service) |
| one instance down | replace the instance (no user impact while the others serve) |
| a database down | fail over to a replica (replicas > 1); restore |
| slow | scale out; shed load (a balancer); for a database: the slow queries |
| full | free space; scale the consumers (a queue) |
| an external down | switch to its fallback, if there is one; a circuit breaker meanwhile (the dependency soft) |

Every action is **checked by the simulator**: the faults after it, spread again, and the entry points it restores —
the remedies ranked by restored entry points, then risk. And **prevention** from the architecture: for each single
point of failure and unguarded path, a change of the system (a replica, a fallback, a cache, a circuit breaker) with
the blast radius before and after, by `Spread` — a fix is proposed only when the model shows it helps.

## 7. Surfaces

- A CLI: `okay-reason arch|generate|explain|remedies|serve`.
- An MCP server (okay-mcp, as symdex): `load_system`, `assert`, `arch`, `simulate`, `explain`, `why_not`,
  `next_check`, `remedies`, `generate`, `reset` — compact text for a model, `format: json` for a program; under
  okay-guard every call seen and journaled.

## 8. Checked (laws, as tests)

- `Spread` is monotone; every effect's chain ends at a fault, each step a dependency of the system.
- For generated scenarios with every component observed: the gold faults are among the minimal explanations (the
  analyzer may find another set of the same size when the observations cannot tell them apart — then it says so).
- Every remedy ranked first restores the entry points it claims (re-spread).
- `Read` over the generator's logs gives the gold facts (`facts.txt`).
- `okay-reason bench` (2026-11-04, 200 random systems of 6â11 services, 80% of the components with logs, read by
  `Read`): one fault â the gold the only explanation 64%, among several 36% (each with the check that tells them
  apart), never missed; two faults â the only one 24%, among several 47%, another as small or smaller 30% (a fault
  behind another leaves no trace of its own: Occam's answer, and the gold consistent with every fact in 100%);
  2â3 ms a scenario. Half the components observed, one fault: 49% / 51% / 0%.
- Round trip: `arch.txt` printed and read gives the same system.

## 9. Not yet

Timing in the reasoning (a dependent failing before its cause), probabilities and confidence of facts, a SAT
formulation for large systems (v0 enumerates hitting sets), okay-inlay's handler, a model's extraction measured
(the A / T / E benchmark on okay-agent + Qwen).
