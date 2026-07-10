# syntax=docker/dockerfile:1
# =============================================================================
# Dockerfile - Ambiente de build para o AMD/Xilinx PetaLinux 2025.2
# =============================================================================
#
# LEIA ANTES DE USAR
#
# 1) Base = Ubuntu 22.04, NAO Ubuntu 26.04.
#    O instalador petalinux-v2025.2-11160223-installer.run e validado pela
#    AMD apenas ate o Ubuntu 22.04 (ver UG1144 / release notes do 2025.2).
#    O Ubuntu 26.04 "Resolute Raccoon" (04/2026) substitui coreutils e sudo
#    por reescritas em Rust (uutils/sudo-rs) - mudanca de baixo nivel grande
#    o bastante para arriscar quebrar, de forma sutil, os scripts do
#    Yocto/bitbake por baixo do PetaLinux. Ninguem documentou essa
#    combinacao funcionando ainda, entao esta imagem usa 22.04, que e a
#    combinacao com suporte oficial e testada por terceiros para esta mesma
#    versao do instalador. Se quiser arriscar o 26.04 mesmo assim, troque o
#    FROM abaixo - o resto do Dockerfile nao muda.
#
# 2) A ISO ubuntu-26.04-desktop-amd64.iso NAO entra neste fluxo em nenhum
#    momento. Imagem Docker nao instala a partir de ISO de desktop: ela
#    parte direto da rootfs minima "ubuntu:22.04" do Docker Hub. Uma ISO
#    de desktop so faria sentido para instalar Ubuntu numa VM/maquina
#    fisica - nao existe passo equivalente em Dockerfile.
#
# 3) Antes do build, baixe manualmente (exige login na AMD/Xilinx) o arquivo
#       petalinux-v2025.2-11160223-installer.run
#    e coloque-o na MESMA pasta deste Dockerfile.
#
# COMO USAR
#   docker build -t petalinux:2025.2 .
#   docker run -it --rm -v "$(pwd)/projects:/home/petalinux/projects" petalinux:2025.2
#
# Dentro do container, petalinux-create / petalinux-config / petalinux-build
# ja funcionam direto: o entrypoint carrega o settings.sh automaticamente.
#
# ESPACO EM DISCO: reserve pelo menos 100-150 GB livres para a imagem +
# projetos de build (exigencia do proprio PetaLinux, nao e so o instalador).
# =============================================================================

FROM ubuntu:22.04

LABEL description="Ambiente PetaLinux 2025.2 sobre Ubuntu 22.04 (combinacao oficialmente suportada)"

ARG PETALINUX_VERSION=2025.2
ARG PETALINUX_INSTALLER=petalinux-v2025.2-11160223-installer.run
ARG PETALINUX_USER=petalinux
ARG PETALINUX_UID=1000

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Etc/UTC \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8 \
    TERM=xterm-256color

# -----------------------------------------------------------------------------
# 1) Locale: o bitbake (Yocto) se recusa a rodar sem uma locale UTF-8 valida
# -----------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends locales tzdata \
    && locale-gen en_US.UTF-8 \
    && update-locale LANG=en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# 2) Dependencias de host exigidas pelo UG1144 (PetaLinux Tools Reference
#    Guide) para Ubuntu, mais pacotes confirmados por instalacoes reais do
#    2025.2 que o guia oficial nao lista (ex.: libtinfo5), alem de utilitarios
#    para automatizar a instalacao dentro do container (expect, sudo).
#    Agrupados por finalidade so para facilitar leitura:
#      - toolchain/compilacao, utilitarios do bitbake/Yocto, ncurses
#        (menuconfig), suporte 32-bit/multilib, SSL, libs nativas do Yocto
#        (qemu etc.), Python 3 (petalinux-create/config sao em py3), rede/
#        download/VCS, e utilitarios de sistema/conveniencia do container.
# -----------------------------------------------------------------------------
RUN dpkg --add-architecture i386 && apt-get update && apt-get install -y --no-install-recommends \
    build-essential gcc g++ gcc-multilib g++-multilib make cpp tree \
    autoconf automake libtool libtool-bin patch gettext \
    chrpath socat texinfo diffstat cpio gawk iproute2 net-tools \
    xterm screen pax gzip unzip tar xz-utils debianutils \
    iputils-ping lzop bc rsync u-boot-tools tofrodos sed file xxd \
    libncurses5-dev libncursesw5-dev libtinfo5 \
    lib32z1 lib32stdc++6 zlib1g-dev zlib1g:i386 \
    libssl-dev libselinux1 gnupg \
    libsdl1.2-dev libglib2.0-dev libgtk2.0-0 \
    python3 python3-pip python3-pexpect python3-git python3-jinja2 python3-subunit pylint \
    git wget curl ca-certificates tftpd-hpa flex bison \
    lsb-release kmod sudo expect vim less \
    neofetch \
    openssh-server \
    dnsutils \
    zsh       \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# 3) O PetaLinux exige que /bin/sh aponte para bash, nao para dash
# -----------------------------------------------------------------------------
RUN echo "dash dash/sh boolean false" | debconf-set-selections \
    && DEBIAN_FRONTEND=noninteractive dpkg-reconfigure dash

