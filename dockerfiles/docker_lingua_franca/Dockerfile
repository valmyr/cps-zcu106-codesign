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
    file \
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

###############################################################################
# FASE 3 — PlatformWrapper.scala: campos genéricos de driver
#
# Adiciona platformMainDriverFile / platformDriverExtraFlags à classe base,
# com valores default seguros (não quebram os ~10 wrappers pré-existentes
# que não os sobrescrevem). Isso é o que permite ao mainCodesign (abaixo)
# compilar o driver certo, com as flags certas, sem hardcode por placa.
###############################################################################

RUN python3 << 'PYEOF'
import re
path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala'
with open(path) as f:
    content = f.read()
anchor = '  def platformDriverFiles: Seq[String]  // additional files'
patch = anchor + '''

  // Nome do .cpp que contém initPlatform/deinitPlatform, dentro de
  // platformDriverFiles. Por padrão, deduzido automaticamente: o primeiro
  // .cpp da lista.
  def platformMainDriverFile: String = platformDriverFiles.find(_.endsWith(".cpp")).getOrElse("")

  // Flags extras de compilação para platformMainDriverFile (ex: "-fpermissive"
  // para drivers legados de 32 bits). Vazio por padrão.
  def platformDriverExtraFlags: Seq[String] = Seq()'''
assert content.count(anchor) == 1, 'anchor not found exactly once'
content = content.replace(anchor, patch, 1)
with open(path, 'w') as f:
    f.write(content)
print('PlatformWrapper.scala patched OK')
PYEOF
###############################################################################
# reactor-chisel/src/main/scala/Main.scala — mainCodesign genérico
#
# Substitui o Main.scala do orquestrador Chisel: o ramo "else" (qualquer
# plataforma que não seja VerilatedTester) agora lê platformMainDriverFile /
# platformDriverExtraFlags da própria instância do wrapper, em vez de ter o
# nome do driver hardcoded. A instância é criada UMA vez, dentro do bloco
# passado por nome ao emitVerilog (necessário: instanciar um Module fora do
# contexto do Chisel Builder falha com "must be inside Builder context").
###############################################################################

RUN cat > core/src/main/resources/lib/chisel/reactor-chisel/src/main/scala/Main.scala <<'EOF'
package reactor

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

import scala.sys.process.Process
import java.nio.file.{Files, Paths}
import fpgatidbits.PlatformWrapper.VerilatedTesterWrapper
import fpgatidbits.TidbitsMakeUtils._
import fpgatidbits.PlatformWrapper._
import fpgatidbits.TidbitsMakeUtils

