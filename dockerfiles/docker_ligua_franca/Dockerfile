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
    cmake \
    zsh \
    g++-aarch64-linux-gnu \
    gcc-aarch64-linux-gnu \
    gradle \
    sudo && \
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
# Corrige AXIPlatformWrapper.scala
#
# O build falha na elaboração do hardware com o erro:
#   "Attempted to name ZedBoardWrapper.csr: IO[AXILiteExternalIF] with a
#    duplicated name 'csr'. Use suggestName to seed a unique name"
#
# Isso acontece porque o Wire interno usado para a lógica do regfile é
# declarado como "val csr = Wire(...)" -- mesmo nome do IO externo
# "csr" (extCsrIf.suggestName("csr")). Quando a subclasse concreta do
# wrapper (ex.: ZedBoardWrapper) também acaba expondo/observando esse
# nome, o Chisel detecta a colisão e a elaboração falha.
#
# A correção renomeia o Wire interno de "csr" para "csrSlave" (mantendo
# o IO externo com o nome "csr"), eliminando a colisão. De brinde, troca
# também o uso de "FPGAQueue(...) <> mem(i).writeData" por uma instância
# explícita de Module(new FPGAQueue(...)), evitando ambiguidade na
# geração da fila de write data.
###############################################################################

RUN cat > core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/AXIPlatformWrapper.scala <<'EOF'
package fpgatidbits.PlatformWrapper

import chisel3._
import chisel3.util._
import fpgatidbits.dma._
import fpgatidbits.regfile._
import fpgatidbits.axi._
import fpgatidbits.ocm._
import chisel3.experimental.noPrefix

// wrapper for AXI platforms

abstract class AXIPlatformWrapper(p: PlatformWrapperParams,
                                  instFxn: PlatformWrapperParams => GenericAccelerator)
  extends PlatformWrapper(p, instFxn) {

  val csrSlave = Wire(new AXILiteSlaveIF(p.memAddrBits, p.csrDataBits))
  val mem = Wire(Vec(p.numMemPorts, new AXIMasterIF(p.memAddrBits, p.memDataBits, p.memIDBits)))

  // add the actual external interfaces with the correct naming
  val extMemIf = noPrefix {VecInit(Seq.tabulate(p.numMemPorts) {idx => IO(new AXIExternalIF(p.memAddrBits, p.memDataBits, p.memIDBits)).suggestName(s"mem${idx}")}) }
  val extCsrIf = noPrefix {IO(Flipped(new AXILiteExternalIF(p.memAddrBits, p.csrDataBits))).suggestName("csr") }

  // Make the connections between the external and internal AXI interface
  for ((extIf, intIf) <- extMemIf zip mem ) {
    extIf.connect(intIf)
  }
  extCsrIf.connect(csrSlave)



    // memory port adapters and connections
  // TODO use accel numMemPorts and plug unused
  for(i <- 0 until accel.numMemPorts) {
    // instantiate AXI request and response adapters for the mem interface
    val mrp = p.toMemReqParams()
    // read requests
    val readReqAdp = Module(new AXIMemReqAdp(mrp)).io
    readReqAdp.genericReqIn <> accel.io.memPort(i).memRdReq
    readReqAdp.axiReqOut <> mem(i).readAddr
    // read responses
    val readRspAdp = Module(new AXIReadRspAdp(mrp)).io
    readRspAdp.axiReadRspIn <> mem(i).readData
    readRspAdp.genericRspOut <> accel.io.memPort(i).memRdRsp
    // write requests
    val writeReqAdp = Module(new AXIMemReqAdp(mrp)).io
    writeReqAdp.genericReqIn <> accel.io.memPort(i).memWrReq
    // write burst adapter
    val writeBurstAdp = Module(new AXIWriteBurstReqAdapter(
      p.memAddrBits, p.memDataBits, p.memIDBits
    )).io
    writeReqAdp.axiReqOut <> writeBurstAdp.in_writeAddr
    writeBurstAdp.in_writeData.bits.data := accel.io.memPort(i).memWrDat.bits
    // TODO fix this: forces all writes bytelanes valid!
    writeBurstAdp.in_writeData.bits.strb := ~0.U((p.memDataBits/8).W)
    // burst adapter will set this appropriately
    writeBurstAdp.in_writeData.bits.last := false.B
    writeBurstAdp.in_writeData.valid := accel.io.memPort(i).memWrDat.valid
    accel.io.memPort(i).memWrDat.ready := writeBurstAdp.in_writeData.ready
    writeBurstAdp.out_writeAddr <> mem(i).writeAddr
    // add a small write data queue to ensure we can provide both req ready and
    // data ready at the same time (otherwise this is up to the AXI slave)
    val q = Module(new FPGAQueue(new AXIWriteData(p.memDataBits), 2))
    q.io.enq <> writeBurstAdp.out_writeData
    q.io.deq <> mem(i).writeData
//    FPGAQueue(writeBurstAdp.out_writeData, 2) <> mem(i).writeData
    // write responses
    val writeRspAdp = Module(new AXIWriteRspAdp(mrp)).io
    writeRspAdp.axiWriteRspIn <> mem(i).writeResp
    writeRspAdp.genericRspOut <> accel.io.memPort(i).memWrRsp
  }

  // the accelerator may be using fewer memory ports than what the platform
  // exposes; plug the unused ones
  for(i <- accel.numMemPorts until p.numMemPorts) {
    println("Plugging unused memory port " + i.toString)
    mem(i).driveDefaults()
  }

  // AXI regfile read/write logic
  // slow and clumsy, but ctrl/status is not supposed to be performance-
  // critical anyway

  csrSlave.writeAddr.ready := false.B
  csrSlave.writeData.ready := false.B
  csrSlave.writeResp.valid := false.B
  csrSlave.writeResp.bits := 0.U
  csrSlave.readAddr.ready := false.B
  csrSlave.readData.valid := false.B
  csrSlave.readData.bits.data := regFile.extIF.readData.bits
  csrSlave.readData.bits.resp := 0.U

  regFile.extIF.cmd.valid := false.B
  regFile.extIF.cmd.bits.driveDefaults()

  val sRead :: sReadRsp :: sWrite :: sWriteD :: sWriteRsp :: Nil = Enum(5)
  val regState = RegInit(sRead)
  val regModeWrite = RegInit(false.B)
  val regRdReq = RegInit(false.B)
  val regRdAddr = RegInit(0.U(p.memAddrBits.W))
  val regWrReq = RegInit(false.B)
  val regWrAddr = RegInit(0.U(p.memAddrBits.W))
  val regWrData = RegInit(0.U(p.csrDataBits.W))
  // AXI typically uses byte addressing, whereas regFile indices are
  // element indices -- so the AXI addr needs to be divided by #bytes
  // in one element to get the regFile ind
  // Note that this permits reading/writing only the entire width of one
  // register
  val addrDiv = (p.csrDataBits/8).U

  when(!regModeWrite) {
    regFile.extIF.cmd.valid := regRdReq
    regFile.extIF.cmd.bits.read := true.B
    regFile.extIF.cmd.bits.regID := regRdAddr / addrDiv
  } .otherwise {
    regFile.extIF.cmd.valid := regWrReq
    regFile.extIF.cmd.bits.write := true.B
    regFile.extIF.cmd.bits.regID := regWrAddr / addrDiv
    regFile.extIF.cmd.bits.writeData := regWrData
  }

  switch(regState) {
    is(sRead) {
      csrSlave.readAddr.ready := true.B

      when(csrSlave.readAddr.valid) {
        regRdReq := true.B
        regRdAddr := csrSlave.readAddr.bits.addr
        regModeWrite := false.B
        regState := sReadRsp
      }.otherwise {
        regState := sWrite
      }
    }

    is(sReadRsp) {
      println("sReadRsp")
      csrSlave.readData.valid := regFile.extIF.readData.valid
      when (csrSlave.readData.ready & regFile.extIF.readData.valid) {
        regState := sWrite
        regRdReq := false.B
      }
    }

    is(sWrite) {
      csrSlave.writeAddr.ready := true.B

      when(csrSlave.writeAddr.valid) {
        regModeWrite := true.B
        regWrReq := false.B // need to wait until data is here
        regWrAddr := csrSlave.writeAddr.bits.addr
        regState := sWriteD
      } .otherwise {
        regState := sRead
      }
    }

    is(sWriteD) {
      csrSlave.writeData.ready := true.B
      when(csrSlave.writeData.valid) {
        regWrData := csrSlave.writeData.bits.data
        regWrReq := true.B // now we can set the request
        regState := sWriteRsp
      }
    }

    is(sWriteRsp) {
      csrSlave.writeResp.valid := true.B
      when(csrSlave.writeResp.ready) {
        regWrReq := false.B
        regState := sRead
      }
    }
  }
}
EOF