# -----------------------------------------------------------------------------
# 4) Usuario nao-root: o instalador do PetaLinux recusa rodar como root
# -----------------------------------------------------------------------------
RUN useradd -m -u ${PETALINUX_UID} -s /bin/bash ${PETALINUX_USER} \
    && echo "${PETALINUX_USER} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${PETALINUX_USER} \
    && chmod 0440 /etc/sudoers.d/${PETALINUX_USER} \
    && mkdir -p /opt/petalinux \
    && chown -R ${PETALINUX_USER}:${PETALINUX_USER} /opt/petalinux

# -----------------------------------------------------------------------------
# 5) Script "expect" para aceitar as EULAs sem interacao manual.
#    O instalador .run mostra o texto das licencas num pager e depois
#    pergunta [y/N] duas vezes (Xilinx EULA e Third Party EULA). Ao gerar a
#    imagem a partir deste Dockerfile voce esta aceitando essas licencas em
#    nome de quem rodar o build - releia-as em
#    /opt/petalinux/<versao>/license/ antes de distribuir a imagem.
# -----------------------------------------------------------------------------
COPY <<'EOF' /usr/local/bin/accept-eula.expect
#!/usr/bin/expect -f
# Uso: accept-eula.expect <installer.run> <diretorio-destino>
set installer [lindex $argv 0]
set dest      [lindex $argv 1]

# Sem um tamanho de janela valido no pty, o paginador usado para exibir as
# licencas pode cair num modo degradado ("WARNING: terminal is not fully
# functional") mesmo com TERM setado - por isso fixamos rows/cols aqui.
set stty_init "rows 40 cols 200"

spawn $installer --dir $dest

# Precisamos responder [y/N] duas vezes (Xilinx EULA, Third Party EULA).
# O texto de cada licenca passa por um paginador cujo prompt de "esperando
# tecla" muda de formato dependendo do ambiente (--More--, (END), ou so o
# caminho do arquivo temporario sendo exibido, sem nenhum marcador claro).
# Em vez de tentar cobrir cada formato possivel, usamos tambem um timeout
# curto como rede de seguranca: se a saida ficar parada por alguns
# segundos, assumimos que e o paginador esperando uma tecla e mandamos
# 'q'. Isso so roda nesta fase (ate as 2 perguntas serem respondidas), e
# no pior caso o efeito e so um 'q' a mais sendo "digitado" enquanto ainda
# estamos vendo texto de licenca - inofensivo, nao interfere na instalacao
# de verdade que vem depois.
set eula_count 0
while {$eula_count < 2} {
    set timeout 6
    expect {
        -re {\[y/N\]} {
            send "y\r"
            incr eula_count
        }
        -re {Press Enter to display the license agreements} {
            send "\r"
        }
        -re {Press RETURN to continue} {
            send "\r"
        }
        -re {--More--|\(END\)} {
            send "q"
        }
        timeout {
            send "q"
        }
        eof {
            break
        }
    }
}

# Dai em diante e so a instalacao de verdade rodando (pode levar varios
# minutos) - paramos de reagir a saida e so aguardamos ate terminar.
set timeout -1
expect eof
catch wait result
exit [lindex $result 3]
EOF

# -----------------------------------------------------------------------------
# 6) Entrypoint: carrega o settings.sh do PetaLinux automaticamente, tanto
#    em shell interativo (docker run -it ... bash) quanto em modo comando
#    direto (docker run ... petalinux-build), sem precisar editar .bashrc.
# -----------------------------------------------------------------------------
COPY <<'EOF' /usr/local/bin/docker-entrypoint.sh
#!/bin/bash
set -e
SETTINGS="$(find /opt/petalinux -mindepth 2 -maxdepth 2 -name settings.sh 2>/dev/null | head -n1)"
if [ -n "$SETTINGS" ]; then
    # shellcheck disable=SC1090
    source "$SETTINGS"
else
    echo "Aviso: settings.sh do PetaLinux nao encontrado em /opt/petalinux" >&2
fi
exec "$@"
EOF

RUN chmod +x /usr/local/bin/accept-eula.expect /usr/local/bin/docker-entrypoint.sh

# -----------------------------------------------------------------------------
# 7) Copia o instalador (precisa estar ao lado deste Dockerfile) e instala
#    como usuario nao-root. O .run e apagado no fim desta MESMA camada para
#    nao inflar a imagem final com os ~GBs do instalador.
# -----------------------------------------------------------------------------
COPY --chown=${PETALINUX_USER}:${PETALINUX_USER} ${PETALINUX_INSTALLER} /tmp/${PETALINUX_INSTALLER}
USER ${PETALINUX_USER}

WORKDIR /home/${PETALINUX_USER}
###############################################################################
# Oh My Zsh
###############################################################################

RUN RUNZSH=no \
    CHSH=no \
    KEEP_ZSHRC=yes \
    sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"

RUN chmod +x /tmp/${PETALINUX_INSTALLER} && \
    env TERM=xterm \
        PAGER=cat \
        LESS="-FRX" \
        COLUMNS=200 \
        LINES=50 \
        /usr/local/bin/accept-eula.expect \
            /tmp/${PETALINUX_INSTALLER} \
            /opt/petalinux/${PETALINUX_VERSION}

# Volta o apt ao comportamento interativo padrao para quem usar o container
# depois (docker exec) - so afeta o runtime, nao a instalacao acima.
ENV DEBIAN_FRONTEND=dialog

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["/bin/zsh"]
