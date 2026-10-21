# docs/ — documentação de origem

| Arquivo | O que cobre |
|---|---|
| `01-lingua-franca-chisel.md` | Instalação e build manuais (fora do Docker): pré-requisitos, os 3 problemas conhecidos, `./gradlew assemble`. Útil para quem não vai usar o container. |
| `02-dockerfile-lfc.md` | Os 13 itens de "leia antes de usar" da imagem, os smoke tests, o Makefile, SSH, bind mounts. |
| `03-petalinux-vivado.md` | O outro lado da ponte: Vivado → XSA → SDT → PetaLinux → boot via JTAG/pen drive → deploy do `.tar` na placa. |
| `04-backlog-generalizacao.md` | O registro de engenharia: causa raiz, Fases 1–3 da generalização, teste `TESTBOARD`, matriz de cobertura e a descoberta do `clock-period`. |

Para "o que foi feito", comece pelo `README.md` da raiz; para "como se chegou lá", leia o 04.
