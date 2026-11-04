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
7. ~~**Kept and shared with no server**~~ — DONE 2026-11-04 (spec §23).
   the system in `localStorage`; a link with the system in its `#` fragment (never
   sent to a server), the incident's facts too.
8. ~~**A report**~~ — DONE 2026-11-04 (spec §24).
   the architecture, the numbers, an incident's causes and remedies as Markdown, for a postmortem.
9. ~~**Russian**~~ — DONE 2026-11-04 (spec §25).
   the page's words in English and Russian, chosen by the browser, switchable.
10. ~~**On a phone**~~ — DONE 2026-11-04 (spec §26). okay-faces' mobile face, as okay-remote's.
11. ~~**Smaller**~~ — DONE 2026-11-04 (spec §27). page.js is 2.9 MB: what is linked and why, and less of it.

## 4. Proof that it works

12. **The extraction at scale** — DEFERRED 2026-11-04 (the owner decides how to run the models).
    50+ incidents, two faults and partial observation among them, two or three models
    (Qwen3.6-35B, a small one, Claude) — A / T / E with confidence intervals.
13. ~~**Real incidents**~~ — DONE 2026-11-04 (spec §28: 8 postmortems, the truth first in 5, top three in 8).
    Public postmortems (GitHub, Cloudflare, AWS, Google Cloud, Slack): their systems and symptoms
    written as arch.txt and facts, and the analyzer's answer against what the postmortem found.

## 5. The rest

14. ~~okay-faces republished~~ — DONE 2026-11-04 (okay-faces f62577f: JVM, JS, Native, Android; okay-ui with Native;
    okay-reliability green from Pages alone, the page republished).
    okay-faces republished on Pages with the `Session` fix (an event heard as a step listens), okay-ui after it.
15. ~~okay-reliability released as a library~~ — DONE 2026-11-04 (spec §29; checked from a scala-cli script).
    okay-reliability released as a library on Pages (Maven): `core`, `cli`.
16. CI (GitHub Actions) — needs a token with the workflow scope.

## 6. What the real incidents taught (spec §28)

17. ~~**A look outweighs a change**~~ — DONE 2026-11-04 (spec §30; GitHub third → second).
    a person's direct check that a component is healthy should weigh more than the
    ×30 a change gives its faults (GitHub 2018: the link changed, then was seen healthy, and still ranks first).
18. ~~**Facts of a world**~~ — DONE 2026-11-04 (spec §31; S3 second → first).
    `symptom(s3, errors, reads)` — what failed for reads, for writes (S3 2017: the write path
    explains as well as the index when "GET failed too" cannot be said).
19. ~~**Reactions**~~ — DONE 2026-11-04 (spec §32: `withdraws`, `saturates`; Slack, Facebook agree with every fact).
    a component that turns a callee's failure into its own `down` (DNS withdrawing its routes,
    Facebook 2021); a caller saturated by a slow callee fails rather than slows (Slack 2021).

## 7. After the real incidents (the owner, 2026-11-04: "the rest — do it"; the phone app later)

20. ~~**The new words on the page**~~ — DONE 2026-11-04 (spec §33).
    `reads`/`writes` in a symptom, `withdraws`/`saturates` on a dependency — said in
    the fields' hints and shown by an example on the Examples page.
21. ~~**More postmortems**~~ — DONE 2026-11-04 (spec §34: 21 in all, first 13, top three 18).
    10–15 more public ones beside the eight (spec §28), the same rules: facts only from before
    the cause was known; the table re-measured.
22. ~~**The kind after a change**~~ — CLOSED 2026-11-04, refuted (spec §35: equal boosts, first 13 → 12;
    the kind is told by the check the analyzer already asks for).
    a change raises errors ×30, down ×5 — Facebook 2021 names the backbone but `errors`
    before `down`. A rule for the kind, measured on `bench` (200 scenarios, with changes) and on all the postmortems.

## 8. What the twenty-one taught (spec §34)

23. ~~**A loss that congests**~~ — DONE 2026-11-04 (spec §36: `congests`; Google 2019 not found → fourth).
    a dependency whose loss takes capacity — the caller slow, not failing (Google 2019).
24. ~~**Congestion shows both**~~ — CLOSED 2026-11-04, refuted (spec §37: two faults first 31% → 17%).
    one fault, latency and errors at once (AWS 2021).
25. **A rare kind stays rare**: a balancer's prior too low for a change at the onset to lift (Cloudflare 2022).
26. **An alert's time is not an onset**: a time said as an alert's, weighed as later than it began (Cloudflare
    2025-06).

## Later, when a server is needed

- A Worker again (its code: commit 30767db): an alert's webhook (Alertmanager) diagnosed with no page open and told in
  Telegram or Slack; shared incidents in Durable Objects; MCP with sessions for a remote agent.

## 5. A reader in a few megabytes (operator, 2026-11-04)

21. **Micro** — specs/reader.md: a byte-level reader (~1 MB) trained on the generator's logs, Qwen's paraphrases
    and the corpus; `Micro` ⊇ `Read`; 99% on unseen phrasings; on the page without a server; the incidents' table
    with `Micro`'s facts. No large model needed for extraction — it stays for conversation in okay-agent only.
