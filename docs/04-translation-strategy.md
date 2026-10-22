# Translation strategy — pt-BR → English

How the English translation of this repository was produced, and how to land
it in the upstream repository without breaking the build, without losing
history, and without the two languages drifting apart afterwards.

---

## 1. What was translated, and what was deliberately left alone

The repository was classified into four layers before any text was touched,
because each one carries a different risk:

| Layer | Files | Treatment |
|---|---|---|
| **Prose** (zero build risk) | `README.md`, `CHANGELOG.md`, `docs/*.md`, `patches/README.md`, `examples/README.md` | Fully translated. |
| **Comments and messages** (no semantic risk, but inside build files) | `docker/*/Dockerfile`, `tools/Makefile`, `patches/.../uiodriver.hpp`, `platform-mpsoc-uio.cpp`, `patch_PlatformWrapper.py`, `examples/loopback_sim.lf` | Comments, `echo`/`printf` strings and `LABEL` text translated. No command, path, flag or anchor string changed. |
| **Public interface** (breaking risk) | the `PROJETO` variable of `tools/Makefile` | Renamed to `PROJECT`, with `PROJETO` kept as a deprecated alias (see §4). |
| **Identifiers and external contracts** (must not change) | Scala/Kotlin symbols, `platformName`, `fpgaBoard` values, `LF_FPGA_*` macros, the `assert content.count(anchor) == 1` anchor strings in `patches/*.py`, reactor names in the `.lf` examples (`Accel_Subtrac`, `Hardware_ZCU106`, `Software_PetaLinux`), the `images/passoNN.png` filenames | Untouched. |

The anchor strings deserve emphasis: `patches/compiler-kotlin/*.py` and
`patches/fpga-tidbits/*.py` match **exact upstream source lines**. Translating
or reflowing one of those strings would turn a loud `AssertionError` at build
time into a patch that silently does not apply. They were treated as data,
not as text.

### Deliberately out of scope

- **`references/`** — third-party papers and reports (PDF/DOCX), including
  Portuguese filenames. Not source; translating them would misattribute
  other people's work.
- **`images/passoNN.png`** — `passo` means "step". Renaming them means
  touching ~60 binary files plus ~60 `src="..."` references for no
  functional gain; worth doing only as a separate, isolated commit if
  wanted.
- **The `.lf` examples' identifiers** — `Accel_Subtrac` is a Portuguese
  abbreviation, but it is an identifier that appears in generated RTL, in
  the Vivado hierarchy and in the tutorial screenshots in `docs/02`.
  Renaming it invalidates the screenshots.

---

## 2. Line endings: do this first, in its own commit

The archive arrived with **mixed line endings**: `LICENSE`, both Dockerfiles,
`tools/Makefile`, `CHANGELOG.md`, `docs/01`–`03`, `examples/led.lf` and
`examples/ExplorationMultipleReactoresCodesign.lf` were CRLF; everything else
was LF. Meanwhile `.gitattributes` already declares `* text=auto eol=lf`.

Everything here has been normalized to LF, which is what `.gitattributes`
asks for and what git would do on commit anyway. CRLF inside the Dockerfile
heredocs also meant the `.scala`/`.hpp` files written into the image carried
CRLF.

**Land it separately**, so the translation diff stays readable:

```bash
git switch -c chore/normalize-eol
git add --renormalize .
git commit -m "chore: normalize line endings to LF per .gitattributes"
```

Mixing this with the translation produces a diff where every line of every
file appears changed and nothing can be reviewed.

---

## 3. Suggested commit/PR sequence

One branch, five reviewable commits — ordered so that each one can be
verified on its own:

| # | Commit | Why this order |
|---|---|---|
| 0 | `chore: normalize line endings to LF` | Separates whitespace noise from content (§2). |
| 1 | `i18n: translate code comments, build messages and Dockerfiles` | Highest risk, smallest diff. Verify with a real `docker build` before anything else lands. |
| 2 | `i18n: rename Makefile PROJETO -> PROJECT (alias kept)` | The only behavioural change in the whole set; isolated so it can be reverted alone. |
| 3 | `i18n: translate README and CHANGELOG` | Zero risk, high visibility. |
| 4 | `docs: translate docs/ and rename 03-backlog-generalizacao.md` | Use `git mv` so the rename is recorded as a rename, not as a delete + add. |
| 5 | `ci: add translation drift guards` | §6. |

For the rename, preserving history:

```bash
git mv docs/03-backlog-generalizacao.md docs/03-backlog-generalization.md
```

Then confirm git detected it: `git log --follow docs/03-backlog-generalization.md`.

### Review technique for a translation-only commit

A translation PR is reviewed by proving that **nothing executable changed**:

