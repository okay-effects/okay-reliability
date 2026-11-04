# okay-reliability — architecture, failures, diagnosis, remedies and the numbers of reliability, on one model (spec)

Status: built — the four products (§3–§6) and the probabilities (§9). Renamed from okay-reason 2026-11-04. The
operator's decision (2026-11-04): four products in one — an architecture analyzer, a failure generator for testing, a
failure analyzer, a remedy generator — on ONE model of a system and ONE simulator of how a failure spreads; then the
numbers of reliability on the same model. It grows out of okay-inlay `specs/reason.md` (exact reasoning as a tool the
model uses): a model does what it is good at (reading architecture text, logs, chat; stating facts; leading an
investigation), okay-reliability does what a model gets wrong (propagation through a graph, abduction, counting the
explanations, uniqueness, probabilities, checking that a fix fixes).

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

- A CLI: `okay-reliability arch|generate|explain|remedies|serve`.
- An MCP server (okay-mcp, as symdex): `load_system`, `assert`, `arch`, `simulate`, `explain`, `why_not`,
  `next_check`, `remedies`, `generate`, `reset` — compact text for a model, `format: json` for a program; under
  okay-guard every call seen and journaled.

## 8. Checked (laws, as tests)

- `Spread` is monotone; every effect's chain ends at a fault, each step a dependency of the system.
- For generated scenarios with every component observed: the gold faults are among the minimal explanations (the
  analyzer may find another set of the same size when the observations cannot tell them apart — then it says so).
- Every remedy ranked first restores the entry points it claims (re-spread).
- `Read` over the generator's logs gives the gold facts (`facts.txt`).
- `okay-reliability bench` (2026-11-04, 200 random systems of 6–11 services, 80% of the components with logs, read by
  `Read`): one fault — the gold the only explanation 64%, among several 36% (each with the check that tells them
  apart), never missed; two faults — the only one 24%, among several 47%, another as small or smaller 30% (a fault
  behind another leaves no trace of its own: Occam's answer, and the gold consistent with every fact in 100%);
  2–3 ms a scenario. Half the components observed, one fault: 49% / 51% / 0%.
- Round trip: `arch.txt` printed and read gives the same system.

## 9. Probabilities (2026-11-04, the operator: "okay-reliability, with a probabilistic model and metrics")

The same model and the same `Spread`, with rates. Steady-state reliability theory — fault trees by minimal cut sets,
the rare-event approximation, Fussell–Vesely importance, the β-factor model of common causes — checked by a
simulation over time.

**Rates** (`Rates`): a fault EVENT — one instance down, a component's errors, slowness, a full disk or queue, a
component down as a whole (a common cause: a bad deploy, a config) — happens every MTBF and lasts MTTR (both
exponential). In arch.txt: `db fails down every 180d for 1h`, `checkout fails errors every 30d for 30m`; units m, h,
d, y. Not written: a prior by the component's kind (§9.1). A component of several replicas: each instance down at the
kind's rate, and down as a whole at β = 0.1 of it. An event's unavailability q = MTTR / (MTBF + MTTR).

**An SLO** per entry point: `web slo 99.9%` (availability: neither down nor errors), `web latency slo 99%` (not slow).

### 9.1 Priors by kind (when no rate is written)

| kind | down | errors | slow | full |
|---|---|---|---|---|
| service (each instance) | 30d / 10m | 45d / 30m | 14d / 20m | — |
| database | 180d / 1h | 365d / 30m | 30d / 30m | 365d / 2h |
| cache | 90d / 10m | — | 60d / 10m | — |
| queue | 180d / 30m | — | 60d / 20m | 90d / 1h |
| balancer | 365d / 10m | — | 90d / 10m | — |
| external | 30d / 30m | 30d / 20m | 14d / 20m | — |

Rough, and said to be: numbers to replace with your own (§9.5).

### 9.2 Availability (`reliability`)

For each entry point and SLI (availability: down or errors; latency: slow): its **minimal cut sets** — the smallest
sets of fault events whose spread fails it (up to 3 events; how many were cut off said); its unavailability
U ≈ Σ over cuts Π q (the rare-event approximation, an upper bound); its frequency F ≈ Σ over cuts Σ e∈cut
(1/MTBF_e) Π f≠e q_f — incidents a year; the downtime a month (U · 730 h); the SLO met or not, the error budget left.
**Importance** (Fussell–Vesely): each event's share of U — where the downtime comes from, in minutes a month.

**Checked by simulation** (`simulate --years N`): every event up and down over N years (seeded), the entry points'
state by `Spread` at each change; the measured unavailability within the analytic one's confidence — the law.

### 9.3 Probable causes (`explain`)

The minimal explanations stay (exact). Beside them, **posteriors**: each set S of up to 3 events among the candidates
(those that explain a symptom — now even when they contradict a report: people and readers err), its prior Π q_e,
its likelihood Π over the observations (1 − ε) where `Spread(S)` agrees and ε where it does not — ε 0.02 for a log's
fact, 0.1 for a person's word (a source `chat:`), 0.05 otherwise; normalized over the sets enumerated. The answer:
"A redis down 0.81, B payments slow 0.15 — check inventory". The law: over generated scenarios the gold is ranked
first at least as often as the exact search finds it alone.

### 9.4 Remedies with numbers

- An incident's action has a time (restart 5m, roll back 15m, fail over 10m, restore 2h, scale out 10m, a breaker 15m,
  replace an instance 10m): ranked by the entry points restored, then the time, then the risk.
