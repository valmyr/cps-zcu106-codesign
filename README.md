# Codesign Lingua Franca → ZCU106

> Suporte nativo à placa **Xilinx ZCU106** no compilador Lingua Franca (fork `erlingrj/lingua-franca`, branch `chisel3`, target `Codesign`), empacotado em uma imagem Docker reprodutível, do `.lf` ao binário ARM64 rodando sobre PetaLinux.

**Estado:** 4 dos 5 níveis de teste validados sem placa física; execução em hardware real pendente (bitstream Vivado + overlay de device tree).

---

## 1. O que foi feito, em uma frase

Antes, gerar hardware+software para uma placa real exigia reaplicar patches manuais em Scala a cada projeto novo e dois comandos separados de compilação. Agora:

```bash
lfc --target-compiler aarch64-linux-gnu-g++ meu_teste.lf
```

gera o RTL (`ZCU106Wrapper.v`), compila o driver (`lfFPGA.a`) e cross-compila o software (`ELF ... ARM aarch64`) — **num único comando, sem nenhuma edição manual.**

---

## 2. Estrutura do repositório

```
cps-zcu106-codesign/
├── README.md                     <- este arquivo (o "o que foi feito")
├── CHANGELOG.md                  <- histórico cronológico das correções
│
├── docker/
│   └── Dockerfile                <- imagem de build + 4 smoke tests embutidos
│
├── patches/                      <- AS CORREÇÕES, isoladas do Dockerfile
│   ├── reactor-chisel/
│   │   └── Main.scala                      (mainCodesign genérico — peça central)
│   ├── fpga-tidbits/
│   │   ├── axi/ZCU106Wrapper.scala         (placa nova, identidade própria)
│   │   ├── axi/PYNQZCU104CCWrapper.scala   (cópia de driver + -fpermissive)
│   │   ├── axi/AXIPlatformWrapper.scala    (colisão de nome `csr`)
│   │   ├── cpp/uiodriver.hpp               (driver UIO 64 bits, testável)
│   │   ├── cpp/platform-mpsoc-uio.cpp      (initPlatform/deinitPlatform)
│   │   ├── patch_PlatformWrapper.py        (campos genéricos de driver)
│   │   └── patch_Main_platformMap.py       (registro da ZCU106)
│   └── compiler-kotlin/
│       ├── patch_CodesignGenerator.py      (compila software p/ qualquer placa)
│       └── patch_CodesignSwEmitter.py      (propaga --target-compiler)
│
├── tools/
│   └── Makefile                  <- compilar/empacotar, com guarda sim↔FPGA
│
├── examples/
│   ├── loopback_sim.lf                                   (VerilatedTester)
│   ├── smoke4.lf                                         (ZCU106, smoke test)
│   └── ExplorationMultipleReactorsCodesignAccelsOpBasics.lf  (3 aceleradores)
│
└── docs/
    ├── 01-lingua-franca-chisel.md    (instalação manual, fora do Docker)
    ├── 02-dockerfile-lfc.md          (13 itens de "leia antes de usar")
    ├── 03-petalinux-vivado.md        (XSA → SDT → PetaLinux → boot → deploy)
    └── 04-backlog-generalizacao.md   (como se chegou lá, fase a fase)
```

Os arquivos em `patches/` são **exatamente** o conteúdo que o `docker/Dockerfile` injeta via heredoc — extraídos para cá para que se possa ler, revisar e versionar a correção em si, em vez de garimpá-la dentro de um `RUN cat >`.

---

## 3. As correções, em ordem de dependência