# O mesmo arquivo também existe em core/build/resources/main/... (gerado
# pelo processamento de resources do gradle a partir do diretório acima).
# Garante que as duas cópias fiquem idênticas e corrigidas, para o caso de
# essa pasta já existir de um build anterior / cache reaproveitado.
RUN mkdir -p core/build/resources/main/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi && \
    cp core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/AXIPlatformWrapper.scala \
       core/build/resources/main/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/AXIPlatformWrapper.scala

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

RUN useradd -m -s /bin/bash -G sudo lingua-franca

RUN echo 'lingua-franca:1234' | chpasswd

RUN echo 'lingua-franca ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/lingua-franca && \
    chmod 0440 /etc/sudoers.d/lingua-franca

# Dá posse do projeto
RUN chown -R lingua-franca:lingua-franca /opt/lingua-franca

# Copia os caches do sbt
RUN for d in .cache .ivy2 .sbt; do \
      if [ -d "/root/$d" ]; then \
        cp -a "/root/$d" "/home/lingua-franca/$d"; \
      fi; \
    done && \
    chown -R lingua-franca:lingua-franca /home/lingua-franca

RUN mkdir -p /workspace && \
    chown lingua-franca:lingua-franca /workspace

###############################################################################
# Troca para o usuário
###############################################################################

USER lingua-franca

WORKDIR /home/lingua-franca

###############################################################################
# Oh My Zsh
###############################################################################

RUN RUNZSH=no \
    CHSH=no \
    KEEP_ZSHRC=yes \
    sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"

###############################################################################
# Copia exemplos
###############################################################################

RUN mkdir -p ~/test && \
    cp -r /opt/lingua-franca/test/* ~/test/

WORKDIR /home/lingua-franca 
RUN git clone https://github.com/erlingrj/reactor-chisel-benchmarks.git

CMD ["/bin/zsh"]
