# docs/ — source documentation

> 🇬🇧 **English** (canonical) · [🇧🇷 Português](pt-BR/README.md)

| File | What it covers |
|---|---|
| `01-dockerfile-lfc.md` | The image's 13 "read before using" items, the smoke tests, the Makefile, SSH, bind mounts. |
| `02-petalinux-vivado.md` | The other side of the bridge: Vivado → XSA → SDT → PetaLinux → boot via JTAG/USB stick → deploying the `.tar` onto the board. |
| `03-backlog-generalization.md` | The engineering record: root cause, Phases 1–3 of the generalization, the `TESTBOARD` test, the coverage matrix and the `clock-period` discovery. |
| `04-translation-strategy.md` | How the pt-BR → English translation was done and how to land it: scope, commit sequence, the `PROJETO`/`PROJECT` alias, the duplicated Dockerfile payloads, CI guards. |
| `05-bilingual-maintenance.md` | How the English and Portuguese versions are kept in step: layout, provenance markers, `tools/check-translations.py`, workflow and CI. |

Portuguese counterparts of the documents above are in
[`pt-BR/`](pt-BR/README.md). English is the canonical version; `04` and `05`
are addressed to maintainers and are English-only.

For "what was done", start from the `README.md` at the root; for "how we got
there", read `03-backlog-generalization.md`.
