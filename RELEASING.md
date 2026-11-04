# Releasing

## The page (GitHub Pages)

The page is a static site: `index.html`, `icon.svg` and `page.js` (the front's program, linked by Scala.js), served
from the `gh-pages` branch at https://okay-effects.github.io/okay-reliability/.

```sh
sbt test pageDist                       # target/site
git worktree add /tmp/okr-pages gh-pages
rm -f /tmp/okr-pages/*.js /tmp/okr-pages/*.html /tmp/okr-pages/*.svg
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
