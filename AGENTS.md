# AGENTS.md — okay-reason

Four products on one model of a system: the architecture analyzer, the failure generator, the failure analyzer, the
remedy generator. Read `specs/reason.md` first (the model, the simulator, each product, the laws); it is the source of
truth — change it before the code.

- One simulator (`Spread`) for all four: the generator spreads faults forward, the analyzer searches the faults that
  explain what was seen, the remedies spread again after each action. Never a second notion of how a failure spreads.
- The tools' effects (`Desk`, `Files`) have handlers; `Main.Live.run` gives them in one line. Tests use the memory
  handlers or the pure core.
- `sbt test` (the laws); `sbt "run bench"` after any change to `Spread`, `Explain`, `Read` or `Generate` — record the
  numbers in specs/reason.md §8 when they move.
- Scala 3, `-Werror`, lines at most 120 characters, English in code and docs. Scripts are scala-cli, never Python.
- Commits: plain messages, no attribution trailers.
