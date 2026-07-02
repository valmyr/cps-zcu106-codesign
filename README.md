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


### 1. Gerar o executável `lfc`

```bash
./gradlew assemble
```

O executável será gerado em `build/install/lf-cli/bin/lfc`. Adicione-o ao PATH:

```bash
export PATH="$PWD/build/install/lf-cli/bin:$PATH"
```

### 2. Compilar um programa `.lf`

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


# Linux Embarcado - PetaLinux Xilinix

## Source Vitis 
```
source<Diretório de Instalação Vitis>/settings.sh
```
#Crie o script sdt.tcl

```bash
touch sdt.scl
```

```bash
set outdir [lindex $argv 1]
set xsa [lindex $argv 0]
exec rm -rf $outdir
sdtgen set_dt_param -xsa $xsa -dir $outdir -board_dts zcu106-reva
sdtgen generate_sdt
```


## XSCT Flow
```bash
xsct sdt.tcl <Projeto Vivado>/<Diretório do arquivo XSA (Xilinx Support Archive)> sdt_outdir
```

Mais detalhes em: [Conversão de Fluxo XSA para SDT](https://github.com/Xilinx/system-device-tree-xlnx/blob/master/README.md)


## Carregar as definições necesárias para usar o PetaLinux (Obs: Em outro terminal)
```bash
source <Diretório de instalação do PetaLinux>/settings.sh
```


## Criar o projeto do Linux Embarcado baseado no Board Support Package da Zynq ZCU106
```bash 
petalinux-create project -s <Diretório do arquivo BSP>
```


## Incorporando às caracteristicas do hardware ao PetaLinux

```bash 
cd xilinx-zcu106-20xx.x
```

```bash 
petalinux-config --get-hw-description ../sdt_outdir
```

```bash 
petalinux-config -c rootfs

```


```bash
petalinux-build
```
## Copiar o conteúdo de ps.dtsi para system-user.dtsi 
```bash 
sdt_outdir/pl.dtsi
```
```bash 
code project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi
```

## Boot pelo Pen Drive
## Passo 0:  Abra o monitor serial para a UART de índice zero
```bash 
sudo screen /dev/ttyUSB0 115200
```

Obs: o vitis facilita isso
## Passo 1: Inicie U-Boot (Universal Bootloader) com uma image pré-compilada

```bash 
petalinux-boot jtag --prebuilt 2

```

Obs: A Xilinx fornece o comando: ```petalinux-boot jtag --kernel```, Mas quando tentei, usar o kernel travou. Esse tutorial e bypass para isso, você pode tentar.

## Passo 2: Copie os arquivos para o pendrive em uma pasta conhecida.

```bash 
/image/linux/{image.ub e rootfs.cpio.gz.u-boot}
```

## Passo 3: Mova para a memória

Obs: Isso no terminal da uart que deve esta esperando comandos nesse estágio:

```bash 
zynq>
```

```bash 
zynq>fatload usb 0:1 0x10000000 {Diretório da pasta do pendrive}/image.ub

```

```bash 
zynq>fatload usb 0:1 0x20000000 {Diretório da pasta do pendrive}/rootfs.cpio.gz.u-boot

```

## Passo 4: boot pela memoria

```bash 
zynq>bootm 0x10000000 0x20000000

```

### Exemplo pela UART no terminal do Vitis 

```bash 
zynq>fatload usb 0:1 0x10000000 vector_add_zcu106/image.ub
```

```bash 
zynq>fatload usb 0:1 0x20000000 vector_add_zcu106/rootfs.cpio.gz.u-boot
```

```bash 
zynq>bootm 0x10000000 0x20000000
```

### Problemas Típicos e Links Úteis

[Problemas Típicos](https://rpi.camerafmc.com/en/latest/troubleshooting.html)

[Tutorial ZCU104](https://www.hackster.io/engrinam0077/zcu104-mpsoc-development-petalinux-2024-2-basic-tutorial-c82b8d)

[Fluxo AVNET](https://gitlab.avnet.com/xilinx/example-board-projects/zcu106/vitis-platforms/avnet-sme-zcu106-vitis-platform-workflow-training/-/tree/main/2021.2/avtsme_zcu106_base)

[Tutorial ZCU106](https://www.fpgalover.com/index.php/boards/39-zcu106/97-build-petalinux-for-zcu106-ps-and-pl-based-10g-ethernet)

[Documentação PetaLinux](https://docs.amd.com/r/en-US/ug1144-petalinux-tools-reference-guide/Introduction)



