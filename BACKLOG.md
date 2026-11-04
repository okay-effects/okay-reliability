# BACKLOG — okay-reliability

The operator's order (2026-11-04): everything below, in this order; each item spec first (specs/reliability.md), then
the code, its laws as tests, `bench` when the numbers can move, the page republished when it changes.

## 1. The model, truer

1. ~~**Zones and regions**~~ — DONE 2026-11-04 (spec §17).
   `db: database replicas 2 zones a,b`, `zone a fails every 365d for 2h`: a zone's events take
   down every instance in it at once; the β-factor stays for the rest. Availability of replicated systems is overstated
   until then.
2. ~~**Changes as causes**~~ — DONE 2026-11-04 (spec §18).
   `deployed(checkout) @14:02`, `changed(config, checkout)`: a fault that begins after a change
   of its component is far more probable (a prior by the change), and rolling it back is a remedy checked by the
   clock. The most frequent cause of incidents is missing from the model.
3. ~~**Capacity and load**~~ — DONE 2026-11-04 (spec §19).
   one replica of two lost: the other may not hold the load (`checkout capacity 60%`: the
   share one replica carries) → slow, not retries; retries multiply the load on a callee (a retry storm).

## 2. Inputs from real sources

4. ~~**The dependency graph from OpenTelemetry traces**~~ — DONE 2026-11-04 (spec §20).
   spans (OTLP JSON, Jaeger's JSON) to components and calls,
   their modes guessed (errors on a call, a cache's name, a messaging span) and said; merged with a compose or
   Kubernetes reading.
5. ~~**Logs of the usual stacks**~~ — DONE 2026-11-04 (spec §21; 19 lines in ReadCorpusSuite).
   the reader's patterns for Spring Boot, nginx, Postgres, Envoy/Istio, Node, Go, with a
   corpus of real lines and a test each; what it does not read, said.

## 3. The page

6. ~~**Large systems**~~ — DONE 2026-11-04 (spec §22).
   the picture zoomed and grouped by layer; a component pressed shows its blast radius.
7. **Kept and shared with no server** — the system in `localStorage`; a link with the system in its `#` fragment (never
   sent to a server), the incident's facts too.
8. **A report** — the architecture, the numbers, an incident's causes and remedies as Markdown, for a postmortem.
9. **Russian** — the page's words in English and Russian, chosen by the browser, switchable.
10. **On a phone** — okay-faces' mobile face, as okay-remote's.
11. **Smaller** — page.js is 2.9 MB: what is linked and why, and less of it.

## 4. Proof that it works

12. **The extraction at scale** — 50+ incidents, two faults and partial observation among them, two or three models
    (Qwen3.6-35B, a small one, Claude) — A / T / E with confidence intervals.
13. **Real incidents** — public postmortems (GitHub, Cloudflare, AWS, Google Cloud, Slack): their systems and symptoms
    written as arch.txt and facts, and the analyzer's answer against what the postmortem found.

## 5. The rest

14. okay-faces republished on Pages with the `Session` fix (an event heard as a step listens), okay-ui after it.
15. okay-reliability released as a library on Pages (Maven): `core`, `cli`.
16. CI (GitHub Actions) — needs a token with the workflow scope.

## Later, when a server is needed

- A Worker again (its code: commit 30767db): an alert's webhook (Alertmanager) diagnosed with no page open and told in
  Telegram or Slack; shared incidents in Durable Objects; MCP with sessions for a remote agent.
