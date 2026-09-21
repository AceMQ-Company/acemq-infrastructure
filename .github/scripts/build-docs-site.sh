#!/usr/bin/env bash
# Render docs/*.md into site/.
#
#   bash .github/scripts/build-docs-site.sh
#
# Lives here rather than in the shared scripts folder because the docs workflow
# runs from a checkout of this repository alone and cannot reach anything
# outside it. Runs locally too, for previewing before pushing.
#
# There is no generated API reference to fold in. There is no API yet -- see
# docs/roadmap.md. When there is, the javadoc goes under site/apidocs/ and the
# navigation gains an entry, the same way acemq-java-rabbitmq-admin does it.
#
# Two link checks, at both ends:
#
#   * before rendering, over docs/*.md, because cross-page links are written as
#     .md so the same files read correctly on GitHub -- which is where somebody
#     meets these pages before they find the site. A site-only check cannot see
#     that half: it runs on the rewritten output and is satisfied by guide.html
#     existing whatever the markdown said.
#
#   * after rendering, over site/*.html, for links AND anchors. A dead anchor is
#     a 200 that lands in the wrong place, so a file-existence check cannot see
#     it: rename a heading, pandoc renames its id with it, and every link
#     written against the old spelling silently points at the top of the page.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
OUT="site"

command -v pandoc >/dev/null || { echo "pandoc is required" >&2; exit 1; }

# The source-side check. It runs first because it needs nothing rendered, and a
# bad link is cheapest to find before a minute of pandoc.
python3 - <<'PY'
import os, re, sys, urllib.parse

DOCS = "docs"

pages = sorted(f for f in os.listdir(DOCS) if f.endswith(".md"))
broken = []
links = 0


def prose(body):
    """The page with its code removed.

    These pages are full of YAML and shell, and a link-shaped regex finds `](`
    inside a code block sooner or later. A link never lives inside code, so
    dropping fenced blocks and inline spans before scanning costs the check
    nothing.
    """
    lines, fence = [], None
    for line in body.split("\n"):
        marker = re.match(r"\s*(```+|~~~+)", line)
        if fence is None and marker:
            fence = marker.group(1)[0] * 3
            continue
        if fence is not None:
            # Closing on the same character, so a ``` inside a ~~~ block -- how
            # a fenced block is shown in prose -- does not end it early.
            if marker and marker.group(1)[0] * 3 == fence:
                fence = None
            continue
        lines.append(line)
    # Inline spans, longest run of backticks first so ``a ` b`` closes on its
    # own pair rather than on the single backtick it is quoting.
    return re.sub(r"(`+)[\s\S]*?\1", " ", "\n".join(lines))


for page in pages:
    with open(os.path.join(DOCS, page), encoding="utf-8") as handle:
        body = handle.read()
    for target in re.findall(r"\]\(([^)\s]+)\)", prose(body)):
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        path = urllib.parse.unquote(target.partition("#")[0])
        if not path:
            continue
        links += 1
        # Nothing in this repository generates HTML into docs/, so every .html
        # target here is a docs page link written the wrong way round. That is
        # the mistake this check exists for, so it is named as that rather than
        # reported as a missing file.
        if path.endswith(".html"):
            broken.append("{} -> {}  (a docs page link belongs in .md)".format(page, target))
            continue
        if not os.path.exists(os.path.join(DOCS, path)):
            broken.append("{} -> {}".format(page, target))

if broken:
    print("::error::docs/ links that are dead when the pages are read on GitHub:")
    for item in broken:
        print("  " + item)
    sys.exit(1)
print("{} source pages, {} internal links, every one resolves inside docs/"
      .format(len(pages), links))
PY

# The option was renamed: --highlight-style in older pandoc, --syntax-highlighting
# in newer, and each rejects or deprecates the other. Ubuntu's package and a
# current Homebrew install sit on opposite sides of that change, so the flag is
# chosen rather than assumed -- hardcoding either one breaks the build somewhere.
if pandoc --help 2>&1 | grep -q -- '--syntax-highlighting'; then
  HIGHLIGHT=(--syntax-highlighting=tango)