object Settings {
  def writeVerilogToFile(verilog: String, path: String) = {
    import java.io._
    val fname = path
    val f = new File(fname)
    if (!f.exists()) {
      f.getParentFile.mkdirs
      f.createNewFile()
    }
    val writer = new PrintWriter(f)
    writer.write(verilog)
    writer.close()
  }
}
 object ReactorChisel {
   def mainStandalone(mainReactorFunc: () => Reactor, targetDir: String, name: String, globalCfg: GlobalReactorConfig): Unit = {
     println("------------------------------------------------------------------------------------------------------")
     println("Running Chisel compiler to generate verilator project")
     println("------------------------------------------------------------------------------------------------------")

    val chiselArgs = Array("--target-dir", s"$targetDir/tmp")
    val verilog = (new chisel3.stage.ChiselStage).emitVerilog(new StandaloneTopReactor((mainReactorFunc))(globalCfg), chiselArgs)

     val saveLocation = targetDir + "/ReactorChisel.v"
     Settings.writeVerilogToFile(verilog, saveLocation)
     println(s"Wrote the generated verilog to `$saveLocation`")
     // Copy main cpp file for emulation
     resourceCopyBulk("simulator/standalone", targetDir, Seq("main.cpp", "Makefile"))
     println("------------------------------------------------------------------------------------------------------")
     println("Building verilator project")
     println("------------------------------------------------------------------------------------------------------")
     val result = Process("make", new java.io.File(targetDir)).!
   }

   def mainCodesign(platformInstFxn: PlatformInstFxn, mainReactorFunc: () => Reactor, mainReactorSwIOFunc: () => SwIO, targetDir: String, globalConfig: GlobalReactorConfig) = {
     println("------------------------------------------------------------------------------------------------------")
     println("Running Chisel compiler to generate verilator project")
     println("------------------------------------------------------------------------------------------------------")
     val chiselArgs = Array("--target-dir", s"$targetDir")
     var platformWrapperInst: PlatformWrapper = null
     (new chisel3.stage.ChiselStage).emitVerilog({
       platformWrapperInst = platformInstFxn(p => new CodesignTopReactor(p, mainReactorFunc, mainReactorSwIOFunc)(globalConfig), targetDir)
       platformWrapperInst
     }, chiselArgs)
     if (platformInstFxn.equals(TidbitsMakeUtils.platformMap("VerilatedTester"))) {
       resourceCopyBulk("simulator/codesign", targetDir, Seq("main.cpp", "Makefile"))
       println("------------------------------------------------------------------------------------------------------")
       println("Building verilator project")
       println("------------------------------------------------------------------------------------------------------")
       val result = Process("make lib", new java.io.File(targetDir)).!
       println(s"Library produced to $targetDir/lfFPGA.a.")
      } else {
       // Real hardware platform: cross-compile the driver into lfFPGA.a.
       // Driver file and flags are read from the wrapper instance itself
       // (platformMainDriverFile / platformDriverExtraFlags), not hardcoded here.
       println("------------------------------------------------------------------------------------------------------")
       println("Cross-compiling hardware driver for real FPGA platform")
       println("------------------------------------------------------------------------------------------------------")
       val cxx = sys.env.getOrElse("LF_FPGA_CXX", "aarch64-linux-gnu-g++")
       val ar = sys.env.getOrElse("LF_FPGA_AR", "aarch64-linux-gnu-ar")
       val targetDirFile = new java.io.File(targetDir)

       val mainDriverFile = platformWrapperInst.platformMainDriverFile
       val extraFlags = platformWrapperInst.platformDriverExtraFlags
       if (mainDriverFile.isEmpty) {
         throw new RuntimeException("No platformMainDriverFile (.cpp) found in platformDriverFiles for this platform")
       }
       val objFile = mainDriverFile.stripSuffix(".cpp") + ".o"
       println(s"Compiling driver: $mainDriverFile (flags: ${extraFlags.mkString(" ")})")
       val compileResult = Process(
         Seq(cxx) ++ extraFlags ++ Seq("-c", mainDriverFile, "-o", objFile),
         targetDirFile
       ).!
       if (compileResult != 0) {
         throw new RuntimeException(s"Failed to cross-compile $mainDriverFile (exit code $compileResult)")
       }
       val arResult = Process(
         Seq(ar, "rcs", "lfFPGA.a", objFile),
         targetDirFile
       ).!
       if (arResult != 0) {
         throw new RuntimeException(s"Failed to archive lfFPGA.a (exit code $arResult)")
       }
       println("------------------------------------------------------------------------------------------------------")
       println(s"Library produced to $targetDir/lfFPGA.a.")
       println("------------------------------------------------------------------------------------------------------")
     }



   }
 }
EOF

###############################################################################
# PYNQZCU104CCWrapper.scala — copia de fato os arquivos de driver
#
# O wrapper original só DEFINIA platformDriverFiles, nunca chamava
# resourceCopyBulk/generateRegDriver — por isso build/ nunca continha
# platform.h/CodesignTopReactor.hpp/driver para essa plataforma. Também
# sobrescreve platformDriverExtraFlags com "-fpermissive", necessário
# porque linuxphysregdriver.hpp foi escrito para ARM 32 bits (cast de
# void* para unsigned int) e GCC rejeita isso por padrão em ARM64.
###############################################################################

RUN cat > core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/PYNQZCU104CCWrapper.scala <<'EOF'
package fpgatidbits.PlatformWrapper
import chisel3._
import fpgatidbits.TidbitsMakeUtils._
object PYNQZCU104CCParams extends PlatformWrapperParams {
  val platformName = "PYNQZCU104CC"
  val memAddrBits = 32
  val memDataBits = 64
  val memIDBits = 6
  val memMetaBits = 1
  val numMemPorts = 1
  val sameIDInOrder = true
  val typicalMemLatencyCycles = 32
  val burstBeats = 8
  val coherentMem = true
}
class PYNQZCU104CCWrapper(instFxn: PlatformWrapperParams => GenericAccelerator, targetDir: String)
  extends AXIPlatformWrapper(PYNQZCU104Params, instFxn) {
  val platformDriverFiles = baseDriverFiles ++ Array[String](
    "platform-zedboard-linux.cpp", "linuxphysregdriver.hpp", "axiregdriver.hpp"
  )
  override def platformDriverExtraFlags: Seq[String] = Seq("-fpermissive")
  suggestName("PYNQZCU104CCWrapper")
  mem(0).readAddr.bits.cache := "b1100".U
  mem(0).writeAddr.bits.cache := "b1100".U
  mem(0).readAddr.bits.prot := "b10".U
  mem(0).writeAddr.bits.prot := "b10".U
  generateRegDriver(targetDir)
  resourceCopyBulk("cpp/platform-wrapper-regdriver/", targetDir, platformDriverFiles)
  println(s"=======> Driver files copied to ${targetDir}")
}
EOF

