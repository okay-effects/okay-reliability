# okay-reliability — a reader in a few megabytes (spec, 2026-11-04)

Status: planned (operator's direction, 2026-11-04: "a micro-model specialized for reliability; a large model is not
needed there — or is it?"). Grows out of okay-inlay `specs/bridge.md` (readers to exact machines: on Qwen3.5-4B a
probe plus the model asked read a two-argument relation at 100% on unseen phrasings; a reader's errors are
systematic by phrasing, two readers fail on different ones).

## 1. Is a large model needed?

Not for the page. What the analyzer needs from text is narrow: per line or sentence, **a kind** (`down`, `errors`,
`slow`, `stale`, `retries`, `backlog`, `full`, `healthy`, `alive`, `change`, or nothing), **which named component is
the subject** and **which the callee**, the time (exact, `Instant.find`), and a confidence. The names are the
system's (exact string matches); only the roles and the kind are judgement. That is a closed domain: a classifier of
a few megabytes reaches it, as a sentiment probe does. Today `Read` (spec §5, §21) does it by patterns — exact
where a pattern fits, silent elsewhere — and the large model (Qwen3.6-35B in okay-agent, §13) does the rest by text.
The large model remains for ONE thing: leading an investigation in conversation (okay-agent's surface). Extraction,
and the page, do not need it.

## 2. Micro

- **Input**: a line or sentence (bytes, ≤ 256) with the mentioned names marked (positions of the system's names in
  it; found exactly). **Output**: the kind; the subject (which mention, or none); the callee (which mention, or none);
  a confidence (calibrated: the share right at that score, measured).
- **Model**: a byte-level transformer, 4 layers, d = 128, 256-byte context — about a million parameters, ~4 MB in
  float32, ~1 MB in int8. Trained on MLX through mlx-scala (autograd, as okay-forge). Run by a plain-Scala forward
  (one code on the JVM and in the browser through Scala.js — the page); WebGPU when it matters.
- **Data**, labelled for free: (1) the generator's logs in its formats with the gold facts (`facts.txt`); (2) the
  usual-stacks corpus (`ReadCorpusSuite`, real lines) labelled by `Read`; (3) **paraphrases**: Qwen rewrites the
  generator's lines and the chat's sentences in other words and formats (ru / en, prose, tickets, Slack) — the gold
  stays the generator's; the teacher is used at build time only; (4) the incidents' `facts.txt` as a held-out test
  (`incidents/`, real postmortems).
- **Two readers, by construction** (bridge's lesson): `Read` (patterns) and `Micro`; where both read, they must
  agree or the fact is marked unsure and shown; where only `Micro` reads, its confidence is the fact's.

## 3. Laws and measures

- `Micro` ⊇ `Read` on the corpus: every line `Read` reads, `Micro` reads the same (kind, subject, callee).
- On paraphrases `Read` cannot read: `Micro`'s accuracy per field; target 99% on held-out phrasings (bridge-4's
  bar), measured by format and by language.
- On the incidents: the facts an operator stated, read by `Micro` alone, against `facts.txt`; the analyzer's rank of
  the truth with `Micro`'s facts against the hand-stated ones (spec §28's table, one more column).
- Calibration: the confidence's buckets against the share right; an explanation's confidence = the weakest fact it
  rests on (Viterbi over the derivation, okay-datalog's semiring).

## 4. On the page

`Micro` reads pasted logs and chat in the browser (no server, no key): facts with their line, kind, confidence;
the unsure ones first for the person to confirm; the explanations with the facts they rest on. The model's size is
the page's budget: ~1 MB int8 loads with the page.

## 5. Steps

1. The data: the generator's paraphrase pass (Qwen3.5-4B / 3.6 through okay-mlx), the corpus, the incidents.
2. The model and its training in mlx-scala; the forward in core (JVM + JS); the laws above as tests.
3. `Micro` beside `Read` in the analyzer and on the page; the incidents' table with the new column.

## 6. Measured (2026-11-04)

`Micro` v0: bytes → 4 layers, d = 128, 4 heads, ffn 256; 592,768 parameters, 2.3 MB float32; trained on MLX in 1–3
minutes (`micro/`), the law MLX forward == plain forward held (`MicroSuite`). Held-out: 60 generated scenarios
(3,708 examples, seeds the training never saw); the corpus: `ReadCorpusSuite`'s 18 real lines (Spring, nginx,
Postgres, Envoy, pino, zap), scored as agreement with `Read` on the whole set of facts a line states.

| training data | held-out kind | alive | corpus (real lines) |
|---|---|---|---|
| the generator's lines (24.8k examples, "none" kept at 3× the rest) | 98.2–99.0% | 99.8% | 3 of 18 |
| + Qwen3.5-4B's paraphrases (12 styles; 1,352 lines, 1,048 distinct; 11.6k examples) | 98.8% | 99.6% | 2 of 18 |
| + the stacks' words (`Stacks`: each generated line's meaning rendered in Spring / nginx / Postgres / Envoy / pino / zap / Python / k8s; 23.6k examples) | 98.4% | 99.2% | **12 of 18** |