else
  HIGHLIGHT=(--highlight-style=tango)
fi

rm -rf "$OUT"
mkdir -p "$OUT"

# Images as well as stylesheets. Copying only *.css is how a logo ends up
# referenced by every page and served by none.
if compgen -G "docs/assets/*" > /dev/null; then
  mkdir -p "$OUT/assets"
  cp docs/assets/* "$OUT/assets/"
fi

cat > "$OUT/style.css" <<'CSS'
:root {
  color-scheme: light dark;
  --fg:#1a1a1a; --bg:#fff; --muted:#5f5f5f; --line:#e4e4e4;
  --accent:#b4451f; --code-bg:#f7f7f5; --nav-bg:#fbfbfa;
}
@media (prefers-color-scheme: dark) {
  :root { --fg:#e8e8e8; --bg:#161616; --muted:#9c9c9c; --line:#2d2d2d;
          --accent:#ff8a5c; --code-bg:#1e1e1e; --nav-bg:#1b1b1b; }
}
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--fg);
       font:16px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; }
nav.top { background:var(--nav-bg); border-bottom:1px solid var(--line);
          padding:.85rem 1.25rem; display:flex; gap:1.15rem; flex-wrap:wrap; align-items:baseline;
          position:sticky; top:0; z-index:10; }
nav.top .brand { display:inline-flex; align-items:center; gap:.5rem; font-weight:700;
                 letter-spacing:-.01em; margin-right:.5rem; }
nav.top .brand img { height:22px; width:auto; display:block; }
/* The mark is black on transparent, so it disappears against a dark page.
   Inverting is enough for a two-tone logo and avoids shipping a second file. */
@media (prefers-color-scheme: dark) { nav.top .brand img { filter: invert(1) brightness(1.15); } }
nav.top a.enterprise { color:var(--fg); opacity:.78; }
nav.top a.enterprise:hover { opacity:1; color:var(--accent); }
nav.top a { color:var(--fg); text-decoration:none; font-size:.9rem; opacity:.78; }
nav.top a:hover { opacity:1; color:var(--accent); }
nav.top a.roadmap { color:var(--accent); opacity:1; font-weight:600; }

/* Grouped navigation. No JavaScript: the menu opens on hover and on focus-within,
   so a keyboard reaches it and a blocked script cannot break it. */
nav.top .group { position:relative; display:inline-block; }
nav.top .group > button { font:inherit; font-size:.9rem; color:var(--fg); opacity:.78;
  background:none; border:0; padding:0; cursor:pointer; }
nav.top .group > button::after { content:" \25be"; font-size:.8em; opacity:.7; }
nav.top .group:hover > button, nav.top .group:focus-within > button {
  opacity:1; color:var(--accent); }
nav.top .group .menu { display:none; position:absolute; left:0; top:100%; z-index:20;
  background:var(--nav-bg); border:1px solid var(--line); border-radius:8px;
  padding:.4rem 0; min-width:16rem; box-shadow:0 6px 24px rgba(0,0,0,.12); }
nav.top .group:hover .menu, nav.top .group:focus-within .menu { display:block; }
nav.top .group .menu a { display:block; padding:.35rem 1rem; opacity:.85; white-space:nowrap; }
nav.top .group .menu a:hover { background:var(--code-bg); opacity:1; }
nav.top a.roadmap { margin-left:auto; }

/* On a narrow screen the menus would hang off the edge, so everything unfolds
   into a list instead of pretending to be a menu bar. */
@media (max-width: 900px) {
  nav.top { flex-wrap:wrap; }
  nav.top .group { position:static; }
  nav.top .group .menu { position:static; display:block; border:0; box-shadow:none;
    padding:0; background:none; min-width:0; }
  nav.top .group > button { display:none; }
  nav.top .group .menu a { display:inline-block; padding:0; }
  nav.top a.roadmap { margin-left:0; }
}
main { max-width:47rem; margin:0 auto; padding:2.5rem 1.25rem 5rem; }
h1 { font-size:2rem; letter-spacing:-.025em; margin:0 0 1.5rem; }
h2 { font-size:1.3rem; letter-spacing:-.015em; margin:2.75rem 0 .85rem;
     padding-top:.4rem; border-top:1px solid var(--line); }
