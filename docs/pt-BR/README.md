# docs/ — documentação de origem

> 🇧🇷 **Português** · [🇬🇧 English](../README.md) — a versão em inglês é a canônica.

<!-- translated-from: docs/README.md@0ad06220c587dbc56eca15e4552068f564aec71c -->

| Arquivo | O que cobre |
|---|---|
| `01-dockerfile-lfc.md` | Os 13 itens de "leia antes de usar" da imagem, os smoke tests, o Makefile, SSH, bind mounts. |
| `02-petalinux-vivado.md` | O outro lado da ponte: Vivado → XSA → SDT → PetaLinux → boot via JTAG/pen drive → deploy do `.tar` na placa. |
| `03-backlog-generalization.md` | O registro de engenharia: causa raiz, Fases 1–3 da generalização, teste `TESTBOARD`, matriz de cobertura e a descoberta do `clock-period`. |

Para "o que foi feito", comece pelo `README.md` da raiz; para "como se chegou lá", leia o `03-backlog-generalization.md`.
