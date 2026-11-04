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