h3 { font-size:1.05rem; margin:1.75rem 0 .6rem; }
p, li { color:var(--fg); }
a { color:var(--accent); }
pre { background:var(--code-bg); border:1px solid var(--line); border-radius:8px;
      padding:1rem 1.15rem; overflow-x:auto; font-size:.855rem; line-height:1.55; }
code { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:.9em; }
p code, li code, td code { background:var(--code-bg); border:1px solid var(--line);
      border-radius:4px; padding:.08em .35em; color:var(--accent); }
pre code { background:none; border:none; padding:0; color:inherit; }
table { border-collapse:collapse; width:100%; font-size:.92rem; display:block; overflow-x:auto; }
th,td { text-align:left; padding:.55rem .8rem; border-bottom:1px solid var(--line); vertical-align:top; }
th { color:var(--muted); font-weight:600; }
blockquote { border-left:3px solid var(--line); margin:1.25rem 0; padding:.2rem 0 .2rem 1.15rem; color:var(--muted); }
footer { max-width:47rem; margin:0 auto; padding:1.5rem 1.25rem 4rem;
         border-top:1px solid var(--line); color:var(--muted); font-size:.85rem; }

/* Pandoc writes its own syntax colours into a <style> block in the head, and
   they are tuned for a white page: numbers, floats and base-n literals are all
   #0000cf, which on this page's dark background is about 1.4:1 against it.
   This stylesheet is linked after that block, so redefining the handful of
   colours that go dark is enough; the light palette is left as pandoc chose. */
