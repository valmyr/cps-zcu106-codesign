# Lingua Franca em FPGA

> Hardware e software descritos no mesmo nível de abstração:
> reatores com concorrência determinística, de um lado e do outro
> da fronteira PL/PS de um Zynq UltraScale+.

Suporte nativo à placa **Xilinx ZCU106** no compilador Lingua Franca
(fork `erlingrj/lingua-franca`, branch `chisel3`, target `Codesign`).
Um único `.lf` descreve *hardware reactors*, sintetizados via
`reactor-chisel` para a lógica programável, e reatores de software,
compilados em C++ para o processador — um comando gera, cross-compila
e empacota os dois.

Baseado em *Codesign of Reactor-Oriented Hardware and Software for
Cyber-Physical Systems* (Jellum, Schoeberl, Lee e Orlandic, ACM TRETS
17(4), 2024). Este repositório contribui com a portabilidade para a
ZCU106 e com a automação do fluxo de build.
---

## 1. O que foi feito

Antes, gerar hardware + software para uma placa real exigia reaplicar patches manuais em Scala a cada projeto novo, mais dois comandos separados de compilação. Agora:

```bash
lfc --target-compiler aarch64-linux-gnu-g++ meu_teste.lf
```

gera o RTL (`ZCU106Wrapper.v`), compila o driver (`lfFPGA.a`) e cross-compila o software (`ELF … ARM aarch64`) — **num único comando, sem nenhuma edição manual.**

---

## 2. Estrutura do repositório

```
Cyber_PhysicalSystems/
├── README.md                     <- o que foi feito
├── CHANGELOG.md                  <- histórico cronológico das correções
├── LICENSE
│
├── docker/
│   ├── lingua-franca/
│   │   └── Dockerfile            <- compilador lfc com as correções + smoke test
│   └── petalinux/
│       └── Dockerfile            <- ambiente de build do PetaLinux 2025.2
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
│       ├── patch_CodesignGenerator.py      (compila software para qualquer placa)
│       └── patch_CodesignSwEmitter.py      (propaga --target-compiler)
│
├── tools/
│   └── Makefile                  <- compilar/empacotar, com guarda simulação ou FPGA
│
├── examples/
│   ├── loopback_sim.lf                                       (simulação)
│   ├── smoke4.lf                                             (ZCU106, smoke test)
│   ├── ExplorationMultipleReactorsCodesignAccelsOpBasics.lf  (ZCU106, 3 aceleradores)
│   ├── ExplorationMultipleReactoresCodesign.lf
│   └── led.lf
│
├── docs/
│   ├── 01-lingua-franca-chisel.md    (instalação manual, fora do Docker)
│   ├── 02-dockerfile-lfc.md          (13 itens de "leia antes de usar")
│   ├── 03-petalinux-vivado.md        (Vivado → XSA → SDT → PetaLinux → boot → deploy)
│   └── 04-backlog-generalizacao.md   (como se chegou lá, fase a fase)
│
├── references/                   <- artigos, relatórios e material de apoio
└── images/                       <- capturas usadas pelos tutoriais
```

Os arquivos em `patches/` são **exatamente** o conteúdo que o `docker/lingua-franca/Dockerfile` injeta via heredoc.

---

## 3. As correções, em ordem de dependência

| # | Problema | Correção | Arquivo |
|---|---|---|---|
| 1 | Submódulos em SSH, sem chave no container | `.gitmodules` → HTTPS + `insteadOf` global (cobre o aninhado) | `docker/lingua-franca/Dockerfile` |
| 2 | `chisel3`/`chiseltest` desalinhados em 4 arquivos | `sed` por regex de patch-version → dupla 3.6.x / 0.6.x | `docker/lingua-franca/Dockerfile` |
| 3 | `.asUInt()` depreciado | `sed` → `.asUInt` | `docker/lingua-franca/Dockerfile` |
| 4 | Colisão de nome `csr` na elaboração | `Wire` interno renomeado para `csrSlave` | `patches/fpga-tidbits/axi/AXIPlatformWrapper.scala` |
| 5 | Wrapper nunca copiava os arquivos de driver | `generateRegDriver` + `resourceCopyBulk` | `patches/fpga-tidbits/axi/PYNQZCU104CCWrapper.scala` |
| 6 | `xlnk` não existe em kernels `linux-xlnx` modernos | driver canônico via **UIO** (`mmap` de `/dev/uioN`) | `patches/fpga-tidbits/cpp/uiodriver.hpp` |
| 7 | Driver hardcoded só para `VerilatedTester` | campos `platformMainDriverFile` / `platformDriverExtraFlags` lidos da instância do wrapper | `patch_PlatformWrapper.py` + `reactor-chisel/Main.scala` |
| 8 | Sem identidade para a ZCU106 | wrapper próprio + registro em `platformMap`/`fpgaPartMap` | `ZCU106Wrapper.scala` + `patch_Main_platformMap.py` |
| 9 | Software só compilava para simulação | condição removida | `patch_CodesignGenerator.py` |
| 10 | `--target-compiler` perdido na 2ª geração interna | escreve `compiler:` no `_SwTop.lf` gerado | `patch_CodesignSwEmitter.py` |