###############################################################################
# ZCU106Wrapper.scala — wrapper próprio (mesmo chip do PYNQZCU104CC, mas
# identidade clara para manutenção futura). Usa o driver canônico de 64
# bits (UioDriver / platform-mpsoc-uio.cpp), sem necessidade de flags.
###############################################################################

RUN cat > core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/ZCU106Wrapper.scala <<'EOF'
package fpgatidbits.PlatformWrapper
import chisel3._
import fpgatidbits.TidbitsMakeUtils._
object ZCU106Params extends PlatformWrapperParams {
  val platformName = "ZCU106"
  val memAddrBits = 32
  val memDataBits = 64
  val memIDBits = 6
  val memMetaBits = 1
  val numMemPorts = 1
  val sameIDInOrder = true
  val typicalMemLatencyCycles = 32
  val burstBeats = 8
  val coherentMem = true
}
class ZCU106Wrapper(instFxn: PlatformWrapperParams => GenericAccelerator, targetDir: String)
  extends AXIPlatformWrapper(PYNQZCU104Params, instFxn) {
  val platformDriverFiles = baseDriverFiles ++ Array[String](
    "platform-mpsoc-uio.cpp", "uiodriver.hpp"
  )
  suggestName("ZCU106Wrapper")
  mem(0).readAddr.bits.cache := "b1100".U
  mem(0).writeAddr.bits.cache := "b1100".U
  mem(0).readAddr.bits.prot := "b10".U
  mem(0).writeAddr.bits.prot := "b10".U
  generateRegDriver(targetDir)
  resourceCopyBulk("cpp/platform-wrapper-regdriver/", targetDir, platformDriverFiles)
  println(s"=======> Driver files copied to ${targetDir}")
}
EOF

###############################################################################
# uiodriver.hpp / platform-mpsoc-uio.cpp — driver canônico de 64 bits,
# via UIO (/dev/uioN + mmap), sem depender de xlnk (ausente em kernels
# linux-xlnx modernos). Diretórios/nomes de dispositivo configuráveis por
# macro, com defaults de produção (/sys/class/uio, /dev) — permite testar
# a lógica isoladamente (ver test_uiodriver.cpp) sem afetar produção.
###############################################################################

RUN cat > core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/uiodriver.hpp <<'EOF'
#ifndef UIODRIVER_H
#define UIODRIVER_H

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "wrapperregdriver.h"

#ifndef LF_FPGA_UIO_NAME
#define LF_FPGA_UIO_NAME "lf_fpga_regs"
#endif

#ifndef LF_FPGA_UIO_MAP_SIZE
#define LF_FPGA_UIO_MAP_SIZE 0x10000
#endif

// Diretório base do sysfs para dispositivos UIO. Configurável via -D para
// permitir testes com uma árvore de diretório falsa, sem precisar de
// acesso root a /sys/class/uio real.
#ifndef LF_FPGA_UIO_SYSFS_DIR
#define LF_FPGA_UIO_SYSFS_DIR "/sys/class/uio"
#endif

// Diretório base para os device nodes (/dev/uioN). Configurável pelo mesmo
// motivo do acima.
#ifndef LF_FPGA_UIO_DEV_DIR
#define LF_FPGA_UIO_DEV_DIR "/dev"
#endif

class UioDriver : public WrapperRegDriver {
public:
  UioDriver(const char *uioNameHint = LF_FPGA_UIO_NAME,
            size_t mapSize = LF_FPGA_UIO_MAP_SIZE)
      : m_mapSize(mapSize), m_reg(nullptr), m_fd(-1) {
    std::string devPath = findUioDevice(uioNameHint);
    if (devPath.empty()) {
      throw "UioDriver: no UIO device found matching name hint";
    }

    m_fd = open(devPath.c_str(), O_RDWR | O_SYNC);
    if (m_fd < 0) {
      throw "UioDriver: failed to open UIO device";
    }

    void *mapped = mmap(nullptr, m_mapSize, PROT_READ | PROT_WRITE,
                         MAP_SHARED, m_fd, 0);
    if (mapped == MAP_FAILED) {
      close(m_fd);
      throw "UioDriver: mmap failed";
    }
    m_reg = reinterpret_cast<volatile AccelReg *>(mapped);
  }