- **A 4B's paraphrases did not reach the real lines**: it leaks the fact's own words into the line ("зафиксирован
  симптом(N1, errors)"), mangles placeholders, repeats itself; 42% of its rewrites were dropped for not naming the
  components. Worth keeping as variety, not as the bridge to real logs.
- **The stacks' words did**: 2 → 12 of 18 — the same patterns `Read` knows (§21), written as data instead of regexes.
  The caveat, stated: the corpus and these templates are of one nature; real generalization needs lines from
  outside (the next test: lines collected from the web, labelled by hand).
- **The six misses are mostly UNSURE** (0.46–0.84 where the hits are 0.9–1.0): a connection refused read as the
  callee "errors" rather than "down" (Spring, pino's ENOTFOUND), an nginx refused as "web slow (0.46)", an access
  log's 502 and Postgres's "shutting down" read as nothing, Envoy's 503 UF as "deploy (0.65)". So the rule of §2
  works as designed: where `Read` and `Micro` differ, the fact is unsure and shown — and a confidence this low on
  its own is the signal.
- Not in the generator at all: `stale`, `backlog`, `config` (no cache or queue in the 5–10-service systems drawn;
  no config lines written) — the reader has never seen them. The generator's systems need caches and queues, and
  config changes, before `Micro` can read those kinds.

Next: `Micro` beside `Read` in the analyzer and on the page (agree → the fact; differ → unsure, shown first);
real lines from outside as the honest test; the generator extended (caches, queues, config changes); int8 weights
for the page (0.6 MB).

## 7. Two readers, measured (2026-11-05)

`Readers.log`: a log read by both. `Read`'s facts are kept as they are — Micro still misses lines `Read` reads (the
corpus: 10–12 of 18), so a disagreement does not demote a pattern's fact, it is listed; Micro adds what `Read` says
nothing of (a component on a line `Read` gives no fact of): a fact when sure (p ≥ 0.9, its source `micro:<c>:<line>`),
an unsure reading otherwise, shown for a person to confirm (`you (Micro)`), not used until then.

**The honest test**: 40 real messages of real software (Postgres, nginx, HikariCP, Redis, Kafka, Kubernetes, Go, Node,
Java, Elasticsearch), none from the generator or `Stacks`, labelled by hand (`micro/data/outside.tsv`,
`MicroOutsideSuite`):

| weights | Read right | Micro right | both right | wrong facts Micro added | unsure | corpus (= Read) |
|---|---|---|---|---|---|---|
| v1 (§6) | 11 | 15 | 17 | 2 | 19 | 12 of 18 |
| v2: + caches, queues, config changes (step c) | 11 | 17 | 17 | 2 | 20 | 10 of 18 |

Both readers: 17 of 40 against `Read`'s 11 — Micro adds 6, and misleads with 2 (sure and wrong: "too many clients"
as `slow`, a Kafka replica error as `retries`). v2 reads `deploy` and `config` (v1 did not) and has seen `stale` and
`backlog` (119–161 examples each: still few); the corpus' agreement fell by 2 (Read's facts are kept there anyway).
v2 kept. On the generated scenarios (`bench --micro true`, Read's home): Micro adds 2–3 facts in 200 scenarios,
"the only explanation" 73% → 72% (one fault), 35% → 34% (two), the first cause unchanged (93%, 31%); a scenario takes
~1 s more (the plain forward on every line and named component).

What it says: on real lines the pair is better than the patterns alone, at a cost of two sure mistakes in forty; 17 of
40 is still far from the 99% of held-out generated lines — the gap is the real world's words. Next: more real lines
(labelled), and training on them by kind of line, with the test kept apart.

## 8. On the page (2026-11-05)

The weights in int8 (`Micro.writeInt8`: one scale a tensor; 579 KB against 2,315 KB float32; the same kind on all 40
lines from outside), `site/micro.int8` beside page.js (written by `Int8` after every training; a test keeps it equal to
core's `micro.bin`). The page fetches it at start (without it, `Read` alone) and gives it to the program
(`App.reading(micro)`); a log or a folder's logs are read by both; the Incident lists the unsure readings ("Unsure —
Micro's readings, not used until confirmed") with a press to confirm each. Checked: `MicroPageSuite` (JVM) and the
page's e2e (a Stripe line only Micro reads, its fact `micro:checkout:1` used, in the browser).

## 9. Micro's facts weigh less (BACKLOG 27e, 2026-11-05)

A fact Micro adds is evidence, not a constraint. In the probabilities (`Probable.epsilon`) its source `micro:` has its
own ε — the measured share of its sure facts that were wrong (2 of 8 on the lines from outside, §7: ε = 0.25; a log
line's pattern 0.02, a chat 0.1, a person 0.05) — so one sure mistake cannot outweigh the facts beside it. The exact
explanations (`Explain`) must cover every other symptom, not Micro's: a wrong reading cannot force a fault into every
minimal set; Micro's symptoms still order the probable causes. ε is measured again with each version (§10).

Measured (v2 weights, `bench --micro true`, 200 scenarios): the exact search is Micro-proof again — "the only
explanation" 73% / 35%, as with `Read` alone (72% / 34% before §9); the probable causes: one fault first 92% (93%
alone), two faults first 30% (31%), top three 135 (140). v2 adds 24 / 42 facts on the generator's lines where v1 added
2 / 3: its "sure" is not sure enough — the next step (§10, calibration) is what fixes that, not ε.

## 10. The real test set, and Micro off the page (2026-11-05)

`micro/data/real-test.tsv`: 330 lines — LogHub's samples (one line per template) of seven programs Micro is never to
be trained on (Zookeeper, OpenStack, Spark, Hadoop, Apache, OpenSSH, HDFS; 290 lines) and the 40 of `outside.tsv` —
labelled by hand (what a line states about its writer; 278 none, 52 stating something), rows alternating dev / test
(`MicroRealSuite`). Measured, v2 weights:

| half | Read right | both right | Micro's sure-and-wrong facts | of the lines stating something, both read |
|---|---|---|---|---|
| dev (165) | 142 | 102 | 71 (43 per 100 lines) | 7 of 28 |
| test (165) | 143 | 97 | 81 (49 per 100 lines) | 7 of 24 |

On real routine lines — most of a real log — Micro invents failures: the 40 lines of §7 were nearly all failures and
hid it. No threshold saves it (dev: at p ≥ 0.9, 4 right and 44 wrong; at 0.99, 0 right and 8 wrong): its confidence
says nothing on real lines, so calibration (step g) cannot help until the model has seen them. **Micro is off the page**
(the page does not fetch its weights; `Read` alone, as before §8) until it meets the bar on dev — sure-and-wrong ≤ 1 in
100 lines, both readers no worse than `Read` — and then on test. It stays in the CLI (`bench --micro true`) and the
tests. Next: step h — real lines in training, routine ones above all, labelled by Qwen (a sample checked by hand), from
programs apart from the test's.

## 11. Real lines for training, labelled by a teacher (BACKLOG 27h, 2026-11-05)

`Label` (micro): Qwen3.6-35B, local through okay-mlx's gateway, labels real lines by the rule of the test set (its
prompt has no line of it), 20 a request, at build time only. The lines: LogHub's samples of nine programs apart from
the test's — BGL, Linux, HealthApp, Proxifier, Thunderbird, Android, HPC, Mac, Windows — up to two lines a template:
1,661 (`micro/data/real-train-lines.tsv`); labelled 1,221 in 90 minutes (22 batches never answered cleanly; the run
resumes): 978 none, 182 errors, 42 down, 10 deploy, 4 slow, 3 retries, 1 config, 1 healthy (`real-train.tsv`).
Checked by hand, 60 at random (30 none, 30 not): 57 agree — the three not, two routine process starts called a deploy
and a BGL "re-synch state" INFO line called errors. Training (v3): the generated, the paraphrased, the stacks' and the
real lines (all of them, twice). Real dev: sure-and-wrong 43 → 14.5 per 100 lines, both readers right 102 → 128 (Read
alone 142); outside: Micro alone 17 → 19, both 17 → 15.

## 12. A second opinion (BACKLOG 27g, 2026-11-05)

Two models of different seeds (`micro.bin`, `micro2.bin`; `Readers.log(…, second)`): a reading is as sure as the less
sure of the two, and where they name different kinds it is unsure (0.5). Dev, by the threshold of sure:

| | both right (Read alone 142) | sure-and-wrong | of 28 stating something, read |
|---|---|---|---|
| one model, 0.9 | 128 | 24 (14.5 per 100) | 6 |
| two, 0.7 | 128 | 29 | 8 |
| two, 0.9 | 137 | 7 (4.2 per 100) | 5 |
| two, 0.95 | 138 | 5 (3.0 per 100) | 5 |

The second opinion cuts the sure mistakes by three; the bar (≤ 1 in 100, no worse than `Read`) is not met: Micro
stays off the page. What is left is the data: real lines that state a failure are few (the teacher found 243 in
1,221), so Micro learns "none" well and failures badly. Next: more real failure lines (the 440 unlabelled; LogHub's
full logs, chosen by level and by the teacher; the failures of postmortems' quoted logs), and the test (not dev)
touched only when dev meets the bar.
