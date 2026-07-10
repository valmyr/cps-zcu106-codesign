# syntax=docker/dockerfile:1

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Etc/UTC

###############################################################################
# Dependências
###############################################################################

RUN apt-get update && apt-get install -y \
    git \
    curl \
    wget \
    unzip \
    zip \
    vim \
    nano \
    sed \
    grep \
    build-essential \
    python3 \
    python3-pip \
    neofetch \
    openssh-server \
    ca-certificates \
    gnupg \
    tree \
    verilator \
    software-properties-common \
    openjdk-17-jdk \
    net-tools \
    cmake           \
    zsh            \
    gradle && \
    rm -rf /var/lib/apt/lists/*

###############################################################################
# Instala SBT
###############################################################################

RUN mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://keyserver.ubuntu.com/pks/lookup?op=get\&search=0x99E82A75642AC823 | \
    gpg --dearmor -o /etc/apt/keyrings/sbt.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/sbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
    > /etc/apt/sources.list.d/sbt.list && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

###############################################################################
# Java
###############################################################################

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

###############################################################################
# Configuração do Git
###############################################################################

RUN git config --global url."https://github.com/".insteadOf git@github.com:

###############################################################################
# Workspace
###############################################################################

WORKDIR /opt

###############################################################################
# Clone
###############################################################################

RUN git clone \
    --branch chisel3 \
    https://github.com/erlingrj/lingua-franca.git

WORKDIR /opt/lingua-franca

###############################################################################
# Corrige .gitmodules
###############################################################################

RUN sed -i \
's|git@github.com:erlingrj/reactor-chisel|https://github.com/erlingrj/reactor-chisel.git|g' \
.gitmodules

###############################################################################
# Atualiza submódulos
###############################################################################

RUN git submodule sync --recursive

RUN git submodule update --init --recursive

###############################################################################
# Atualiza Scala
###############################################################################

RUN find . \
\( -name "*.kt" -o -name "*.sbt" -o -name "build.sc" \) \
-exec sed -i 's/2\.13\.8/2.13.10/g' {} \;


###############################################################################
# Atualiza Chisel / ChiselTest
#
# O repo declara chisel3/chiseltest em 4 lugares diferentes, cada um com uma
# patch-version de partida diferente:
#   - ChiselSbtGenerator.kt              -> chisel3 3.5.3 / chiseltest 0.5.1
#   - reactor-chisel/build.sbt           -> chisel3 3.5.3 / chiseltest 0.5.1
#   - reactor-chisel/build.sc (Mill)     -> chisel3 3.5.1 / chiseltest 0.5.1
#   - fpga-tidbits/build.sbt (submódulo) -> chisel3 3.5.6 / chiseltest 0.5.6
#
# A versão original deste Dockerfile trocava só valores exatos (3.5.3->3.6.0,
# 0.5.1->0.6.0, e depois 3.5.6->3.6.0 só para o chisel3), mas nunca migrava o
# "chiseltest 0.5.6" do fpga-tidbits/build.sbt. Resultado: fpgatidbits passava
# a depender de chisel3 3.6.0 enquanto chiseltest ficava preso em 0.5.6 (que
# por sua vez exige chisel3 3.5.6) — exatamente o conflito binário que o sbt
# reporta ao gerar o hardware ("chisel3_2.13:3.6.0 ... is selected over 3.5.6
# for test" / "chiseltest_2.13:0.5.6 (depends on 3.5.6)"), o que faz o `sbt
# run` falhar e, em cascata, a build do software falhar por falta de
# platform.h / lfFPGA.a (nunca chegam a ser gerados).
#
# Usar uma regex de patch-version (3\.5\.[0-9]+ / 0\.5\.[0-9]+) em vez de
# valores exatos garante que TODOS os arquivos fiquem na mesma dupla
# compatível, por linha oficial do Chisel: chisel3 3.6.x <-> chiseltest 0.6.x
# (https://www.chisel-lang.org/docs/appendix/versioning).
###############################################################################

RUN find . \
\( -name "*.kt" -o -name "*.sbt" -o -name "build.sc" \) \
-exec sed -i -E 's/3\.5\.[0-9]+/3.6.0/g; s/0\.5\.[0-9]+/0.6.0/g' {} \;

###############################################################################
# Corrige PlatformWrapper.scala
###############################################################################

RUN sed -i \
's/\.asUInt()/\.asUInt/g' \
core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala

###############################################################################
# Compila
###############################################################################

RUN chmod +x gradlew

RUN ./gradlew assemble --no-daemon

###############################################################################
# PATH
###############################################################################

ENV PATH=/opt/lingua-franca/build/install/lf-cli/bin:$PATH

###############################################################################
# Testes
###############################################################################

RUN java -version

RUN gradle --version

RUN sbt --version

RUN lfc --help

###############################################################################
# Usuário lingua-franca
###############################################################################

RUN useradd -m -s /bin/bash lingua-franca

# Dá posse do projeto ao novo usuário: o lfc escreve ali (ex.:
# test/Codesign/codesign-gen/...), então ele precisa ter permissão de escrita,
# não só de leitura.
RUN chown -R lingua-franca:lingua-franca /opt/lingua-franca

# Reaproveita o cache do sbt/coursier/ivy2 já baixado durante a build (como
# root), pra evitar que o lfc tenha que rebaixar chisel3/chiseltest/scala do
# zero na primeira vez que rodar como o novo usuário.
RUN for d in .cache .ivy2 .sbt; do \
      if [ -d "/root/$d" ]; then \
        cp -a "/root/$d" "/home/lingua-franca/$d"; \
      fi; \
    done && \
    chown -R lingua-franca:lingua-franca /home/lingua-franca

RUN mkdir -p /workspace && chown lingua-franca:lingua-franca /workspace

RUN sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"

RUN echo 'lingua-franca:1234' | chpasswd

USER lingua-franca

WORKDIR /home/lingua-franca

CMD ["/bin/zsh"]
