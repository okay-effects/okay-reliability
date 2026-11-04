# okay-reliability

**Live:** https://okay-effects.github.io/okay-reliability/ — the page, everything computed in your browser: nothing you
paste leaves it. For agents: `okay-reliability serve`, an MCP server on your machine.

The reliability of a system, on one model of it and one simulator of how a failure spreads:

1. **The architecture analyzer** — reads `docker-compose.yml`, Kubernetes manifests or a one-fact-a-line `arch.txt`;
   what each component's loss fails (with the chain), single points of failure, cycles of hard dependencies
   (Datalog, with a proof), unguarded dependencies.
2. **The numbers** — for each entry point its availability and latency from the components' failure rates (written,
   or rough priors by kind): downtime a month, incidents a year, the SLO and its error budget, where the downtime comes
   from — by minimal cut sets, checked by simulating thousands of years of failures and repairs. Rates re-estimated
   from your incident history (Bayesian).
3. **The failure generator** — scenarios with gold answers: faults in your system (or a random one), spread, rendered
   as logs in several formats with noise and a chat (en/ru); or a span of life: incidents and hourly SLIs.
4. **The failure analyzer** — from symptoms in logs and chat (read by the built-in reader, or stated by a model): the
   smallest sets of faults that explain everything, counted; their probabilities; the check that settles the most;
   why another fault is not the cause.
5. **The remedies** — for an incident, actions each checked by simulating the faults left after it (what it restores,
   how long it takes, its risk); for the architecture, changes priced in the downtime a month they save.

A front: the same program as screens (okay-ui), in a browser — everything computed in the page (`sbt pageDist`, on
GitHub Pages: RELEASING.md).

The model does what it is good at — reading text, stating facts, leading an investigation; okay-reliability does what
a model gets wrong — propagation through a graph, abduction, counting, probabilities, checking that a fix fixes. All of
it through an MCP server any agent can use (Claude Code, okay-agent, opencode).

```
sbt "run reliability examples/shop.txt --years 2000"
sbt "run arch examples/docker-compose.yml"
sbt "run generate --out /tmp/scn --random 9 --seed 21"
sbt "run explain /tmp/scn"
sbt "run generate --out /tmp/life --arch examples/shop.txt --timeline 365d"
sbt "run estimate --arch examples/shop.txt --incidents /tmp/life/incidents.csv --window 365d"
sbt "run bench --seeds 200"
sbt "run serve"                       # MCP on stdin/stdout
```

```
web availability: 99.8509%  — 65.3 min a month, 22.8 incidents a year; SLO 99.90%: MISSED (budget 43.8 min, 21.5 over)
  where the downtime comes from:
    web errors                      20.3 min a month  (31%)
    db down                         10.1 min a month  (16%)
simulated 2000 years:
  web availability: 99.8504% ± 0.0026 (computed 99.8509%), 22.9 incidents a year (computed 22.8)
changes, by the downtime they save:
  run db with 2 replicas — saves 9.1 min of downtime a month

probable causes (of 2951 sets of up to 3 events):
  1. checkout down                            0.540
  2. shipping errors                          0.220
check checkout next (it settles 1.46 bits): down 0.54, fine 0.23, errors 0.22
  1. restart checkout — restores web (~5 min, risk low)
```

Measured (`bench`, 200 random systems, faults drawn by their rates, 80% of components with logs): one fault — the
cause first by probability in 90%, in the top 3 in 99%. specs/reliability.md §8, §9.7.

On real incidents (`okay-reliability incidents incidents`: 21 public postmortems — Cloudflare, AWS, Google,
GitHub, GitLab, Slack, Facebook, Datadog, Stripe, Roblox, Fastly — each the system, what its operators saw before
they knew, and what the postmortem found): the cause first in 13, in the top three in 18; the misses and why in
specs/reliability.md §28, §34.

As a library: `io.github.okay-effects::okay-reliability:0.1.0` (JVM, Scala.js, Scala Native) and
`okay-reliability-cli`, from https://okay-effects.github.io/okay-reliability/maven (RELEASING.md).

Built on okay-datalog (rules with proofs), okay-mcp (the server) and okay-scala (effects). Apache-2.0.
