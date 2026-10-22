# Maintaining both languages

> 🇬🇧 **English** (canonical) · Portuguese counterparts live under `docs/pt-BR/`
> and as `*.pt-BR.md` next to their English originals.

This repository keeps a Portuguese version alongside the English one. The
whole point of the layout below is that the two cannot drift silently: when
the English side moves, a check fails and names the commit that has to be
ported.

---

## 1. The one rule that keeps this cheap

**Only prose is bilingual. Code is English-only.**

| Lives in both languages | Lives only in English |
|---|---|
| `README.md`, `CHANGELOG.md`, `docs/*.md`, `patches/README.md`, `examples/README.md` | Dockerfiles, `tools/Makefile`, `patches/**` (Scala, C++, Python), `examples/*.lf` |

The reason is maintenance cost, not preference. A duplicated paragraph that
goes stale is an annoyance. A duplicated **Dockerfile** that goes stale is a
second build that nobody runs, producing a different image — and this
repository already has one hard-won lesson about duplicated payloads (see
`04-translation-strategy.md` §5). Comments inside build files are therefore
English, whoever is reading them.

If a Portuguese reader needs the build files explained, that explanation goes
in `docs/pt-BR/01-dockerfile-lfc.md`, which is already a line-by-line
commentary on the Dockerfile.

---

## 2. Layout

```
README.md                      ← canonical            README.pt-BR.md
CHANGELOG.md                   ← canonical            CHANGELOG.pt-BR.md
patches/README.md              ← canonical            patches/README.pt-BR.md
examples/README.md             ← canonical            examples/README.pt-BR.md
docs/
├── README.md                  ← canonical            docs/pt-BR/README.md
├── 01-dockerfile-lfc.md       ← canonical            docs/pt-BR/01-dockerfile-lfc.md
├── 02-petalinux-vivado.md     ← canonical            docs/pt-BR/02-petalinux-vivado.md
├── 03-backlog-generalization.md ← canonical          docs/pt-BR/03-backlog-generalization.md
├── 04-translation-strategy.md   (maintainers, EN only)
├── 05-bilingual-maintenance.md  (this file, EN only)
└── pt-BR/
```

Two conventions make the pairing mechanical:

- **Identical basenames.** `docs/pt-BR/01-dockerfile-lfc.md` pairs with
  `docs/01-dockerfile-lfc.md`. Never rename one side only. Top-level files
  use the `.pt-BR.md` suffix instead, so the canonical `README.md` keeps the
  name that GitHub renders on the landing page.
- **`images/` is shared.** The screenshots show Vivado, PetaLinux and
  terminals — all English UI. There is no reason to duplicate ~60 PNGs; the
  Portuguese docs reference the same `../../images/` path.

Every pair carries a language switcher under its title, so a reader who
lands on the wrong one is a click away from the other:

```markdown
> 🇧🇷 **Português** · [🇬🇧 English](../01-dockerfile-lfc.md) — a versão em inglês é a canônica.
```

---

## 3. The provenance marker

Each Portuguese file declares which English commit it was translated from:

```markdown
<!-- translated-from: docs/01-dockerfile-lfc.md@f80059487 -->
```

That single line is what turns "we should keep the translation updated" into
something a machine can check. `tools/check-translations.py` compares the sha
against the last commit that touched the English file:

```console
$ python3 tools/check-translations.py
up to date  CHANGELOG.pt-BR.md
up to date  README.pt-BR.md
...
STALE       docs/pt-BR/01-dockerfile-lfc.md
            docs/01-dockerfile-lfc.md moved f80059487 -> 23a52afe9
              23a52af docs: add a requirement to 01

1 stale, 0 needing attention, 7 up to date.
```

It does not just say "stale" — it lists the commits you have to port. After
updating a translation, stamp it:

```bash
python3 tools/check-translations.py --update   # re-stamps every marker
git add -A && git commit -m "docs(pt-BR): port 23a52af to 01-dockerfile-lfc"
```

Run `--update` once right after the first commit of this layout, to replace
the initial `@unknown` placeholders with real shas.