@media (prefers-color-scheme: dark) {
  code span.dv, code span.bn, code span.fl { color:#b5cea8; }   /* literals */
  code span.st, code span.ch, code span.vs { color:#a3d18a; }   /* strings */
  code span.co, code span.cn                { color:#9c9c9c; }   /* comments */
  code span.kw, code span.cf                { color:#7fb3ff; }   /* keywords */
  code span.dt, code span.bu                { color:#7fd4c1; }   /* types */
  code span.fu                              { color:#dcb6ff; }   /* functions */
  code span.at, code span.va                { color:#e8e8e8; }
  code span.op, code span.sc                { color:#c9c9c9; }
  code span.er, code span.al                { color:#ff8a5c; }
}
CSS

# Five top-level entries with the design pages grouped underneath. Eleven in a
# row is legible at 1600px and wraps into three lines on a laptop, and a
# navigation nobody can scan is one nobody uses.
#
# The group opens on hover and on focus, so the keyboard reaches it too, and
# every link is a plain anchor -- it needs no JavaScript and still works when
# JavaScript is blocked or fails to load.
NAV='<nav class="top">
  <span class="brand"><img src="assets/acemq.png" alt="AceMQ"> infrastructure</span>
  <a href="index.html">Overview</a>
  <a href="what-it-is.html">What this is</a>

  <div class="group">
    <button type="button" aria-haspopup="true">Design</button>
    <div class="menu">
      <a href="shape.html">Language and shape</a>
      <a href="broker-agnostic.html">Broker-agnostic, honestly</a>
      <a href="blue-green.html">Blue/green</a>
      <a href="canary.html">Canary</a>
      <a href="message-state.html">Message state</a>
    </div>
  </div>

  <a href="configuration.html">Configuration</a>
  <a href="library.html">The library</a>
  <a href="prior-art.html">Prior art</a>
  <a class="roadmap" href="roadmap.html">Roadmap</a>
  <a class="enterprise" href="https://acemq.org/">Client libraries</a>
  <a class="enterprise" href="https://acemq.com">Enterprise support</a>
</nav>
<main>'

FOOT='</main>
<footer>
  <a href="https://github.com/AceMQ-Company/acemq-infrastructure">AceMQ
  infrastructure</a> &mdash;
  Apache-2.0, and provided without warranty &mdash; see the
  <a href="licence.html">licence</a>.
  <a href="https://acemq.com">Enterprise support</a>.
  The client libraries are at <a href="https://acemq.org/">acemq.org</a>.
  A design, not a product: nothing here deploys anything yet.
  RabbitMQ is a trademark of Broadcom Inc. This project is not affiliated with it.
</footer>'

printf '%s' "$NAV" > "$OUT/.nav.html"
printf '%s' "$FOOT" > "$OUT/.foot.html"

for f in docs/*.md; do
  base="$(basename "${f%.md}")"
  # The first heading is the page title. pagetitle rather than title, because
  # pandoc's template renders a title block from "title" and would print the
  # H1 that the markdown already contains.
  title="$(head -n1 "$f" | sed 's/^#\{1,6\} *//')"
  # The overview's own heading is the site's name, and suffixing it produces
  # "AceMQ infrastructure — AceMQ infrastructure" in the browser tab.
  if [ "$title" = "AceMQ infrastructure" ]; then
    pagetitle="$title"
  else
    pagetitle="$title — AceMQ infrastructure"
  fi
  pandoc "$f" \
    --from=gfm --to=html5 --standalone \
    --metadata pagetitle="$pagetitle" \
    "${HIGHLIGHT[@]}" \
    --css=style.css \
    --include-before-body="$OUT/.nav.html" \
    --include-after-body="$OUT/.foot.html" \
    --output "$OUT/$base.html"
  # Links between pages are written as .md so they work when the same files are
  # read on GitHub; only the rendered copy is rewritten. The anchor is kept: a
  # link to another page's section is written .md#section and must survive.
  perl -pi -e 's{href="([^":#]*)\.md(#[^"]*)?"}{href="$1.html$2"}g' "$OUT/$base.html"
  echo "  rendered $base.html"
done

rm -f "$OUT/.nav.html" "$OUT/.foot.html"

# The rendered check: links AND anchors, over the output that is actually
# published.
python3 - <<'PY'
import os, re, sys

SITE = "site"
pages = sorted(f for f in os.listdir(SITE) if f.endswith(".html"))

_ids = {}
def ids(path):
    if path not in _ids:
        body = open(path, encoding="utf-8", errors="replace").read()
        _ids[path] = set(re.findall(r'\bid="([^"]+)"', body))
    return _ids[path]

broken, dangling, anchors = [], [], 0
for page in pages:
    body = open(os.path.join(SITE, page), encoding="utf-8", errors="replace").read()
    for href in re.findall(r'href="([^"]+)"', body):
        if href.startswith(("http://", "https://", "mailto:")):
            continue
        target, _, fragment = href.partition("#")
        # No target means the link is to a section of the page it is written on.
        path = os.path.join(SITE, target) if target else os.path.join(SITE, page)
        if target:
            # A link that climbs out of site/ is unservable however it resolves
            # on this disk: ../README.md is the usual way in, written by somebody
            # reading docs/ on GitHub, where the repository root is one level up
            # and the site is not. Refused before the existence check, which
            # would otherwise pass it whenever the file happens to sit there.
            if os.path.relpath(path, SITE).startswith(os.pardir):
                broken.append(f"{page} -> {href}")
                continue
            if not os.path.exists(path):
                broken.append(f"{page} -> {href}")
                continue
            if os.path.isdir(path):
                path = os.path.join(path, "index.html")
                if not os.path.exists(path):
                    broken.append(f"{page} -> {href}")
                    continue
        if not fragment:
            continue
        # A dead anchor on a live page is a 200 that lands in the wrong place, so
        # a file-existence check cannot see it. Same-page links are checked too --
        # href="#section" is the commonest form and the one most often left
        # behind by a rename.
        anchors += 1
        if fragment not in ids(path):
            dangling.append(f"{page} -> {href}")

if broken:
    print("::error::the site links to pages that do not exist:")
    for b in broken:
        print("  " + b)
if dangling:
    print("::error::the site links to anchors that do not exist:")
    for d in dangling:
        print("  " + d)
if broken or dangling:
    sys.exit(1)
print(f"{len(pages)} pages, every internal link resolves "
      f"and all {anchors} anchors exist")
PY

# Jekyll would otherwise skip anything underscore-prefixed.
touch "$OUT/.nojekyll"

echo "Site written to $REPO_ROOT/$OUT/index.html"