- A change of the architecture is priced in **downtime a month saved** (U before − U after, §9.2) per entry point and
  SLI; ranked by it.

### 9.5 From your history (`estimate`)

Incidents in a CSV (`start,end,component,kind[,instance]`, ISO times): each event's MTBF and MTTR re-estimated — a
Gamma prior (the kind's rate, worth one failure) updated by the failures seen in the window; MTTR the mean of the
durations with the prior as one more. Written out as arch.txt lines, to paste.

### 9.6 A span of life (`generate --timeline`)

Every event over a span (seeded): `incidents.csv` (the truth), `sli.csv` (each hour, each entry point: the minutes
unavailable, the minutes slow) — for testing alerts and SLO burn. The law: `estimate` over a long generated history
recovers the rates written.

### 9.7 Checked (2026-11-04)

- The shop (examples/shop.txt, the priors): web availability computed 99.8509%, measured over 2000 simulated
  years 99.8504% ± 0.0026; 22.8 incidents a year computed, 22.9 measured; latency 99.7322% vs 99.7328% ± 0.0021.
  The analytic report in 86 ms, the 2000 years in 0.4 s. A test holds it within 4 standard errors + 5%.
- `estimate` over 200 generated years recovers db down, checkout errors and checkout down within 25% (a test).
- `bench`, faults drawn by their rates, 80% of components with logs: one fault — the gold first by probability 90%
  (the exact search finds it alone in 70%), in the top 3 in 99%; two faults — first 24%, top 3 55% (a fault behind
  another leaves no trace: no probability can find it). ~35 ms a scenario.
- Optimistic until §10, fixed there: a read cache and a circuit breaker hid a dependency's loss (reads served
  stale, the feature degraded); writes through them still fail in life, and the model does not tell reads from
  writes yet.

## 10. Reads and writes, breakers (2026-11-04, fixing §9.7's optimism)

A request is a read or a write, and they travel differently. `Spread` runs two worlds over the same faults:

