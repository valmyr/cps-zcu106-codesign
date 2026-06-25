# Lingua Franca + Chisel — Setup Guide

Guia de instalação e resolução de problemas para executar o gerador Chisel no Lingua Franca.

---

## Pré-requisitos

### Instalar Scala via SDKMAN

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install scala 2.13.10
```

### Configurar Java

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

---

## Problemas Conhecidos e Soluções

### Problema 1 — Clone: submódulos com SSH

As URLs de alguns submódulos estão configuradas com `git@github.com` (SSH), o que exige ser membro do repositório. Execute o script abaixo para forçar HTTPS e reconfigurar os submódulos:

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

Nos arquivos abaixo, atualize as versões conforme indicado. Em seguida, execute o comando, aguarde a ocorrência de eventuais erros e corrija os arquivos, se necessário.

```
git clone https://github.com/erlingrj/lingua-franca.git --branch chisel3 --recursive && cd lingua-franca # Clone repo and check out the right branch
./gradlew targetTest -Ptarget=Chisel # Run the set of Chisel-target integration tests
./gradlew targetTest -Ptarget=Codesign # Run the set of Codesign-target integration tests

```


grep -R "2.13.8" -n .
| Arquivo | Campo | De | Para |
|---|---|---|---|
| `core/src/main/kotlin/org/lflang/generator/chisel/ChiselSbtGenerator.kt` | scala | `2.13.8` | `2.13.10` |
| `core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/build.sbt` | scala / chisel | `2.13.8` / `3.5.6` | `2.13.10` / `3.6.0` |
| `core/src/main/resources/lib/chisel/reactor-chisel/build.sbt` | scala / chisel | `2.13.8` / `3.5.6` | `2.13.10` / `3.6.0` |
| `core/src/main/resources/lib/chisel/reactor-chisel/build.sc` | scala / chisel | `2.13.8` / `3.5.6` | `2.13.10` / `3.6.0` |

Para editar:

```bash
vim core/src/main/kotlin/org/lflang/generator/chisel/ChiselSbtGenerator.kt
vim core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/build.sbt
vim core/src/main/resources/lib/chisel/reactor-chisel/build.sbt
vim core/src/main/resources/lib/chisel/reactor-chisel/build.sc
```

---

### Problema 3 — API depreciada em `PlatformWrapper.scala`

Arquivo:
```
core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala
```

Na **linha 243**, substitua:

```scala
// Antes
val bits = element.asUInt()

// Depois
val bits = element.asUInt
```

---

## Compilar e executar

```bash
../bin/lfc ./src/HelloWorld.lf
```

---

## Referências e Materiais de Estudo

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
- [FPGA-Based Reactor Protection Systems — Sandia National Laboratories (PDF)](https://www.sandia.gov/app/uploads/sites/273/2024/11/Field-Programmable-Gate-Array-Based-Reactor-Protection-Systems-and-Potential-for-Inclusion-of-Secure-Elements-to-Improve-Cyberse.pdf)
