# syntax=docker/dockerfile:1
# =============================================================================
# Dockerfile - Build environment for AMD/Xilinx PetaLinux 2025.2
# =============================================================================
#
# READ BEFORE USING
#
# 1) Base = Ubuntu 22.04, NOT Ubuntu 26.04.
#    The petalinux-v2025.2-11160223-installer.run installer is validated by
#    AMD only up to Ubuntu 22.04 (see UG1144 / the 2025.2 release notes).
#    Ubuntu 26.04 "Resolute Raccoon" (04/2026) replaces coreutils and sudo
#    with Rust rewrites (uutils/sudo-rs) - a low-level change big enough to
#    risk subtly breaking the Yocto/bitbake scripts underneath PetaLinux.
#    Nobody has documented that combination working yet, so this image uses
#    22.04, which is the officially supported combination and has been
#    tested by third parties with this same installer version. If you want
#    to risk 26.04 anyway, change the FROM below - the rest of the
#    Dockerfile stays the same.
#
# 2) The ubuntu-26.04-desktop-amd64.iso image is NOT part of this flow at
#    any point. A Docker image does not install from a desktop ISO: it
#    starts straight from the minimal "ubuntu:22.04" rootfs on Docker Hub.
#    A desktop ISO would only make sense to install Ubuntu on a VM or a
#    physical machine - there is no equivalent step in a Dockerfile.
#
# 3) Before the build, manually download (an AMD/Xilinx login is required)
#       petalinux-v2025.2-11160223-installer.run
#    and place it in the SAME folder as this Dockerfile.
#
# HOW TO USE
#   docker build -t petalinux:2025.2 .
#   docker run -it --rm -v "$(pwd)/projects:/home/petalinux/projects" petalinux:2025.2
#
# Inside the container, petalinux-create / petalinux-config /
# petalinux-build work right away: the entrypoint sources settings.sh
# automatically.
#
# DISK SPACE: reserve at least 100-150 GB free for the image + build
# projects (a requirement of PetaLinux itself, not just the installer).
# =============================================================================

FROM ubuntu:22.04

LABEL description="PetaLinux 2025.2 environment on Ubuntu 22.04 (officially supported combination)"

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
# 1) Locale: bitbake (Yocto) refuses to run without a valid UTF-8 locale
# -----------------------------------------------------------------------------
RUN apt-get update && apt-get install -y --no-install-recommends locales tzdata \
    && locale-gen en_US.UTF-8 \
    && update-locale LANG=en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# 2) Host dependencies required by UG1144 (PetaLinux Tools Reference Guide)
#    for Ubuntu, plus packages confirmed by real 2025.2 installations that
#    the official guide does not list (e.g. libtinfo5), plus utilities to
#    automate the installation inside the container (expect, sudo).
#    Grouped by purpose purely for readability:
#      - toolchain/compilation, bitbake/Yocto utilities, ncurses
#        (menuconfig), 32-bit/multilib support, SSL, Yocto native libs
#        (qemu etc.), Python 3 (petalinux-create/config are py3), network/
#        download/VCS, and system/convenience utilities for the container.
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
# 3) PetaLinux requires /bin/sh to point to bash, not to dash
# -----------------------------------------------------------------------------
RUN echo "dash dash/sh boolean false" | debconf-set-selections \
    && DEBIAN_FRONTEND=noninteractive dpkg-reconfigure dash

# -----------------------------------------------------------------------------
# 4) Non-root user: the PetaLinux installer refuses to run as root
# -----------------------------------------------------------------------------
RUN useradd -m -u ${PETALINUX_UID} -s /bin/bash ${PETALINUX_USER} \
    && echo "${PETALINUX_USER} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${PETALINUX_USER} \
    && chmod 0440 /etc/sudoers.d/${PETALINUX_USER} \
    && mkdir -p /opt/petalinux \
    && chown -R ${PETALINUX_USER}:${PETALINUX_USER} /opt/petalinux

# -----------------------------------------------------------------------------
# 5) "expect" script to accept the EULAs without manual interaction.
#    The .run installer shows the license text in a pager and then asks
#    [y/N] twice (Xilinx EULA and Third Party EULA). By building the image
#    from this Dockerfile you are accepting those licenses on behalf of
#    whoever runs the build - re-read them under
#    /opt/petalinux/<version>/license/ before distributing the image.
# -----------------------------------------------------------------------------
COPY <<'EOF' /usr/local/bin/accept-eula.expect
#!/usr/bin/expect -f
# Usage: accept-eula.expect <installer.run> <destination-directory>
set installer [lindex $argv 0]
set dest      [lindex $argv 1]

# Without a valid window size on the pty, the pager used to display the
# licenses can fall back to a degraded mode ("WARNING: terminal is not fully
# functional") even with TERM set - hence we pin rows/cols here.
set stty_init "rows 40 cols 200"

spawn $installer --dir $dest

# We need to answer [y/N] twice (Xilinx EULA, Third Party EULA).
# Each license text goes through a pager whose "waiting for a key" prompt
# changes format depending on the environment (--More--, (END), or just the
# path of the temporary file being displayed, with no clear marker at all).
# Instead of trying to cover every possible format, we also use a short
# timeout as a safety net: if the output stalls for a few seconds, we assume
# it is the pager waiting for a key and send 'q'. This only runs during this
# phase (until the 2 questions have been answered), and in the worst case
# the effect is just one extra 'q' being "typed" while we are still looking
# at license text - harmless, and it does not interfere with the real
# installation that follows.
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

# From here on it is just the real installation running (it can take
# several minutes) - we stop reacting to the output and simply wait for it
# to finish.
set timeout -1
expect eof
catch wait result
exit [lindex $result 3]
EOF

# -----------------------------------------------------------------------------
# 6) Entrypoint: sources the PetaLinux settings.sh automatically, both in an
#    interactive shell (docker run -it ... bash) and in direct command mode
#    (docker run ... petalinux-build), with no need to edit .bashrc.
# -----------------------------------------------------------------------------
COPY <<'EOF' /usr/local/bin/docker-entrypoint.sh
#!/bin/bash
set -e
SETTINGS="$(find /opt/petalinux -mindepth 2 -maxdepth 2 -name settings.sh 2>/dev/null | head -n1)"
if [ -n "$SETTINGS" ]; then
    # shellcheck disable=SC1090
    source "$SETTINGS"
else
    echo "Warning: PetaLinux settings.sh not found under /opt/petalinux" >&2
fi
exec "$@"
EOF

RUN chmod +x /usr/local/bin/accept-eula.expect /usr/local/bin/docker-entrypoint.sh

# -----------------------------------------------------------------------------
# 7) Copy the installer (it must sit next to this Dockerfile) and install it
#    as a non-root user. The .run is deleted at the end of this SAME layer
#    so the final image is not inflated by the installer's several GB.
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

# Restore apt's default interactive behaviour for anyone using the container
# afterwards (docker exec) - this only affects runtime, not the installation
# above.
ENV DEBIAN_FRONTEND=dialog

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["/bin/zsh"]