- **reads**: every dependency used for reads (`use` both or reads), by its mode;
- **writes**: every dependency used for writes (`use` both or writes); a `cached` one is `hard` here — a cache serves
  no write — unless its callee is a cache component itself (a cache's invalidation is best effort: `soft`).

A component's effects are both worlds' (a log shows both). An entry point's availability is request-weighted:
U = (1 − w) · U_reads + w · U_writes, w its share of writes (`web writes 20%`; default 10%); reported with the two
apart. In arch.txt: `checkout -> db cached` (reads through a cache, writes direct), `web -> recs soft reads` (a
dependency for reads only), `api -> audit soft writes`.

**A circuit breaker** (`checkout -> payments hard breaker`) fails fast instead of waiting: through it a callee's
`slow` or `backlog` becomes the caller's `errors`, not `slow` — it protects the latency SLI and costs availability. It
does not make a dependency optional. **Degrading** (the mode `soft`) does, and is proposed as its own change, for
reads only, with the condition said: "if the feature can be left out". Prevention prices each change by its net
downtime (latency saved minus availability lost).

## 11. Time in the diagnosis

A symptom may carry when it was seen: `symptom(checkout, errors) [log:checkout:4] @2026-11-04T14:01:12Z`; `Read`
takes the time from each line (JSON `ts`, logfmt `ts=`, a plain line's first word, an access log's `[…]`). An
explanation's chains must run with the clock: where its spread makes a caller's observed effect come through a
callee's observed effect, the caller's time is not before the callee's by more than the tolerance (10 s; clocks drift).
An exact explanation that runs against the clock is dropped and said ("against the clock: …"); in the posterior each
such pair weighs ε = 0.05. Each explanation's onset: its earliest explained symptom. The generator spaces the depths
15 s apart (jitter 5 s).

## 12. Metrics (Prometheus)

- **A snapshot** in the text exposition format, or **a server** (`url`, the HTTP API's instant queries): per job (or
  `service` label; a job named as a component, or `job` mapped by `component=` in the call) — `up` 0 → `down`; the
  ratio of 5xx over all requests above 5% → `errors`; the p95 latency above 1 s → `slow`; a queue's depth growing
  (`queue_depth` over 10 000) → `backlog`; up and under all thresholds → `healthy`. Each fact's source
  `metrics:<series>`. The queries are the usual ones (`http_requests_total{code}`,
  `http_request_duration_seconds_bucket`), each replaceable in the call.
- **History**: `up` over a range (`query_range`) → the intervals each job was down → incidents → `estimate` (§9.5).
- The generator writes `metrics.prom` for each scenario (every component scraped, a down one too: `up 0`); the law:
  `Metrics.read` of it gives the gold's metric facts.

### 10–12 checked (2026-11-04)

- Reads and writes: `web -> db cached`, db down → reads stale, writes errors; the unavailability exactly
  (1 − w)·U_reads + w·U_writes (a test). The shop's read cache for db now saves 47.7 min a month, not 53: its
  writes (10%) still fail. A breaker on a hard dependency trades latency for availability — for the shop its net is
  negative, and prevention no longer proposes it there.
- Time: a cascade whose caller failed a minute before its callee is set aside ("against the clock"), two independent
  faults found instead (a test); in order, the cascade is the one explanation again, its onset said.
- Metrics: a scenario's scrape read back agrees with the gold in 30 of 30 (a test); a server's instant queries and
  `up`'s range read through `Http` (a test with a server in memory). `bench` (200 scenarios, faults by their rates,
  80% of components with logs):

| | 1 fault, logs | 1 fault, logs + metrics | 2 faults, logs | 2 faults, logs + metrics |
|---|---|---|---|---|
| the gold the only explanation | 70% | 82% | 33% | 46% |
| among several | 31% | 18% | 48% | 40% |
| another, as small or smaller | 0% | 0% | 19% | 14% |
| the gold first by probability | 90% | 95% | 27% | 36% |
| in the top 3 | 99% | 100% | 65% | 80% |
| ms a scenario | 90 | 100 | 212 | 131 |