```bash
# commits 1 and 4: no executable line should differ
git diff --stat HEAD~1
git diff HEAD~1 -- docker/ | grep -E '^[+-]' | grep -vE '^[+-]\s*(#|//)' 
# ^ should show only heredoc comment lines, never a RUN/ENV/COPY directive
```

---

## 4. Backwards compatibility of the `Makefile` interface

`PROJETO` was a public knob: it appears in the `README`, in `docs/01`, in the
header comments of `tools/Makefile` and in the copy of the Makefile embedded
in the image. A bare rename silently breaks anyone's muscle memory and any
script in the lab.

The chosen approach keeps both working:

```make
PROJECT  ?= ExplorationMultipleReactorsCodesignAccelsOpBasics.lf
ifdef PROJETO
PROJECT := $(PROJETO)
endif
```

Semantics, verified in this repository:

- `make compiler PROJECT=x.lf` → uses `x.lf`.
- `make compiler PROJETO=x.lf` → uses `x.lf` and prints a yellow
  deprecation warning.
- `make compiler PROJECT=a.lf PROJETO=b.lf` → uses `a.lf`; a command-line
  variable outranks a makefile assignment, so the new name always wins.

Suggested deprecation window: keep the alias for one release cycle, then
remove the `ifdef` block and the warning line in `check-string`.

The colour variables (`VERMELHO`/`VERDE`/`AMARELO` → `RED`/`GREEN`/`YELLOW`)
and `MODO`/`simulacao` → `MODE`/`simulation` are internal to the Makefile and
were renamed without an alias.

---

## 5. The single hardest problem: the duplicated payloads

`docker/lingua-franca/Dockerfile` carries **seven heredoc payloads that are
byte-for-byte duplicates** of standalone files in the repository:

| Heredoc inside the Dockerfile | Duplicate of |
|---|---|
| `AXIPlatformWrapper.scala` | `patches/fpga-tidbits/axi/AXIPlatformWrapper.scala` |
| `Main.scala` | `patches/reactor-chisel/Main.scala` |
| `PYNQZCU104CCWrapper.scala` | `patches/fpga-tidbits/axi/PYNQZCU104CCWrapper.scala` |
| `ZCU106Wrapper.scala` | `patches/fpga-tidbits/axi/ZCU106Wrapper.scala` |
| `uiodriver.hpp` | `patches/fpga-tidbits/cpp/uiodriver.hpp` |
| `platform-mpsoc-uio.cpp` | `patches/fpga-tidbits/cpp/platform-mpsoc-uio.cpp` |
| the generated `Makefile` | `tools/Makefile` |

`patches/README.md` states that the files there are "**exactly** the content
that the Dockerfile injects". Translating a comment in one copy and not the
other silently breaks that promise — and the copy that actually ends up
inside the image is the Dockerfile one, i.e. the one nobody reviews.

**Rule adopted: `patches/` and `tools/` are the source of truth; the
Dockerfile heredocs are generated from them.** `tools/sync-dockerfile-payloads.py`
does the sync and is idempotent:

```bash
python3 tools/sync-dockerfile-payloads.py          # rewrites the heredocs from the sources
git diff --exit-code docker/lingua-franca/Dockerfile   # in CI: fail if out of sync
```

The medium-term fix is to stop duplicating: replace each
`RUN cat > … <<'EOF'` with `COPY patches/… /opt/lingua-franca/…`. That
removes the class of bug entirely, at the cost of making the build depend on
the build context (today the Dockerfile is self-contained). The sync script
is the cheap intermediate step.

---

## 6. Guards against regression and drift

Four checks, cheap enough to run on every PR:

```yaml
# .github/workflows/checks.yml (sketch)
- name: Dockerfile payloads in sync with patches/
  run: |
    python3 tools/sync-dockerfile-payloads.py
    git diff --exit-code docker/lingua-franca/Dockerfile

- name: No Portuguese left in source
  run: |
    ! grep -rniE '\b(não|arquivo|placa|correção|usuário|simulação|diretório|seção|é necessário)\b' \
        --include='*.md' --include='*.py' --include='*.lf' --include='*.hpp' --include='*.cpp' \
        --include='*.scala' --include='Makefile' --include='Dockerfile' \
        . ':!references'

- name: Makefile parses, both arches
  run: |
    cd tools && touch dummy.lf
    make -n compiler PROJECT=dummy.lf ARCH=arm
    make -n compiler PROJECT=dummy.lf ARCH=x86_64
    ! make -n compiler PROJECT=dummy.lf ARCH=riscv

- name: Markdown links and anchors resolve
  run: npx --yes markdown-link-check -q README.md CHANGELOG.md docs/*.md
```