  virtual ~UioDriver() {
    if (m_reg) {
      munmap((void *)m_reg, m_mapSize);
    }
    if (m_fd >= 0) {
      close(m_fd);
    }
  }

  virtual std::string platformID() { return "UioDriver"; }

  virtual void writeReg(unsigned int regInd, AccelReg regValue) {
    m_reg[regInd] = regValue;
  }

  virtual AccelReg readReg(unsigned int regInd) { return m_reg[regInd]; }

  virtual void attach(const char *name) { (void)name; }
  virtual void detach() {}

  // Exposta para testes: mesma lógica de busca usada no construtor.
  static std::string findUioDevice(const std::string &nameHint) {
    const char *sysDir = LF_FPGA_UIO_SYSFS_DIR;
    DIR *dir = opendir(sysDir);
    if (!dir) return "";

    std::string fallback;
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
      std::string uioName = entry->d_name;
      if (uioName.substr(0, 3) != "uio") continue;

      std::string namePath = std::string(sysDir) + "/" + uioName + "/name";
      FILE *f = fopen(namePath.c_str(), "r");
      if (!f) continue;
      char buf[256] = {0};
      if (fgets(buf, sizeof(buf), f)) {
        std::string devName(buf);
        devName.erase(devName.find_last_not_of("\n\r") + 1);
        if (devName.find(nameHint) != std::string::npos) {
          fclose(f);
          closedir(dir);
          return std::string(LF_FPGA_UIO_DEV_DIR) + "/" + uioName;
        }
      }
      fclose(f);
      if (fallback.empty()) fallback = std::string(LF_FPGA_UIO_DEV_DIR) + "/" + uioName;
    }
    closedir(dir);
    return fallback;
  }

private:
  size_t m_mapSize;
  volatile AccelReg *m_reg;
  int m_fd;
};

#endif // UIODRIVER_H
EOF

RUN cat > core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/platform-mpsoc-uio.cpp <<'EOF'
#include "platform.h"
#include "uiodriver.hpp"

// Hint "ZCU106Wrapper" bate por substring com o nome real do dispositivo
// exposto em /sys/class/uio/uioN/name pelo sdtgen (ex.: "ZCU106Wrapper@a0000000"),
// confirmado em bring-up real na placa. O macro LF_FPGA_UIO_NAME por si só
// ("lf_fpga_regs") nunca bate com o nome que o device tree gerado usa de
// fato -- por isso o hint precisa ser passado explicitamente aqui.
WrapperRegDriver *initPlatform(bool tracing) {
  (void)tracing;
  return new UioDriver("ZCU106Wrapper");
}

void deinitPlatform(WrapperRegDriver *driver) {
  if (driver) {
    delete driver;
  }
}
EOF
###############################################################################
# CodesignGenerator.kt — compila o software automaticamente para QUALQUER
# placa, não só VerilatedTester (simulação).
#
# Original: só compilava o software gerado quando fpgaBoard == "VerilatedTester",
# exigindo um passo manual extra (lfc --target-compiler ... _SwTop.lf) para
# qualquer placa real. Esta edição remove essa restrição: swEmitter.compile()
# roda sempre, usando o --target-compiler passado na linha de comando do lfc
# (ou o compilador nativo do sistema, se nenhum for especificado).
###############################################################################

RUN python3 << 'PYEOF'
path = 'core/src/main/kotlin/org/lflang/generator/codesign/CodesignGenerator.kt'
with open(path) as f:
    content = f.read()

anchor = 'if (targetConfig.fpgaBoard.equals("VerilatedTester")) {'
patch = 'if (true) { // PATCHED: always compile software, regardless of fpgaBoard (see backlog)'

assert content.count(anchor) == 1, 'anchor not found exactly once in CodesignGenerator.kt'
content = content.replace(anchor, patch, 1)

with open(path, 'w') as f:
    f.write(content)
print('CodesignGenerator.kt patched OK')
PYEOF

###############################################################################
# fpgatidbits/Main.scala — registro de ZCU106 no platformMap e fpgaPartMap
#
# Edição cirúrgica (não substitui o arquivo inteiro, que tem outras seções
# não documentadas nesta conversa) via Python, ancorada nas linhas exatas
# do PYNQZCU104CC já existentes.
###############################################################################

