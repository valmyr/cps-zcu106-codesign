
## Dockerfile - Ambiente de build para o AMD/Xilinx PetaLinux 2025.2


### LEIA ANTES DE USAR



 1) Base = Ubuntu 22.04, NAO Ubuntu 26.04.
    O instalador petalinux-v2025.2-11160223-installer.run e validado pela
    AMD apenas ate o Ubuntu 22.04 (ver UG1144 / release notes do 2025.2).
    O Ubuntu 26.04 "Resolute Raccoon" (04/2026) substitui coreutils e sudo
    por reescritas em Rust (uutils/sudo-rs) - mudanca de baixo nivel grande
    o bastante para arriscar quebrar, de forma sutil, os scripts do
    Yocto/bitbake por baixo do PetaLinux. Ninguem documentou essa
    combinacao funcionando ainda, entao esta imagem usa 22.04, que e a
    combinacao com suporte oficial e testada por terceiros para esta mesma
    versao do instalador. Se quiser arriscar o 26.04 mesmo assim, troque o
    FROM abaixo - o resto do Dockerfile nao muda.

 2) A ISO ubuntu-26.04-desktop-amd64.iso NAO entra neste fluxo em nenhum
    momento. Imagem Docker nao instala a partir de ISO de desktop: ela
    parte direto da rootfs minima "ubuntu:22.04" do Docker Hub. Uma ISO
    de desktop so faria sentido para instalar Ubuntu numa VM/maquina
    fisica - nao existe passo equivalente em Dockerfile.

 3) Antes do build, baixe manualmente (exige login na AMD/Xilinx) o arquivo
       petalinux-v2025.2-11160223-installer.run
    e coloque-o na MESMA pasta deste Dockerfile.

### COMO USAR
```bash 
   docker build -t petalinux:2025.2 .
   docker run -it --privileged --rm -v "$(pwd)/projects:/home/petalinux/projects" petalinux:2025.2
```
 Dentro do container, petalinux-create / petalinux-config / petalinux-build
 ja funcionam direto: o entrypoint carrega o settings.sh automaticamente.

 ESPACO EM DISCO: reserve pelo menos 100-150 GB livres para a imagem +
 projetos de build (exigencia do proprio PetaLinux, nao e so o instalador).
