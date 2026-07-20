
# Cyber-Physical Systems — Ambiente PetaLinux (Docker + Tutorial)

Este documento reúne duas partes:

1. **Dockerfile** — ambiente de build containerizado para o AMD/Xilinx PetaLinux 2025.2.
2. **Tutorial** — passo a passo completo para gerar uma imagem PetaLinux a partir de um projeto de hardware feito no Vivado, incluindo o boot na placa e o deploy de uma aplicação Cyber-Physical Systems (reator Lingua Franca) na FPGA.

---

## Parte 1 — Dockerfile: ambiente de build para o PetaLinux 2025.2

### Leia antes de usar

1. **Base = Ubuntu 22.04, não Ubuntu 26.04.**
   O instalador `petalinux-v2025.2-11160223-installer.run` é validado pela AMD apenas até o Ubuntu 22.04 (ver UG1144 / release notes do 2025.2). O Ubuntu 26.04 "Resolute Raccoon" (04/2026) substitui `coreutils` e `sudo` por reescritas em Rust (`uutils`/`sudo-rs`) — uma mudança de baixo nível grande o bastante para arriscar quebrar, de forma sutil, os scripts do Yocto/BitBake por baixo do PetaLinux. Ninguém documentou essa combinação funcionando ainda, então esta imagem usa 22.04, que é a combinação com suporte oficial e testada por terceiros para esta mesma versão do instalador. Se quiser arriscar o 26.04 mesmo assim, troque o `FROM` no Dockerfile — o resto do arquivo não muda.

2. **A ISO `ubuntu-26.04-desktop-amd64.iso` não entra neste fluxo em nenhum momento.**
   Imagem Docker não instala a partir de ISO de desktop: ela parte direto da rootfs mínima `ubuntu:22.04` do Docker Hub. Uma ISO de desktop só faria sentido para instalar Ubuntu numa VM/máquina física — não existe um passo equivalente no Dockerfile.

3. **Baixe o instalador manualmente antes do build.**
   Faça login no site da AMD/Xilinx, baixe o arquivo `petalinux-v2025.2-11160223-installer.run` e coloque-o na mesma pasta deste Dockerfile.

### Como usar

```bash
docker build -t petalinux:2025.2 .
docker run -it --privileged --rm -v "$(pwd)/projects:/home/petalinux/projects" petalinux:2025.2
```

Dentro do container, `petalinux-create`, `petalinux-config` e `petalinux-build` já funcionam direto: o entrypoint carrega o `settings.sh` automaticamente.

> **Espaço em disco:** reserve pelo menos 100–150 GB livres para a imagem + projetos de build (exigência do próprio PetaLinux, não apenas do instalador).

---

## Parte 2 — Tutorial: criando um projeto PetaLinux a partir do Vivado/Vitis

Este tutorial mostra o fluxo completo para gerar uma imagem Linux embarcado (PetaLinux) a partir de um projeto de hardware criado no Vivado: exportação do XSA, geração do Software Device Tree, criação do projeto PetaLinux, configuração, build, geração da imagem de boot e deploy final na FPGA.

### Pré-requisitos

- **Vivado / Vitis** 2025.2 instalados e licenciados.
- **PetaLinux Tools** 2025.2 instalado (`/petalinux` neste tutorial).
- BSP da placa alvo (neste exemplo, **ZCU106**).
- Ambiente Linux com espaço em disco suficiente — o build do Yocto pode passar de 50 GB — e, de preferência, várias CPUs disponíveis para paralelizar o build.
- **Requisito total de espaço em disco: +200 GB.**

### Sumário