**Generalização para novas placas Xilinx (#7):** adicionar uma placa nova custa 1 arquivo de wrapper + 2 linhas de registro. Validado com a placa fictícia `TESTBOARD`, sem tocar em `mainCodesign`.

---

## 4. Fluxo

```
┌─ DOCKER · lf-chisel ───────────────────────────────────────────────────┐
│                                                                        │
│   smoke_test.lf                                                        │
│        │  make deploy PROJETO=smoke_test.lf ARCH=arm                   │
│        ▼                                                               │
│        ├──► hardware (Chisel) ─► _FpgaTop ─┬─► ZCU106Wrapper.v         │
│        │                                   │                           │
│        │                                   └─► lfFPGA.a                │
│        │                                       (UioDriver, ARM64)      │
│        │                                            │                  │
│        └──► software (C++) ─► bin/ ◄───── linka ────┘                  │
│                               (ELF ARM aarch64)                        │
│                                      │                                 │
│                                      ▼                                 │
│                    smoke_test<timestamp>_deploy.tar.gz                 │
│                      ├── ZCU106Wrapper.v                               │
│                      ├── Q_srl.v                                       │
│                      ├── lib/libreactor-cpp-default.so*                │
│                      ├── bin/smoke_test                                │
│                      └── smoke_testSNAPSHOT_<timestamp>.lf             │
└────────────────────────────────────│───────────────────────────────────┘
                                     │  bind mount / docker cp
                                     ▼
                         ┌────────────────────────────┐
                         │        .tar.gz no host     │
                         └───┬────────────────────┬───┘
         ZCU106Wrapper.v     │                    │   bin/ + lib/  (scp)
         Q_srl.v             │                    │   
                             ▼                    │
    ┌─ HOST · Vivado + Vitis (licenciados) ──┐    │
    │   block design + síntese               │    │
    │        ├──► bitstream ───────────────┐ │    │
    │        │                             │ │    │
    │        └──► XSA ──xsct/sdtgen──► SDT │ │    │
    └──────────────────────────│───────────│─┘    │
                               │ SDT       │      │
                               ▼           │      │
         ┌─ DOCKER · petalinux ──────────┐ │      │
         │  petalinux-config             │ │      │
         │   + uio_pdrv_genirq           │ │      │
         │     .of_id=generic-uio        │ │      │
         │   + compatible = "generic-uio"│ │      │
         │        │                      │ │      │
         │  petalinux-build              │ │      │
         │        │                      │ │      │
         │        ▼                      │ │      │
         │  images/linux/                │ │      │
         │   ├── image.ub                │ │      │
         │   └── rootfs.cpio.gz.u-boot   │ │      │
         └────────│──────────────────────┘ │      │
                  │  pen drive →           │      │
                  │  fatload + bootm       │      │
                  ▼                        ▼      ▼
         ┌───────────────────────────────────────────────┐
         │  PLACA   ZCU106 — PL + PetaLinux              │
         │   xilinx-zcu106-2025.2:~$                     │
         │   export LD_LIBRARY_PATH=./lib                │
         │   ./bin/smoke_test  →  /dev/uioN → mmap       │
         └───────────────────────────────────────────────┘
```

Simule primeiro, sem a diretiva `fpgaBoard`; adicione-a só depois que a lógica estiver validada (ver 5.1). Isso isola bug de lógica de bug de plataforma.

---

## 5. Como rodar

```bash
cd docker/lingua-franca && docker build -t lf-chisel .
docker run -it --rm -v "$(pwd)/../../examples:/workspace" lf-chisel
```

Dentro do container:

```bash
cd /workspace
lfc --target-compiler aarch64-linux-gnu-g++ smoke4.lf
file codesign-gen/smoke4/bin/_SwTop        # ELF 64-bit … ARM aarch64
```

Requisitos: ≥15 GB de disco e ≥8 GB de RAM para o `docker build`.

O container roda como `lingua-franca` (senha `1234`), com `WORKDIR` em `/home/lingua-franca`, onde já estão o `Makefile` e um `.lf` de exemplo. A primeira compilação Codesign de cada container baixa as dependências do `sbt` e leva alguns minutos. Para detalhes — bind mounts para os artefatos de deploy, acesso SSH, onde ficam os testes do fork — veja [`docs/01-dockerfile-lfc.md`](docs/01-dockerfile-lfc.md).

### 5.1 Simulação vs. FPGA

Também é possível compilar pelo `Makefile` (`tools/Makefile`, já presente em `~/Makefile` na imagem). O modo é determinado por **uma única diretiva no `.lf`** — e o `ARCH` passado ao `make` precisa combinar com ela.

| Modo | No `target Codesign` | `ARCH` | Compilador |
|---|---|---|---|
| **FPGA** (hardware real) | `fpgaBoard: ZCU106` **presente** | `arm` | `aarch64-linux-gnu-g++` |
| **Simulação** (Verilator) | `fpgaBoard` **ausente** | `x86_64` | `g++` nativo |

> **Atenção:** para simular, a diretiva `fpgaBoard` precisa ser **removida** — não basta trocar o valor. Com ela presente, o `lfc` gera o `lfFPGA.a` cross-compilado para ARM64 e tenta linkar o software contra ele; num binário x86 isso falha no linker (`Relocations in generic ELF (EM: 183)` / `error adding symbols: file in wrong format`).

```bash
# FPGA — o .lf precisa ter `fpgaBoard: ZCU106`
make deploy PROJETO=smoke4.lf ARCH=arm
# compila hardware + software e empacota smoke4_<timestamp>_deploy.tar.gz

# Simulação — o .lf NÃO pode ter a linha `fpgaBoard`
make compiler PROJETO=loopback_sim.lf ARCH=x86_64
# compila e roda local; não empacota (não há bitstream a entregar)
```

---

## 6. Testes de validação

| # | Valida | Onde roda | Status |
|---|---|---|---|
| 1 | Lógica do `UioDriver` contra um `/sys` falso (round-trip de registradores, busca por nome) | `docker build` | Pass |
| 2 | Simulação ponta a ponta, assinatura `aeef7c29` do `CodesignTopReactor` | `docker build` | Pass |
| 3 | `lfFPGA.a` para `ZCU106` contendo `platform-mpsoc-uio.o`, sem warnings | `docker build` | Pass |
| 4 | Compilação automática do software: `ELF … ARM aarch64` com um único comando | `docker build` | Pass |
| 5 | Execução na placa física (bitstream + device tree) | fora do Docker | Pass |

Falha em qualquer um dos testes 1 a 4 impede a imagem de ser construída, em vez de o problema só aparecer na primeira vez que alguém usar o container.

O teste 5 não roda no `docker build`: ele exige criar o projeto no Vivado, gerar o XSA e compilar o PetaLinux. O procedimento completo está em [`docs/02-petalinux-vivado.md`](docs/02-petalinux-vivado.md).

---

## 7. Pendências

Dentro do compilador e da imagem, nenhuma bloqueante. Fora deles:

- Reset do `ZCU106Wrapper`: ligar em `peripheral_reset` (ativo-alto), não em `peripheral_aresetn` — conexão manual no Block Design.
- `clock-period` **tem** que ser declarado no `.lf` antes de qualquer síntese: o default (1 nsec ≙ 1 GHz) dessincroniza a coordenação de tags do clock físico.
- Overlay de device tree `generic-uio` + bootarg `uio_pdrv_genirq.of_id=generic-uio` no PetaLinux.
- Bug em investigação: `logicalTagComplete` trava em `Tag.FOREVER` acima de ~2,15 s de tempo lógico (suspeita de comparação com sinal).
- GCC 11 / AArch64: *internal compiler error* intermitente em `std::regex` ao compilar `main.cc`.
