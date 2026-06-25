# Lingua Franca + Chisel

> Guia de instalação, configuração e resolução de problemas para executar o gerador Chisel no Lingua Franca.

---

## Índice

- [Pré-requisitos](#pré-requisitos)
- [Instalação](#instalação)
- [Problemas Conhecidos](#problemas-conhecidos)
  - [Problema 1 — Submódulos com SSH](#problema-1--submódulos-com-ssh)
  - [Problema 2 — Versões incompatíveis de Scala e Chisel](#problema-2--versões-incompatíveis-de-scala-e-chisel)
  - [Problema 3 — API depreciada em PlatformWrapper.scala](#problema-3--api-depreciada-em-platformwrapperscala)
- [Build e Execução](#build-e-execução)
- [Referências](#referências)

---

## Pré-requisitos

| Ferramenta | Versão recomendada |
|---|---|
| Java (OpenJDK) | 17 |
| Scala | 2.13.10 |
| sbt | 2.0.0+ |

### Java

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### Scala via SDKMAN

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install scala 2.13.10
```

> **Dica (zsh):** Para que o SDKMAN seja carregado automaticamente, adicione ao `~/.zshrc`:
> ```bash
> export SDKMAN_DIR="$HOME/.sdkman"
> [[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]] && source "$HOME/.sdkman/bin/sdkman-init.sh"
> ```

---

## Instalação

Clone o repositório na branch `chisel3` com todos os submódulos:

```bash
git clone https://github.com/erlingrj/lingua-franca.git \
  --branch chisel3 \
  --recursive
cd lingua-franca
```

---

## Problemas Conhecidos

### Problema 1 — Submódulos com SSH

Alguns submódulos usam URLs `git@github.com` (SSH), o que exige acesso de membro ao repositório. O script abaixo força o uso de HTTPS e reinicializa os submódulos:

```bash
#!/bin/bash

echo "[1] Forçando HTTPS ao invés de SSH..."
git config --global url."https://github.com/".insteadOf git@github.com:

echo "[2] Limpando estado antigo de submódulos..."
git submodule deinit -f --all || true
rm -rf .git/modules || true

echo "[3] Sincronizando .gitmodules..."
git submodule sync --recursive

echo "[4] Atualizando submódulos (recursivo)..."
git submodule update --init --recursive --remote

echo "[OK] Submódulos atualizados com sucesso!"
```

---

### Problema 2 — Versões incompatíveis de Scala e Chisel

Atualize as versões nos arquivos abaixo antes de executar o build:

| Arquivo | Campo | De | Para |
|---|---|---|---|
| `core/src/main/kotlin/org/lflang/generator/chisel/ChiselSbtGenerator.kt` | scala | `2.13.8` | `2.13.10` |
| `core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/build.sbt` | scala / chisel | `2.13.8` / `3.5.6` | `2.13.10` / `3.6.0` |
| `core/src/main/resources/lib/chisel/reactor-chisel/build.sbt` | scala / chisel | `2.13.8` / `3.5.6` | `2.13.10` / `3.6.0` |
| `core/src/main/resources/lib/chisel/reactor-chisel/build.sc` | scala / chisel | `2.13.8` / `3.5.6` | `2.13.10` / `3.6.0` |

Para localizar todas as ocorrências antes de editar:

```bash
grep -R "2.13.8" -n .
```

Para editar cada arquivo:

```bash
vim core/src/main/kotlin/org/lflang/generator/chisel/ChiselSbtGenerator.kt
vim core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/build.sbt
vim core/src/main/resources/lib/chisel/reactor-chisel/build.sbt
vim core/src/main/resources/lib/chisel/reactor-chisel/build.sc
```

---

### Problema 3 — API depreciada em `PlatformWrapper.scala`

**Arquivo:**
```
core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala
```

Na **linha 243**, remova os parênteses do método `asUInt`:

```scala
// Antes (depreciado)
val bits = element.asUInt()

// Depois (correto)
val bits = element.asUInt
```

---

## Build e Execução

### 1. Rodar os testes de integração

```bash
./gradlew targetTest -Ptarget=Chisel    # Testes do target Chisel
./gradlew targetTest -Ptarget=Codesign  # Testes do target Codesign
```

### 2. Gerar o executável `lfc`

```bash
./gradlew assemble
```

O executável será gerado em `build/install/lf-cli/bin/lfc`. Adicione-o ao PATH:

```bash
export PATH="$PWD/build/install/lf-cli/bin:$PATH"
```

### 3. Compilar um programa `.lf`

```bash
lfc ./src/HelloWorld.lf
```

---

## Referências

### Cursos e tutoriais

- [CSE 228A — UCSC Winter 2023](https://classes.soe.ucsc.edu/cse228a/Winter23)
- [Getting Started with Chisel — Instructables](https://www.instructables.com/Getting-Started-With-Chisel)
- [Learning Chisel — vmois.dev](https://vmois.dev/learning-chisel)
- [Introduction to Chisel — Qiita (JP)](https://qiita.com/Kosuke_Matsui/items/80dde1219e3ce02c4b66)

### Referências rápidas

- [Chisel Cheatsheet — Duke University](https://users.cs.duke.edu/~lkw34/Chisel-Cheatsheet.pdf)
- [Chisel em 30 minutos (PDF)](https://wiki.f-si.org/images/a/a0/Chisel-30min.pdf)

### Artigos e apresentações

- [Chisel: Constructing Hardware in a Scala Embedded Language — ASPIRE/Berkeley](https://aspire.eecs.berkeley.edu/projects/chisel-constructing-hardware-in-a-scala-embedded-language)
- [FPGA-SoC Workshop — CERN (PDF)](https://indico.cern.ch/event/1380885/attachments/2831544/4947319/FPGA-SoC%20workshop%20CERN.pdf)
- [FPGA-Based Reactor Protection Systems — Sandia National Labs (PDF)](https://www.sandia.gov/app/uploads/sites/273/2024/11/Field-Programmable-Gate-Array-Based-Reactor-Protection-Systems-and-Potential-for-Inclusion-of-Secure-Elements-to-Improve-Cyberse.pdf)