1. [Preparando o Hardware no Vivado](#1-preparando-o-hardware-no-vivado)
2. [Exportando o Hardware (XSA)](#2-exportando-o-hardware-xsa)
3. [Gerar o Software Device Tree (SDT)](#3-gerar-o-software-device-tree-sdt)
4. [Criar e configurar o projeto PetaLinux](#4-criar-e-configurar-o-projeto-petalinux)
5. [Incorporar as características do hardware ao PetaLinux](#5-incorporar-as-características-do-hardware-ao-petalinux)
6. [Configurar o RootFS](#6-configurar-o-rootfs)
7. [Compilar o PetaLinux](#7-compilar-o-petalinux)
8. [Boot via JTAG / Pen Drive](#8-boot-via-jtag--pen-drive)
9. [Deploy na FPGA](#9-deploy-na-fpga)
9. [Links Úteis](#10-links-uteis)

---

## 1. Preparando o Hardware no Vivado

### 1.1 Carregar o ambiente Vitis

Antes de exportar o hardware, confira no gerenciador de fontes do Vivado se o *block design* e os demais arquivos do projeto estão corretos e sem erros pendentes.

<p align="center">
<img title="Carregando o ambiente Vitis via source" alt="Terminal executando o source do settings.sh do Vitis" src="../../images/passo1.png" width="100%" height="100%">
</p>

```bash
source <diretório de instalação do Vitis>/settings.sh
```

Exemplo:

```bash
source ~/Music/2025.2/Vitis/settings.sh
```

### 1.2 Validar o Block Design

Abra o *Block Design* e valide a arquitetura do sistema (processador, barramentos, IPs periféricos, clocks e resets). É neste momento que se confirma se todos os blocos necessários para o PetaLinux (UART, memória DDR, controlador de armazenamento, etc.) estão presentes e conectados corretamente.

<p align="center">
<img title="Block Design no Vivado" alt="Block design do Vivado com processador e periféricos conectados" src="../../images/passo2.png" width="100%" height="100%">
</p>

## 2. Exportando o Hardware (XSA)

Com o hardware validado, o próximo passo é gerar o arquivo **XSA** (Xilinx Support Archive), que descreve o hardware e será consumido pelo PetaLinux para criar o BSP (Board Support Package).

No Vivado, vá em **File → Export → Export Hardware** e siga o assistente:

**Passo 1 – Início do assistente de exportação.** Confirme que deseja exportar o hardware do projeto atual.

<p align="center">
<img title="Assistente de exportação de hardware" alt="Tela inicial do assistente Export Hardware do Vivado" src="../../images/passo3.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Assistente de exportação de hardware" alt="Segunda tela do assistente Export Hardware" src="../../images/passo4.png" width="100%" height="100%">
</p>

**Passo 2 – Incluir o bitstream.** Marque a opção **Include Bitstream**, para que o arquivo XSA contenha a configuração completa do FPGA.

<p align="center">
<img title="Opção Include Bitstream marcada" alt="Tela do assistente com a opção Include Bitstream selecionada" src="../../images/passo5.png" width="100%" height="100%">
</p>

**Passo 3 – Escolher o diretório de destino.** Neste exemplo:

```bash
/home/xmen/workspace/<nome_do_projeto>
```

<p align="center">
<img title="Seleção do diretório de exportação" alt="Tela de seleção do diretório onde o XSA será salvo" src="../../images/passo6.png" width="100%" height="100%">
</p>

**Passo 4 – Confirmar a exportação.** Verifique se o arquivo `.xsa` foi gerado no diretório correto:

```bash
cd /home/xmen/workspace
ls
# <nome do projeto>.xsa
```

<p align="center">
<img title="Confirmação do arquivo XSA gerado" alt="Terminal mostrando o arquivo .xsa gerado no diretório de workspace" src="../../images/passo8.png" width="100%" height="100%">
</p>

## 3. Gerar o Software Device Tree (SDT)

Crie um script Tcl chamado `sdt.tcl`:

<p align="center">
<img title="Criando o script sdt.tcl" alt="Editor de texto criando o arquivo sdt.tcl" src="../../images/passo9.png" width="100%" height="100%">
</p>

Conteúdo do `sdt.tcl`:

```tcl
set outdir [lindex $argv 1]
set xsa [lindex $argv 0]
exec rm -rf $outdir
sdtgen set_dt_param -xsa $xsa -dir $outdir -board_dts zcu106-reva
sdtgen generate_sdt
```

> **Nota:** Mais detalhes sobre o TCL em [TCL File Mode - Xilinx System-Device-Tree-xlnx](https://github.com/Xilinx/system-device-tree-xlnx/blob/master/README.md).

> **Nota:** Ajuste `zcu106-reva` para o identificador correspondente à sua placa, caso não esteja usando uma ZCU106.

<p align="center">
<img title="Conteúdo do script sdt.tcl" alt="Conteúdo do arquivo sdt.tcl aberto no editor" src="../../images/passo10.png" width="100%" height="100%">
</p>

Confirme que o arquivo foi criado com sucesso:

```bash
cd /home/xmen/workspace/
cat sdt.tcl
```

<p align="center">
<img title="Conferindo o conteúdo do sdt.tcl" alt="Terminal exibindo o conteúdo do sdt.tcl com cat" src="../../images/passo11.png" width="100%" height="100%">
</p>

Execute o script com `xsct`, a partir do diretório de workspace:

```bash
cd /home/xmen/workspace/
```

Sintaxe geral:

```bash
xsct sdt.tcl <caminho_do_arquivo_XSA> sdt_outdir
```

Exemplo usado neste tutorial:

```bash
xsct sdt.tcl design_1_wrapper.xsa sdt_outdir
```

<p align="center">
<img title="Execução do xsct para gerar o SDT" alt="Terminal executando xsct sdt.tcl" src="../../images/passo12.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Saída da geração do Software Device Tree" alt="Terminal mostrando o processamento do sdtgen" src="../../images/passo13.png" width="100%" height="100%">
</p>

Ao final, o diretório `sdt_outdir` deve conter os arquivos de device tree gerados a partir do hardware exportado.

## 4. Criar e configurar o projeto PetaLinux

Vá até o diretório de instalação do PetaLinux e carregue o ambiente:

```bash
cd /petalinux
source settings.sh
```

<p align="center">
<img title="Carregando o ambiente PetaLinux" alt="Terminal executando source settings.sh do PetaLinux" src="../../images/passo17.png" width="100%" height="100%">
</p>

Após o carregamento, volte ao diretório do projeto:

```bash
cd -
```

Crie o projeto PetaLinux a partir do BSP da placa:

```bash
petalinux-create -t project -s <caminho_do_arquivo>.bsp
```

Exemplo usado neste tutorial:

```bash
petalinux-create -t project -s /petalinux/xilinx-zcu106-v2025.2-11160223.bsp
```

Após a execução, o diretório base do projeto deve conter a estrutura padrão de um projeto PetaLinux:

<p align="center">
<img title="Estrutura do projeto PetaLinux criado" alt="Listagem de diretórios do projeto PetaLinux recém-criado" src="../../images/passo18.png" width="100%" height="100%">
</p>

## 5. Incorporar as características do hardware ao PetaLinux

Entre no diretório do projeto criado (o nome varia conforme a versão do PetaLinux):

```bash
cd xilinx-zcu106-20xx.x
```

Neste tutorial:

```bash
cd xilinx-zcu106-2025.2
```

Aponte o projeto para o hardware exportado (diretório do SDT gerado na etapa 3):

```bash
petalinux-config --get-hw-description ../sdt_outdir
```

<p align="center">
<img title="Importando a descrição de hardware" alt="Terminal executando petalinux-config --get-hw-description" src="../../images/passo19.png" width="100%" height="100%">
</p>

### 5.1 Threads paralelas de build

No menu de configuração, acesse:

**PetaLinux System Configuration → Yocto Settings → Parallel Thread Execution**

<p align="center">
<img title="Menu de configuração de threads paralelas" alt="Menu ncurses do PetaLinux System Configuration em Yocto Settings" src="../../images/passo20.png" width="100%" height="100%">
</p>

Verifique a quantidade de CPUs disponíveis no seu ambiente de trabalho:

```bash
neofetch
```

<p align="center">
<img title="Verificando núcleos de CPU disponíveis" alt="Saída do neofetch mostrando informações da CPU" src="../../images/cpuinfo.png" width="100%" height="100%">
</p>

Neste exemplo, um Intel i9-14900F com 32 núcleos:

<p align="center">
<img title="Detalhe da CPU utilizada no exemplo" alt="Detalhe do processador Intel i9-14900F com 32 núcleos" src="../../images/passo21.png" width="100%" height="100%">
</p>

Defina os campos de paralelismo (**BB Number of Threads**, **PARALLEL_MAKE**, etc.) de acordo com a quantidade de núcleos disponível, para acelerar o build:

<p align="center">
<img title="Configurando o número de threads paralelas" alt="Campos de configuração de threads preenchidos com o número de núcleos" src="../../images/passo22.png" width="100%" height="100%">
</p>

### 5.2 Argumentos de boot do kernel (UIO genérico)

Acesse:

**PetaLinux System Configuration → DT Settings → Kernel Bootargs → Add extra boot args**

Adicione um driver de kernel **Generic UIO (Userspace I/O)** à imagem, para permitir acesso aos periféricos da PL a partir do userspace:

<p align="center">
<img title="Menu de argumentos extras de boot" alt="Menu de configuração de Kernel Bootargs" src="../../images/passo23.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Campo de argumentos extras de boot" alt="Campo de texto para adicionar argumentos extras de boot" src="../../images/passo24.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Edição dos argumentos de boot" alt="Edição do campo de boot args" src="../../images/passo25.png" width="100%" height="100%">
</p>

Adicione o argumento:

```
uio_pdrv_genirq.of_id=generic-uio
```

<p align="center">
<img title="Argumento uio_pdrv_genirq adicionado" alt="Campo de boot args com uio_pdrv_genirq.of_id=generic-uio preenchido" src="../../images/passo26.png" width="100%" height="100%">
</p>

### 5.3 Manter a PL no device tree

Acesse **PetaLinux System Configuration → DT Settings** e **desmarque** a opção **Remove PL from devicetree**, para que os IPs da lógica programável apareçam no device tree final:

<p align="center">
<img title="Opção Remove PL from devicetree" alt="Menu DT Settings com a opção Remove PL from devicetree" src="../../images/passo27.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Opção Remove PL from devicetree desmarcada" alt="Opção Remove PL from devicetree desmarcada" src="../../images/passo28.png" width="100%" height="100%">
</p>

### 5.4 Empacotamento de imagem

Acesse **PetaLinux System Configuration → Image Packaging Configuration** e **desmarque** a opção **Copy final images to tftpboot** (a menos que você use boot via TFTP):

<p align="center">
<img title="Opção Copy final images to tftpboot" alt="Menu Image Packaging Configuration com a opção tftpboot desmarcada" src="../../images/passo30.png" width="100%" height="100%">
</p>

Salve as configurações e saia do menu **PetaLinux System Configuration**:

<p align="center">
<img title="Salvando as configurações do menu" alt="Tela de confirmação para salvar as configurações" src="../../images/passo31.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Saindo do menu de configuração" alt="Terminal após sair do menu de configuração do PetaLinux" src="../../images/passo32.png" width="100%" height="100%">
</p>

### 5.5 Sincronizar o device tree da PL

A partir do diretório do projeto (`/home/xmen/workspace/xilinx-zcu106-2025.2/`), abra os dois arquivos abaixo e copie o conteúdo de `pl.dtsi` para dentro de `system-user.dtsi`:

```bash
code ../sdt_outdir/pl.dtsi
code project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi
```

> **Nota:** Detalhes em Vitis-Tutorial [02 Edge IA ZCU104](https://xilinx.github.io/Vitis-Tutorials/2021-1/build/html/docs/Vitis_Platform_Creation/Introduction/02-Edge-AI-ZCU104/step2.html).
> **Nota:** Detalhes em GPIO PetaLinux [GPIO PetaLinux Part 2](https://www.linkedin.com/pulse/gpio-petalinux-part-2-roy-messinger/).

<p align="center">
<img title="Copiando pl.dtsi para system-user.dtsi" alt="VS Code com os arquivos pl.dtsi e system-user.dtsi lado a lado" src="../../images/passo33.png" width="100%" height="100%">
</p>

Na linha correspondente ao nó `uio`, altere a propriedade `compatible` para:

```
compatible = "generic-uio";
```

Salve e feche o VS Code:

<p align="center">
<img title="Alterando a propriedade compatible" alt="Linha compatible = generic-uio editada no system-user.dtsi" src="../../images/passo34.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Arquivo system-user.dtsi salvo" alt="Arquivo salvo no VS Code" src="../../images/passo36.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Terminal após edição do device tree" alt="Terminal de volta após fechar o editor" src="../../images/passo38.png" width="100%" height="100%">
</p>

## 6. Configurar o RootFS

```bash
petalinux-config -c rootfs
```

> **Dica:** use `/` para pesquisar as opções a serem habilitadas e selecione o item com a barra de espaço (tecla numérica) do teclado.

### Habilite os seguintes itens no rootfs

| Item | Categoria | Prioridade |
|---|---|---|
| `libstdc++` | libs | Obrigatório |
| `kmod` | misc | Obrigatório |
| `udev` | base | Obrigatório |
| `libgpiod` | libs | Necessário (GPIO) |
| `gpio-demo` | console → utils | Recomendado |
| `ssh-server-dropbear` **ou** `ssh-server-openssh` (não os dois) | console → network | Recomendado |
| `python3-numpy` | devel | Opcional (análise de dados) |
| `packagegroup-core-buildessential` | petalinux | Opcional (dev na placa, aumenta o tamanho da imagem) |
| `util-linux` | base | Necessário (boot via pen drive) |
| `e2fsprogs` | console → utils | Necessário (boot via pen drive) |
| `dosfstools` | console → utils | Necessário (boot via pen drive) |
| `gdb` | console → utils | Necessário (debug — marcar explicitamente) |
| `packagegroup-core-tools-debug` | petalinux | Opcional (símbolos de debug) |
| `strace` | console → utils | Opcional (debug) |
| `sudo` | base | Obrigatório |

**Observação:** em `gpio-demo`, mantenha as seguintes opções habilitadas:

<p align="center">
<img title="Opções do pacote gpio-demo" alt="Menu de configuração do pacote gpio-demo" src="../../images/passo39.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Sub-opções habilitadas em gpio-demo" alt="Sub-opções do gpio-demo marcadas" src="../../images/passo40.png" width="100%" height="100%">
</p>

Salve as configurações e saia do menu **Configuration**:

<p align="center">
<img title="Confirmação das opções do gpio-demo" alt="Tela final de confirmação das opções do gpio-demo" src="../../images/passo41.png" width="100%" height="100%">
</p>

## 7. Compilar o PetaLinux

O build pode levar de alguns minutos a algumas horas, dependendo do hardware do seu computador e da quantidade de threads configurada.

```bash
petalinux-build
```

<p align="center">
<img title="Início do build do PetaLinux" alt="Terminal com o build do PetaLinux em andamento" src="../../images/passo43.png" width="100%" height="100%">
</p>

É comum que a **primeira execução** apresente avisos e erros relacionados a cache do Yocto/BitBake, fazendo o build falhar. Nesse caso, basta executar o comando novamente — o cache já parcialmente construído normalmente resolve o problema na segunda tentativa. Para mais detalhes sobre falhas conhecidas de build, consulte o guia de troubleshooting do PetaLinux/Yocto, por exemplo a seção ["Build failures" da documentação do RPi Camera FMC](https://rpi.camerafmc.com/en/latest/troubleshooting.html) ou a documentação oficial da AMD/Xilinx para a sua versão de ferramentas.

<p align="center">
<img title="Erros de cache na primeira execução do build" alt="Terminal mostrando erros de cache na primeira tentativa de build" src="../../images/passo44.png" width="100%" height="100%">
</p>

Execute novamente:

```bash
petalinux-build
```

<p align="center">
<img title="Segunda execução do build" alt="Terminal com a segunda execução do petalinux-build" src="../../images/passo45.png" width="100%" height="100%">
</p>

### Compilação bem-sucedida

Ao final, os artefatos de boot (`BOOT.BIN`, `boot.scr`, `image.ub`/`Image`, `system.dtb`, rootfs) estarão disponíveis em `images/linux/` dentro do projeto, prontos para serem gravados em um cartão SD/pen drive ou usados via TFTP/JTAG.

<p align="center">
<img title="Build do PetaLinux concluído com sucesso" alt="Terminal mostrando o build do PetaLinux finalizado com sucesso" src="../../images/passo46.png" width="100%" height="100%">
</p>

## 8. Boot via JTAG / Pen Drive

A escolha desse método se deu em virtude da restrição imposta de realizar todo o processo remotamente, sem a necessidade de contato físico com a FPGA — ou seja, sem manipular os switches que outros métodos exigiriam. Existe também um método que usa `petalinux-boot jtag --kernel`, mas o sistema resultante fica incompleto e não foi aprofundado neste tutorial.

### Passo 1 — Copiar os artefatos para o pen drive

Copie os artefatos gerados pelo build (neste exemplo, de `/home/xmen/workspace/images/linux`) para o pen drive (neste exemplo, `/run/media/xmen/valmir`):

<p align="center">
<img title="Build do PetaLinux concluído com sucesso" alt="Terminal mostrando o build do PetaLinux finalizado com sucesso, artefatos prontos para copiar" src="../../images/passo48.png" width="100%" height="100%">
</p>

Crie uma pasta no seu pen drive com o nome que desejar.

Use o comando abaixo para listar os discos disponíveis:

```bash
df -h
```

No exemplo deste tutorial, o pen drive está montado em `/run/media/xmen/valmir`:

```bash
cd /run/media/xmen/valmir
mkdir workspace
```

Copie os artefatos `rootfs.cpio.gz.u-boot` e `image.ub` para o pen drive:

```bash
cd ~/workspace/xilinx-zcu106-2025.2/images/linux
cp rootfs.cpio.gz.u-boot image.ub /run/media/xmen/valmir/workspace
```

<p align="center">
<img title="Artefatos copiados para o pen drive" alt="Terminal mostrando a cópia de rootfs.cpio.gz.u-boot e image.ub para o pen drive" src="../../images/passo49.png" width="100%" height="100%">
</p>

### Passo 2 — Conectar o pen drive e abrir o monitor serial

Após copiar os arquivos, conecte o pen drive na FPGA.

Inicie o Vitis e vá em **Vitis → Serial Monitor**. Selecione a primeira UART referente ao ZYNQ:

<p align="center">
<img title="Seleção da UART no Vitis" alt="Menu Serial Monitor do Vitis com a UART do ZYNQ sendo selecionada" src="../../images/passo50.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Monitor serial conectado" alt="Terminal serial conectado à UART da placa" src="../../images/passo51.png" width="100%" height="100%">
</p>

Carregue o ambiente PetaLinux novamente:

<p align="center">
<img title="Carregando o ambiente PetaLinux novamente" alt="Terminal executando o source do settings.sh do PetaLinux" src="../../images/passo52.png" width="100%" height="100%">
</p>

E vá até o diretório do projeto (`/home/xmen/workspace/xilinx-zcu106-2025.2`):

<p align="center">
<img title="Acessando o diretório do projeto" alt="Terminal no diretório do projeto PetaLinux" src="../../images/passo53.png" width="100%" height="100%">
</p>

### Passo 3 — Iniciar o U-Boot via JTAG

Inicie o U-Boot com uma imagem pré-compilada:

```bash
petalinux-boot jtag --prebuilt 2
```

<p align="center">
<img title="Boot via JTAG com imagem pré-compilada" alt="Terminal executando petalinux-boot jtag --prebuilt 2" src="../../images/passo54.png" width="100%" height="100%">
</p>

### Passo 4 — Carregar os arquivos para a memória RAM

No terminal da UART:

```
zynq>
zynq> fatload usb 0:1 0x10000000 {diretório da pasta do pen drive}/image.ub
```

> **Obs.:** o dispositivo `usb 0:x` pode variar, e o diretório depende da pasta que você criou no pen drive.

Neste exemplo:

```
fatload usb 0:1 0x10000000 workspace/image.ub
fatload usb 0:1 0x20000000 workspace/rootfs.cpio.gz.u-boot
```

<p align="center">
<img title="Carregando os artefatos na memória RAM" alt="Terminal com os comandos fatload carregando image.ub e rootfs na RAM" src="../../images/passo55.png" width="100%" height="100%">
</p>

### Passo 5 — Boot pela memória RAM

```
bootm 0x10000000 0x20000000
```

<p align="center">
<img title="Executando o boot pela memória RAM" alt="Terminal executando o comando bootm" src="../../images/passo56.png" width="100%" height="100%">
</p>

> **Obs.:** por esse método, é esperado que o boot fique travado nesta tela — isso acontece porque ainda falta gravar o bitstream na lógica programável.

<p align="center">
<img title="Boot travado por falta do bitstream" alt="Terminal com o boot parado aguardando a programação da lógica programável" src="../../images/passo57.png" width="100%" height="100%">
</p>

### Passo 6 — Gravar o bitstream na lógica programável

No Vivado, vá em **PROGRAM AND DEBUG → Program Device**, selecione a placa (neste exemplo, `xczu7_0`):

<p align="center">
<img title="Seleção do dispositivo no Vivado" alt="Menu Program Device do Vivado com o dispositivo xczu7_0 selecionado" src="../../images/passo58.png" width="100%" height="100%">
</p>

**PROGRAM AND DEBUG → Program Device → `xczu7_0` → Program**

<p align="center">
<img title="Programação do bitstream" alt="Vivado gravando o bitstream no dispositivo xczu7_0" src="../../images/passo59.png" width="100%" height="100%">
</p>

Após isso, o sistema inicializará:

<p align="center">
<img title="Sistema inicializado com sucesso" alt="Terminal mostrando o Linux embarcado inicializado com sucesso" src="../../images/passo60.png" width="100%" height="100%">
</p>

### Passo 7 — Login inicial e acesso via SSH

Por padrão, o usuário é `petalinux`; a senha é a que você definiu durante o primeiro acesso.

<p align="center">
<img title="Prompt de login do PetaLinux" alt="Terminal mostrando o prompt de login do usuário petalinux" src="../../images/passo61.png" width="100%" height="100%">
</p>

A essa altura, você já deve ter percebido que, ao usar a UART, os comandos aparecem duplicados. Por isso, recomenda-se a utilização do SSH. Para isso, é preciso conectar a FPGA à rede e descobrir o endereço IP associado:

```bash
ifconfig
```

Neste exemplo, o IP obtido foi `192.168.130.31`:

<p align="center">
<img title="Verificando o endereço IP da placa" alt="Terminal mostrando a saída do comando ifconfig com o IP da placa" src="../../images/passo62.png" width="100%" height="100%">
</p>

Abra um terminal no host e conecte via SSH:

```bash
ssh petalinux@192.168.130.31
```

<p align="center">
<img title="Conectando via SSH" alt="Terminal do host conectando à placa via ssh" src="../../images/passo63.png" width="100%" height="100%">
</p>

> **Obs.:** use a senha definida anteriormente.

<p align="center">
<img title="Autenticação via SSH" alt="Terminal solicitando a senha do usuário petalinux via SSH" src="../../images/passo64.png" width="100%" height="100%">
</p>

### Passo 8 — Verificar o reconhecimento do UIO

Checagem do reconhecimento do Generic Userspace I/O (UIO):

```bash
cat /sys/class/uio/uio*/name
```

<p align="center">
<img title="Verificação do UIO" alt="Terminal mostrando a saída do cat em /sys/class/uio/uio*/name" src="../../images/passo66.png" width="100%" height="100%">
</p>

## 9. Deploy na FPGA

Com o sistema no ar e acessível via SSH, o deploy da aplicação segue os passos abaixo:

1. Use `scp` para copiar o `.tar` gerado no container para a FPGA, em `/home/petalinux`.
2. Extraia o conteúdo do `.tar` e entre como usuário `sudo`:
   ```bash
   sudo -i
   ```
3. Carregue a biblioteca a partir de `deploy_all_files`:
   ```bash
   export LD_LIBRARY_PATH=./lib
   ```
4. Execute o binário:
   ```bash
   ./bin/ExplorationMultipleReactorsCodesign
   ```

O projeto usado como exemplo está na pasta `examples` e explora a utilização de múltiplos reatores a partir de uma extensão do subconjunto da linguagem Lingua Franca voltada para o *Codesign of Reactor-Oriented Hardware and Software for Cyber-Physical Systems*.

<p align="center">
<img title="Deploy do projeto na FPGA" alt="Terminal mostrando o deploy do projeto ExplorationMultipleReactorsCodesign na FPGA" src="../../images/passo71.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Execução do binário na FPGA" alt="Terminal mostrando a execução do binário ExplorationMultipleReactorsCodesign" src="../../images/passo67.png" width="100%" height="100%">
</p>

Caso ocorra um erro de **Bus error (core dumped)**, será necessário programar novamente o bitstream — use os passos descritos na [seção 8](#8-boot-via-jtag--pen-drive).

<p align="center">
<img title="Erro Bus error (core dumped)" alt="Terminal mostrando o erro Bus error (core dumped) na execução do binário" src="../../images/passo68.png" width="100%" height="100%">
</p>

Execute novamente:

<p align="center">
<img title="Execução bem-sucedida após reprogramar o bitstream" alt="Terminal mostrando a execução bem-sucedida do binário após reprogramar o bitstream" src="../../images/passo70.png" width="100%" height="100%">
</p>

---

> Este tutorial ainda tem margem para melhoria e otimização — contribuições e sugestões são bem-vindas.

## 10. Links Úteis

- [Tutorial ZCU104](https://www.hackster.io/engrinam0077/zcu104-mpsoc-development-petalinux-2024-2-basic-tutorial-c82b8d)
- ["Build failures" da documentação do RPi Camera FMC](https://rpi.camerafmc.com/en/latest/troubleshooting.html)
- [TCL File Mode - Xilinx System-Device-Tree-xlnx](https://github.com/Xilinx/system-device-tree-xlnx/blob/master/README.md).
- [Fluxo AVNET](https://gitlab.avnet.com/xilinx/example-board-projects/zcu106/vitis-platforms/avnet-sme-zcu106-vitis-platform-workflow-training/-/tree/main/2021.2/avtsme_zcu106_base)
- [Tutorial ZCU106](https://www.fpgalover.com/index.php/boards/39-zcu106/97-build-petalinux-for-zcu106-ps-and-pl-based-10g-ethernet)
- [Documentação PetaLinux](https://docs.amd.com/r/en-US/ug1144-petalinux-tools-reference-guide/Introduction)
- [02 Edge IA ZCU104](https://xilinx.github.io/Vitis-Tutorials/2021-1/build/html/docs/Vitis_Platform_Creation/Introduction/02-Edge-AI-ZCU104/step2.html).
- [GPIO PetaLinux Part 2](https://www.linkedin.com/pulse/gpio-petalinux-part-2-roy-messinger/).