RUN python3 << 'PYEOF'
path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/Main.scala'
with open(path) as f:
    content = f.read()

anchor1 = '    "PYNQZCU104CC" -> {(f, targetDir) => new PYNQZCU104CCWrapper(f, targetDir)},'
patch1 = anchor1 + '\n    "ZCU106" -> {(f, targetDir) => new ZCU106Wrapper(f, targetDir)},'
assert content.count(anchor1) == 1, 'platformMap anchor not found exactly once'
content = content.replace(anchor1, patch1, 1)

anchor2 = '    "PYNQZCU104CC" -> "xczu7ev-ffvc1156-2-e",'
patch2 = anchor2 + '\n    "ZCU106" -> "xczu7ev-ffvc1156-2-e",'
assert content.count(anchor2) == 1, 'fpgaPartMap anchor not found exactly once'
content = content.replace(anchor2, patch2, 1)

with open(path, 'w') as f:
    f.write(content)
print('fpgatidbits/Main.scala patched OK')
PYEOF

###############################################################################
# CodesignSwEmitter.kt — propaga --target-compiler para o .lf de software
# gerado internamente.
#
# CodesignSwEmitter.compile() cria um novo MainContext/TargetConfig do zero,
# usando Properties() vazio como cliArgs -- isso descarta qualquer
# --target-compiler passado na linha de comando original do lfc. O novo
# TargetConfig só é reconstruído a partir do que está ESCRITO no arquivo
# _SwTop.lf gerado (via TargetProperty.COMPILER, keyword "compiler" no .lf).
# Esta correção faz generateTarget() escrever essa diretiva no .lf gerado,
# propagando o compilador certo através dessa segunda passada de geração.
###############################################################################

RUN python3 << 'PYEOF'
path = 'core/src/main/kotlin/org/lflang/generator/codesign/CodesignSwEmitter.kt'
with open(path) as f:
    content = f.read()

anchor = '''        if (targetConfig.cmakeBuildType != null) {
            builder.appendLine("  build-type: ${targetConfig.cmakeBuildType.name},")
        }'''

patch = anchor + '''
        if (targetConfig.compiler.isNotEmpty()) {
            builder.appendLine("  compiler: \\"${targetConfig.compiler}\\",")
        }'''

assert content.count(anchor) == 1, 'anchor not found exactly once in CodesignSwEmitter.kt'
content = content.replace(anchor, patch, 1)

with open(path, 'w') as f:
    f.write(content)
print('CodesignSwEmitter.kt patched OK')
PYEOF


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
# Tema do Oh My Zsh
###############################################################################

RUN sed -i 's/^ZSH_THEME=.*/ZSH_THEME="jonathan"/' ~/.zshrc

###############################################################################
# Copia exemplos
###############################################################################