Adding a new bilingual document means adding one entry to the `PAIRS` dict at
the top of that script. Nothing else.

---

## 3b. Landing this in an existing clone

`tools/apply-translation.sh` turns this delivery into a branch of six
reviewable commits on your own clone, instead of a single "translate
everything" blob:

```bash
cd /path/to/this/delivery
./tools/apply-translation.sh ~/src/Cyber_PhysicalSystems
```

It refuses to run on a dirty worktree, never deletes anything, uses `git mv`
for the one rename, and finishes by re-running the three mechanical checks.
The commit order matters in one place: the `PROJETO` → `PROJECT` rename lands
*before* the Dockerfile, because `docker/lingua-franca/Dockerfile` embeds a
copy of `tools/Makefile` — so every commit leaves the tree self-consistent.

After it finishes, the acceptance test is the image build:

```bash
docker build -t lf-chisel docker/lingua-franca
```

The four embedded smoke tests are what prove a `sed` pattern, a heredoc
terminator or a patch anchor did not get damaged in translation.

---

## 4. Day-to-day workflow

**English first, always.** The canonical file is the one that gets reviewed
for technical accuracy; the translation follows it. Writing Portuguese first
and translating afterwards means the technical review happens on a file that
is not the source of truth.

Two acceptable rhythms — pick one and stick to it:

| | How it works | Good when |
|---|---|---|
| **Same PR** | English change and Portuguese change in the same PR, as two commits (`docs:` then `docs(pt-BR):`) | The change is small, or you write both languages |
| **Follow-up** | English change merges; CI flags the translation as stale; a follow-up PR labelled `i18n` ports it | Someone else does the translating, or the change is urgent |

What is *not* acceptable is translating in the same commit as the English
change: the diff then mixes a reviewable technical change with a few hundred
lines of prose, and the reviewer approves one by rubber-stamping the other.

For a correction that applies to both (a wrong command, a wrong path), fix
the English file, then port it — never only the Portuguese one. A fix that
exists only in the translation is invisible to everyone reading the canonical
docs.

---

## 5. CI

Add to the checks described in `04-translation-strategy.md` §6:

```yaml
- name: pt-BR translations not stale
  run: python3 tools/check-translations.py
```

One decision to make: whether this **blocks** a merge or only warns.

- **Blocking** guarantees the languages never diverge, at the cost of making
  every English docs change wait for its translation. On a small team that
  turns into pressure to skip the docs change entirely.
- **Warning** (`continue-on-error: true`, or run it on a schedule and open an
  issue) keeps English moving and accumulates a visible translation debt.

The recommendation for this repository is **warn on PRs, block on tagged
releases** — nobody is held up day to day, and no release ships with
Portuguese docs that describe a build that no longer exists.

The accent-based "no Portuguese left in source" grep from
`04-translation-strategy.md` §6 must now exclude the translated files, or it
will fire on every one of them:

```bash
! grep -rniE '\b(não|arquivo|placa|correção|usuário|simulação|diretório)\b' \
    --include='*.md' --include='*.py' --include='*.lf' --include='*.hpp' \
    --include='*.cpp' --include='*.scala' --include='Makefile' --include='Dockerfile' \
    . ':!references' ':!docs/pt-BR' ':!*.pt-BR.md'
```

That exclusion is the reason the `.pt-BR.md` suffix and the `pt-BR/`
directory exist as naming conventions rather than something ad hoc: the
guard can tell translations from accidents by path alone.

---

## 6. When a document should stop being bilingual

Not every file is worth translating forever. Reasonable grounds to drop a
Portuguese counterpart (delete the file, remove it from `PAIRS`, and say so
in the commit message):

- It is addressed to maintainers rather than users — `04-translation-strategy.md`
  and this file are English-only for exactly that reason.
- It has been stale for several releases, which is evidence nobody reads it.
- It is a historical record that will not change again, in which case freeze
  it: note at the top that it describes the state at tag `X` and remove its
  marker so the checker stops tracking it.

Dropping one is a normal decision, not a failure. Carrying a translation that
nobody updates is worse than not having it, because readers cannot tell it is
out of date.