After a returned status states its callee alive (§13's miss, the same day): one fault 73% / 86% the only explanation,
first by probability 93% / 98%; two faults 35% / 49%, first 29% / 38%.

## 13. A model's extraction, measured (A / T / E)

On generated scenarios (the logs, the chat; the system's arch.txt), a local model (Qwen3.6-35B through okay-mlx) in
okay-agent, asked "what failed, and why":

| arm | what the agent has |
|---|---|
| A | the files alone |
| T | the files and okay-reliability's MCP tools (load_system, read_logs, assert, explain, why_not) |
| E | the gold facts and the analyzer (`bench`: the ceiling) |

Scored by the answer's root cause set against the gold (exact; the components named), with the time and the turns.
The runs journaled by okay-agent; the table in this section with the date, the model, the seeds.

Measured 2026-11-04: Qwen3.6-35B-A3B (4-bit, okay-mlx) in okay-agent 0.1.0, seeds 101–108, one fault each (drawn by
its rate), 80% of the components with logs, metrics not given; scored on the last `ROOT CAUSE:` line:

| arm | runs | the cause exactly (component and kind) | the component right | median time |
|---|---|---|---|---|
| A, the files alone | 8 | 2 | 6 | 215 s |
| T, with okay-reliability's tools | 8 | 7 | 8 | 316 s |
| E, the gold facts and the analyzer (`bench`) | 200 | 64–70% alone, 100% among the explanations | — | 0.1 s |

Alone the model names the right component most of the time but the wrong kind (down for slow), or adds a second
cause, or none (seed 108: no answer line). With the tools it named the exact cause in 7 of 8; the one miss (seed 102,
`pricing-db errors` answered `down`) was the analyzer's: it left down, errors and full tied, a symptom `errors` being
shown by a component down too — though the line said `status 503 returned`, and a component that answers is alive.
Fixed the same day: a status returned now states the callee `alive` (the reader and the generator's gold alike), and
down is out. The tools cost a turn or two: 316 s against
215 s. Eight runs: a direction, not a rate.

## 14. Large systems (2026-11-04)

Enumerating every set of up to 3 events is C(n, 3) spreads — fine for tens of components, hopeless for hundreds.
Two standard searches instead, each checked against the enumeration on small systems (the law):

- **Diagnosis as minimal hitting sets**: each symptom's explainers (the candidates whose spread alone shows it);
  branch on the uncovered symptom with the fewest, add one of them, to a depth k — O(d^k) in the explainers d of a
  symptom, not in n. Each set found checked by `Spread` together (a fallback can fail jointly); all at the smallest
  k, deduplicated, counted.
- **Cut sets by a fault tree (MOCUS)**: the rules of `Spread` as a monotone formula over the events, per world —
  a component down or in errors if its own event is on, all its instances are down, or through a hard dependency
  whose callee is down or in errors (slow too, behind a breaker) and whose every fallback is bad; slow likewise —
  expanded into its minimal cut sets, truncated at k events; a cycle met on the way counts as not failing (the least
  fixpoint, as `Spread`). The probabilities as before.

`bench --large N`: random systems of N components, the time of each search.

Checked (2026-11-04): the fault tree's cut sets equal the enumeration's on 14 systems, every entry point, SLI and
world; the hitting-set search finds the enumeration's explanations in 40 scenarios (tests). `bench --large`, two
faults a scenario, medians of 3 systems:

| components | fault events | explain | probable causes | every entry's numbers | gold among the explanations |
|---|---|---|---|---|---|
| 25 | 92 | 0.00 s | 0.23 s | 0.01 s | 3/3 |
| 100 | 353 | 0.03 s | 1.5 s | 0.01 s | 3/3 |
| 200 | 705 | 0.11 s | 2.1 s | 0.02 s | 3/3 |
| 500 | 1782 | 0.81 s | 8.6 s | 0.07 s | 3/3 |

The enumeration of cut sets at 500 components would be C(1782, 3) ≈ 9·10⁸ spreads for each predicate. The probable
causes are now the slowest part: they still enumerate sets of the 40 likeliest events (9 880 spreads).

## 16. The front (2026-11-04)

An okay-ui program (`ui`, JVM and JS) over the core: six places — the system (paste or load arch.txt, a compose file,
Kubernetes manifests; examples; a random system), its architecture (a picture: components in columns by their
distance from the entry points, single points of failure red, unguarded dependencies amber, bands by mode; the
components' blast radius, the changes that help priced in minutes a month), its numbers (availability and latency per
entry point, SLOs and budgets, where the downtime comes from; simulated 1000 years on a press), what if (faults chosen,
spread on the picture, their chains, the remedies), an incident (logs, a chat, metrics, facts read; the causes, their
probabilities, the check to make as buttons — "inventory is fine" — the remedies) and training (an incident made, read
in, the answer revealed). Tested on the JVM by okay-ui's `Play` (`AppSuite`).

- **The page** (`web`): the program on okay-ui's DOM face — everything computed in the browser, nothing sent; a
  static site, on GitHub Pages (https://okay-effects.github.io/okay-reliability/, RELEASING.md).
- `scripts/page.e2e.js`: headless Chrome through the places against a URL (`URL=…`) — all checks pass.
- A Cloudflare Worker (the page, `/api/<tool>`, MCP over HTTP, nothing kept) was built and deployed on 2026-11-04 and
  taken down the same day (the operator: a static site, Workers later where one is needed — an alert's webhook
  diagnosed with no page open, shared incidents, a remote agent's MCP with sessions). Its code is in the history
  (commit 30767db, `worker/`).

## 17. Zones (BACKLOG 1, 2026-11-04)

Replicas fail together when the place they run in fails: a zone (a data centre, an availability zone), a region.
Independent failures and the β-factor overstate a replicated system's availability while its replicas share a zone.

- **Where instances run**: `db: database replicas 2 zones a,b` — instance i in zone i mod n (instance 1 in `a`,
  instance 2 in `b`); a component of one replica in a zone: `cache: cache zones a`. No zones: none shared.
- **A zone's failure** is a fault event of its own, `zone:a down`: every instance in the zone down at once (a
  component of one replica there, down). Its rate: `zone a fails every 365d for 2h`, else the prior 365d / 1h.
- `Spread` takes a zone's fault as the instances' faults it means; the fault tree's `down` of an instance is its own
  event or its zone's; the diagnosis has the zones among its candidates; `Life` simulates them.
- `arch`: each zone's blast radius ("losing zone a fails web"); prevention proposes spreading a component's replicas
  over two zones where they share one, priced by `Reliability` as every change is.
- Laws: the fault tree equals the enumeration with zones; `Life` agrees with the cut sets; a system with its two
  replicas in one zone is less available than the same with them in two.

## 18. Changes as causes (BACKLOG 2, 2026-11-04)

Most incidents follow a change: a deploy, a configuration. A fault that begins soon after its component changed is
far likelier than its rate alone says; rolling the change back is then the first remedy.

- **The fact**: `changed(checkout, deploy) [log:checkout:3] @2026-11-04T14:02:00Z` (or `config`). `Read` takes it
  from the usual lines (`deployed version 1.4.2`, `Started … in … seconds`, `configuration reloaded`, Kubernetes'
  `Scaled up replica set`, `rollout`); the generator writes it — for a fault it caused, and as a decoy elsewhere
  (a change that broke nothing).
- **The prior** (§9.3): a fault of a component that changed within the hour before the incident's onset — its errors
  or slowness ×30, its being down ×5 (a deploy that breaks a service breaks it this way far more often than it
  stops it); a change after the onset weighs nothing.
- **The exact search** keeps its explanations, ordered: those whose faults follow a change first, said ("after
  checkout's deploy at 14:02").
- **Remedies**: for a component that changed, "roll back checkout's deploy (14:02)" — 10 minutes, low risk — first.
- **Checked**: `bench --changes`: half the faults caused by a change, decoy changes elsewhere — the gold first by
  probability with the changes read against without them.
- Measured 2026-11-04 (`bench --changes true`, 200 scenarios, deploys before 60% of the errors and slowness, a decoy
  in half): one fault — the gold first 92% with the changes read and 92% without (a lone fault is found anyway); two
  faults — 44% against 29%: the change makes the second fault, hidden behind the first, the likelier story.

## 19. Capacity and load (BACKLOG 3, 2026-11-04)

An instance lost is not only a retry: the ones left may not carry the load. And a caller that retries a failing
callee multiplies the load on it.

- **Capacity**: `checkout: service replicas 3 needs 2` — the load wants 2 instances up; fewer up (but some) → the
  component is `slow` (overloaded); none → `down`. `needs 1` (the default): any one carries it, a lost instance is
  `retries` as before.
- **A retry storm**: `web -> api hard retries` — the caller retries; a callee showing `errors` (and up) is then also
  `slow`, overloaded by the retries. A breaker on the dependency (`breaker`) stops the storm.
- In the fault tree: `slow` of a component that needs k of n instances is every set of n − k + 1 of its instances
  down (an instance its own event or its zone's); a stormed callee's errors are its slowness too.
- Prevention: for a component whose overload fails an entry point, `needs` met with one more replica; for a stormed
  dependency, a breaker — each priced as every change is.
- Laws: the fault tree equals the enumeration with capacities and storms; `Life` agrees; one instance of three lost
  with `needs 3` slows the component, with `needs 2` it does not.

## 20. The architecture from traces (BACKLOG 4, 2026-11-04)

What calls what is in the traces already: every span knows its service and its parent. `Traces.read` takes OTLP JSON
(an export of `resourceSpans`) or Jaeger's JSON (`data[].spans`, `processes`) and gives a system, each guess said:

- **Components**: a service per `service.name` (Jaeger: the process's `serviceName`); its replicas the distinct
  `service.instance.id` (or `host.name`) seen; an entry point when its server spans begin traces (no parent).
- **Uninstrumented callees**, from a client span's attributes: `db.system` (+ `db.name` or `peer.service`) →
  a database (`redis`, `memcached` → a cache), `messaging.system` (+ the destination) → a queue, `peer.service` or
  the host of `server.address` / `http.url` not a service seen → an external.
- **Calls and their modes**: a span whose parent is another service's → the parent's service calls it; a cache →
  `cached`, a producer span or a queue → `async`; else `hard` — unless the traces show it soft: when the callee's
  span failed, the caller's did not, in at least 80% of at least 3 such traces.
- **Merged** with a system read otherwise (`load_traces` with `merge`): components and calls added, a written
  component kept as written.
- The page reads a trace file on System like any other; the tool `load_traces`.
- The law: a system's traces as `Traces.render` writes them (OTLP, errors sampled) read back to the same components,
  calls and modes — hard, soft, cached, async; Jaeger's form too.

## 15. Not yet

Workers where a server is needed (an alert's webhook diagnosed and told; shared incidents; MCP with sessions for a
remote agent); the front on a phone (okay-faces' mobile
face, as okay-remote's); 

okay-inlay's handler (the exact part as transformer weights).