RUN mkdir -p ~/test && \
    cp -r /opt/lingua-franca/test/* ~/test/

WORKDIR /home/lingua-franca 
RUN git clone https://github.com/erlingrj/reactor-chisel-benchmarks.git
RUN mkdir -p /tmp/smoketest4 && cat > /tmp/smoketest4/smoke4.lf <<'EOF'
target Codesign {
  fpgaBoard: ZCU106
}
reactor Fpga {
  input in: {= UInt(32.W) =}
  output out: {= UInt(32.W) =}
  reaction(in) -> out {=
    lf_set(out, lf_get(in))
  =}
}
reactor Sw {
  input in: uint32_t
  output out: uint32_t
  reaction(startup) -> out {= out.set(1); =}
  reaction(in) {= std::cout << "Got: " << *in.get() << std::endl; =}
}
main reactor {
  @fpga
  fpga = new Fpga()
  sw = new Sw()
  sw.out, fpga.out -> fpga.in, sw.in
}
EOF

RUN cd /tmp/smoketest4 && \
    export PATH=/opt/lingua-franca/build/install/lf-cli/bin:$PATH && \
    lfc --target-compiler aarch64-linux-gnu-g++ smoke4.lf && \
    file codesign-gen/smoke4/bin/_SwTop | grep -q "ARM aarch64" && \
    echo "SMOKE TEST 4 PASSED - software compiled automatically for ZCU106" && \
    rm -rf /tmp/smoketest4

RUN grep -q 'ZSH_THEME="jonathan"' ~/.zshrc && echo "Tema jonathan configurado com sucesso" || (echo "ERRO: tema nao foi aplicado" && exit 1)

WORKDIR /home/lingua-franca

RUN cat <<'EOF' > Makefile
# Makefile para compilar 
#Caso I deploy em FPGA: make all PROJETO=loopback.lf ARCH=arm
#Caso II simulacao	  : make all PROJETO=loopback.lf ARCH=x86_64
#É necessário especificar a extensão do arquivo; isso é uma escolha arbitrária exclusivamente minha.
VERMELHO = \033[1;31m
VERDE    = \033[1;32m
AMARELO  = \033[1;33m
RESET    = \033[0m
PROJETO  ?= loopback.lf
ARCH ?= arm
TIMESTAMP := $(shell date +%Y%m%d_%H%M)
ifeq ($(ARCH),arm)
    TARGET_COMPILER := aarch64-linux-gnu-g++
    MODO := deploy
else ifeq ($(ARCH),x86_64)
    TARGET_COMPILER := g++
    MODO := simulacao
else
    $(error ARCH inválido: '$(ARCH)'. Use ARCH=arm ou ARCH=x86_64)
endif
.PHONY: all compiler deploy clean check-string
check-string:clean
	@if [ "$(MODO)" = "simulacao" ]; then \
		printf "$(VERDE)Modo simulação detectado no arquivo!$(RESET)\n"; \
		if [ "$(ARCH)" != "x86_64" ]; then \
			printf "$(VERMELHO)ERRO: arquivo indica modo simulação, mas ARCH=$(ARCH). Use ARCH=x86_64$(RESET)\n"; \
			exit 1; \
		fi; \
	else \
		printf "$(VERMELHO)Modo implementação em FPGA detectado!$(RESET)\n"; \
		if [ "$(ARCH)" != "arm" ]; then \
			printf "$(VERMELHO)ERRO: arquivo indica FPGA, mas ARCH=$(ARCH). Use ARCH=arm$(RESET)\n"; \
			exit 1; \
		fi; \
	fi
all: deploy
compiler: check-string
	@printf "$(VERDE)Compilando Reatores...$(RESET)\n"
	@printf "$(VERMELHO)lfc --target-compiler=$(TARGET_COMPILER) $(PROJETO)$(RESET)\n"
	lfc --target-compiler=$(TARGET_COMPILER) $(PROJETO)
	@printf "$(VERDE)Fim do processo$(RESET)\n"
deploy: compiler
	@if [ "$(MODO)" = "simulacao" ]; then \
		printf "$(AMARELO)Modo simulação detectado — deploy (empacotamento) não será executado.$(RESET)\n"; \
	else \
		rm -rf ./deploy_all_files; \
		mkdir -p ./deploy_all_files; \
		rm -rf ./deploy_all_files/*; \
		mkdir -p deploy_all_files/bin;\
		printf "$(VERDE)Encapsulando implementacao Software e Hardware...$(RESET)\n"; \
		printf "$(VERDE)Copiando arquivos Verilog...$(RESET)\n"; \
		cp ./codesign-gen/$(basename $(PROJETO))/src-gen/hardware/_FpgaTop/build/ZCU106Wrapper.v ./deploy_all_files ; \
		printf "$(VERDE)Copiando arquivos da biblioteca...$(RESET)\n"; \
		cp -r ./codesign-gen/$(basename $(PROJETO))/lib ./deploy_all_files ; \
		cp -r ./bin/$(basename $(PROJETO)) ./deploy_all_files/bin/ ; \
		cp -r ./$(PROJETO) ./deploy_all_files/$(basename $(PROJETO))_SNAPSHOT_$(TIMESTAMP).lf ; \
		printf "$(VERDE)Compactando...$(RESET)\n"; \
		tar -czf ./$(basename $(PROJETO))_$(TIMESTAMP)_deploy.tar.gz \
			./deploy_all_files/ZCU106Wrapper.v \
			./deploy_all_files/lib \
			./deploy_all_files/bin/$(basename $(PROJETO)) \
			./deploy_all_files/$(basename $(PROJETO))_SNAPSHOT_$(TIMESTAMP).lf; \
		printf "$(VERMELHO)Fim do encapsulamento$(RESET)\n"; \
	fi
clean:
	@printf "$(VERDE)Limpando artefatos...$(RESET)\n"
	@printf "$(VERMELHO)rm -rf codesign-gen$(RESET)\n"
	rm -rf codesign-gen
EOF

CMD ["/bin/zsh"]