| # | Problema | Correção | Arquivo |
|---|---|---|---|
| 1 | Submódulos em SSH, sem chave no container | `.gitmodules` → HTTPS + `insteadOf` global (cobre o aninhado) | `docker/Dockerfile` |
| 2 | `chisel3`/`chiseltest` desalinhados em 4 arquivos | `sed` por regex de patch-version → dupla 3.6.x / 0.6.x | `docker/Dockerfile` |
| 3 | `.asUInt()` depreciado | `sed` → `.asUInt` | `docker/Dockerfile` |
| 4 | Colisão de nome `csr` na elaboração | `Wire` interno renomeado p/ `csrSlave` | `patches/fpga-tidbits/axi/AXIPlatformWrapper.scala` |
| 5 | Wrapper nunca copiava os arquivos de driver | `generateRegDriver` + `resourceCopyBulk` | `patches/fpga-tidbits/axi/PYNQZCU104CCWrapper.scala` |
| 6 | `xlnk` não existe em kernels `linux-xlnx` modernos | driver canônico via **UIO** (`mmap` de `/dev/uioN`) | `patches/fpga-tidbits/cpp/uiodriver.hpp` |
| 7 | Driver hardcoded só para `VerilatedTester` | campos `platformMainDriverFile` / `platformDriverExtraFlags` lidos da instância do wrapper | `patch_PlatformWrapper.py` + `reactor-chisel/Main.scala` |
| 8 | Sem identidade para a ZCU106 | wrapper próprio + registro em `platformMap`/`fpgaPartMap` | `ZCU106Wrapper.scala` + `patch_Main_platformMap.py` |
| 9 | Software só compilava para simulação | condição removida | `patch_CodesignGenerator.py` |
| 10 | `--target-compiler` perdido na 2ª geração interna | escreve `compiler:` no `_SwTop.lf` gerado | `patch_CodesignSwEmitter.py` |

**Critério de generalização atingido (#7):** adicionar uma placa nova = 1 arquivo de wrapper + 2 linhas de registro. Provado com a placa fictícia `TESTBOARD`, sem tocar em `mainCodesign`.

---

## 4. Fluxo completo

```
    .lf  ──lfc──┬──► hardware (Chisel) ──► ZCU106Wrapper.v ──► Vivado ──► bitstream
                │                      └─► lfFPGA.a (UioDriver, ARM64)
                └──► software (C++)  ──► _SwTop (ELF ARM aarch64) ─┐
                                                                   │ linka
         Vivado ──► XSA ──sdtgen──► SDT ──► PetaLinux ──► image.ub ─┤
                                     + uio_pdrv_genirq.of_id=generic-uio
                                     + compatible = "generic-uio"
                                                                   ▼
                                                      ZCU106: /dev/uioN → mmap
```

Simular primeiro (`fpgaBoard: VerilatedTester`), trocar só essa linha para `ZCU106` depois — isola bug de lógica de bug de plataforma.

---

## 5. Como rodar

```bash
cd docker && docker build -t lf-chisel .          # 4 smoke tests rodam no build
docker run -it --rm -v "$(pwd)/../examples:/workspace" lf-chisel
```

Dentro do container:

```bash
cd /workspace
lfc --target-compiler aarch64-linux-gnu-g++ smoke4.lf
file codesign-gen/smoke4/bin/_SwTop        # ELF 64-bit ... ARM aarch64
```

Ou via Makefile (`tools/Makefile`, já presente em `~/Makefile` na imagem):

```bash
make deploy PROJETO=smoke4.lf ARCH=arm        # compila + empacota .tar.gz
make compiler PROJETO=loopback_sim.lf ARCH=x86_64   # simulação
```

Requisitos: ≥15 GB de disco e ≥8 GB de RAM para o `docker build`.

---

## 6. Smoke tests (rodam durante o `docker build`)

| # | Valida | Sem hardware |
|---|---|---|
| 1 | Lógica do `UioDriver` contra `/sys` falso (round-trip de registradores, busca por nome) | ✅ |
| 2 | Simulação `VerilatedTester` ponta a ponta, assinatura `aeef7c29` | ✅ |
| 3 | `lfFPGA.a` para `ZCU106` contendo `platform-mpsoc-uio.o`, sem warnings | ✅ |
| 4 | Compilação automática do software: `ELF ... ARM aarch64` com um comando | ✅ |
| — | Execução na placa física (bitstream + device tree) | ⏳ pendente |

Se qualquer um falhar, a imagem não é construída.

---

## 7. Pendências

Dentro do compilador/imagem: nenhuma bloqueante. Fora dele:

- Reset do `ZCU106Wrapper` → ligar em `peripheral_reset` (ativo-alto), não `peripheral_aresetn` — conexão manual no Block Design.
- `clock-period` **tem** que ser declarado no `.lf` antes de qualquer síntese: o default (1 nsec ≙ 1 GHz) dessincroniza a coordenação de tags do clock físico.
- Overlay de device tree `generic-uio` + bootarg `uio_pdrv_genirq.of_id=generic-uio` no PetaLinux.
- Bug em investigação: `logicalTagComplete` trava em `Tag.FOREVER` acima de ~2,15 s de tempo lógico (suspeita de comparação com sinal).
- GCC 11 / AArch64: *internal compiler error* intermitente em `std::regex` ao compilar `main.cc`.
