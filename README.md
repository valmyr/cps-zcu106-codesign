# Alterações realizadas para funcionar

### Instale/Execute:

```
curl -s "https://get.sdkman.io" | bash

source "$HOME/.sdkman/bin/sdkman-init.sh"

sdk install scala 2.13.10

```

### Erro 0: Clone

#### Problemas nos submodulos, algumas referências url foram definidas como git@github tornado necessário ser membro do repositório para realizar o clone

###### Use o seguinte script para resolver

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

### Erro 1: Versões de softwares

##### Para os seguintes arquivos dos respectivos caminhos

```bash
vim ./core/src/main/kotlin/org/lflang/generator/chisel/ChiselSbtGenerator.kt
vim ./core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/build.sbt
vim ./core/src/main/resources/lib/chisel/reactor-chisel/build.sbt
vim ./core/src/main/resources/lib/chisel/reactor-chisel/build.sc


Altere a versão do scala de 2.13.8 -> 2.13.10
                |
                |  chisel de 3.5.6 -> 2.6.0

```

### Erro 2: Para os seguintes arquivos
```bash

~/work/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala

linha 254 - val bits = element.asUInt() altere para val bits = element.asUInt
```


```
../bin/lfc ./src/HelloWorld.lf
```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

## Links úteis

https://classes.soe.ucsc.edu/cse228a/Winter23

https://aspire.eecs.berkeley.edu/projects/chisel-constructing-hardware-in-a-scala-embedded-language

https://users.cs.duke.edu/~lkw34/Chisel-Cheatsheet.pdf

https://www.instructables.com/Getting-Started-With-Chisel

https://vmois.dev/learning-chisel

https://qiita.com/Kosuke_Matsui/items/80dde1219e3ce02c4b66

https://indico.cern.ch/event/1380885/attachments/2831544/4947319/FPGA-SoC%20workshop%20CERN.pdf

https://wiki.f-si.org/images/a/a0/Chisel-30min.pdf

https://www.sandia.gov/app/uploads/sites/273/2024/11/Field-Programmable-Gate-Array-Based-Reactor-Protection-Systems-and-Potential-for-Inclusion-of-Secure-Elements-to-Improve-Cyberse.pdf

https://indico.cern.ch/event/1380885/attachments/2831544/4947319/FPGA-SoC%20workshop%20CERN.pdf