The accent-based grep is the useful one: Portuguese without accents is hard
to detect mechanically, but in practice almost every Portuguese sentence in
this repository contains at least one accented word or one of the stopwords
above. Keep the list in a file and extend it when something slips through.

A heavier but conclusive check, worth running on the branch before merge
rather than on every push: `docker build` of the `lf-chisel` image. The four
embedded smoke tests are the real acceptance test for the translation of the
Dockerfile — if a `sed` pattern, a heredoc terminator or an anchor string was
damaged, the image fails to build. **That build was not run while producing
this translation** (no Docker daemon available in the environment used);
it is the first thing to run on the branch.

What *was* verified here, mechanically:

- heredoc openers vs. terminators balanced (9 `EOF`, 4 `PYEOF`);
- all four embedded `python3` blocks parse (`ast.parse`);
- all four `patches/*.py` scripts parse;
- `uiodriver.hpp` + `platform-mpsoc-uio.cpp` compile with
  `g++ -std=c++11 -Wall`, no warnings;
- `tools/Makefile` parses, and `compiler`/`deploy`/`clean`, both arches, the
  invalid-arch error path and the `PROJETO` alias all behave;
- the embedded Makefile is byte-identical to `tools/Makefile`, and all six
  code payloads to their `patches/` counterparts;
- every markdown link and intra-document anchor resolves;
- no accented character remains in any source file (only typography and
  box-drawing characters).

---

## 7. Both languages are kept — see `05-bilingual-maintenance.md`

The risk with two languages is that the copies disagree and the reader cannot
tell which one is current. This repository keeps Portuguese anyway, and
addresses that risk structurally rather than by discipline alone:

- English is canonical; Portuguese counterparts live in `docs/pt-BR/` and as
  `*.pt-BR.md`.
- Only prose is bilingual. Dockerfiles, the Makefile and all code stay
  English-only — a stale paragraph is an annoyance, a stale Dockerfile is a
  second build nobody runs.
- Each translation records the English commit it came from, and
  `tools/check-translations.py` fails when the English side has moved past
  it, naming the commits to port.

`05-bilingual-maintenance.md` has the layout, the workflow, the CI wiring and
the criteria for retiring a translation.

---

## 8. Pre-existing defects surfaced by the translation (not fixed here)

Reading every line closely turns up content bugs. These were translated
faithfully rather than silently corrected, so that the translation diff stays
a translation diff. Each deserves its own commit:

1. **`docs/01-dockerfile-lfc.md`, "Automatic detection: simulation vs. FPGA"
   is wrong twice over.** It states that the Makefile greps the `.lf` for the
   string `fpgaboard` — `tools/Makefile` does no such grep; the mode is
   derived purely from `ARCH`. And the polarity is inverted: it says
   "contains `fpgaboard` → simulation", when `fpgaBoard` present is exactly
   the FPGA case. The `README` §5.1 describes the real behaviour. Either
   implement the grep or rewrite that section.
2. **`check-string` cannot ever fail.** Because `MODE` is computed from
   `ARCH`, the `ARCH != x86_64` / `ARCH != arm` branches are unreachable, and
   the messages ("file indicates simulation mode…") claim a file inspection
   that never happens. The guard provides no protection today.
3. **`README.md`'s directory tree listed four `docs/` files that do not
   exist** (`01-lingua-franca-chisel.md`, and `02`/`03`/`04` shifted by one).
   Corrected to the real filenames while translating, since leaving it would
   have meant translating a wrong list.
4. **`docs/01` linked `./CHANGELOG.md`** from inside `docs/`, where there is
   no such file. Fixed to `../CHANGELOG.md`.
5. **`docs/02`'s table of contents numbered two entries "9"** ("Deploy na
   FPGA" and "Links Úteis"). Renumbered to 9 and 10.
6. **`docs/03` jumps from section 6 to section 8** — section 7 does not
   exist. Left as is: renumbering would break every cross-reference in the
   document.
7. **`docs/03`'s title and §1 describe the goal as `PYNQZCU104CC`**, while
   the document actually delivers `ZCU106`. Historical accident of how the
   document grew; harmless but confusing on first read.
8. **`docs/03` §8.2 shows `ZCU106Wrapper.scala` with the legacy
   `platform-zedboard-linux.cpp` driver list**, which §8.3 then replaces.
   Correct as a chronological record, misleading if read as current state.
9. **`ZCU106Wrapper`/`PYNQZCU104CCWrapper`/`TestboardWrapper` all extend
   `AXIPlatformWrapper(PYNQZCU104Params, …)`** — they define their own
   `*Params` object and then do not use it. Harmless today (all three chips
   are identical) and a trap for the next board that is not.
