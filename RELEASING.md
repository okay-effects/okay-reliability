# Releasing

## The page (GitHub Pages)

The page is a static site: `index.html`, `icon.svg`, `page.js` and `micro.int8` (Micro's weights, specs/reader.md §8) (the front's program, linked by Scala.js), served
from the `gh-pages` branch at https://okay-effects.github.io/okay-reliability/.

```sh
sbt test pageDist                       # target/site
git worktree add /tmp/okr-pages gh-pages
find /tmp/okr-pages -maxdepth 1 \( -name "*.js" -o -name "*.html" -o -name "*.svg" -o -name "*.int8" \) -delete
cp target/site/* /tmp/okr-pages/
git -C /tmp/okr-pages add -A
git -C /tmp/okr-pages commit -m "the page: <what changed>"
git -C /tmp/okr-pages push origin gh-pages
git worktree remove /tmp/okr-pages
URL=https://okay-effects.github.io/okay-reliability/ node scripts/page.e2e.js   # once Pages has built it
```

## The CLI and the MCP server

`sbt stage` → `target/okay-reliability` (`bin/okay-reliability`, its jars in `lib`): `okay-reliability serve` is the
MCP server an agent starts (`.mcp.json`).

## The library (Maven on GitHub Pages)

`core` — the model and the four products, pure, on the JVM, in JavaScript and on Scala Native
(`okay-reliability`) — and `cli` — the CLI and the MCP server on stdio (`okay-reliability-cli`), on the same
`gh-pages` branch under `maven/`, served at https://okay-effects.github.io/okay-reliability/maven. Scala 3.9.

```scala-sbt
resolvers ++= Seq("okay-reliability" at "https://okay-effects.github.io/okay-reliability/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-datalog" at "https://okay-effects.github.io/okay-datalog/maven")
libraryDependencies += "io.github.okay-effects" %%% "okay-reliability" % "0.1.0"
```

While nobody but us uses it, a change is REPUBLISHED as 0.1.0 and the tag moved (`git tag -f v0.1.0`).

```sh
sbt test
rm -rf target/pages-maven && sbt coreJVM/publish coreJS/publish coreNative/publish cli/publish
git worktree add /tmp/okr-pages gh-pages
mkdir -p /tmp/okr-pages/maven && rsync -ac target/pages-maven/ /tmp/okr-pages/maven/   # -c: same size, new bytes
git -C /tmp/okr-pages add -A && git -C /tmp/okr-pages commit -m "release 0.1.0"
git -C /tmp/okr-pages push origin gh-pages && git worktree remove /tmp/okr-pages
git tag -f v0.1.0 && git push -f origin v0.1.0
```

The page's publishing above removes only `*.js`, `*.html` and `*.svg` at the top: `maven/` stays.
