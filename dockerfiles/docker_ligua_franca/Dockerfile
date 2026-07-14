###############################################################################
# FASE 3 — PlatformWrapper.scala: campos genéricos de driver
#
# Adiciona platformMainDriverFile / platformDriverExtraFlags à classe base,
# com valores default seguros (não quebram os ~10 wrappers pré-existentes
# que não os sobrescrevem). Isso é o que permite ao mainCodesign (abaixo)
# compilar o driver certo, com as flags certas, sem hardcode por placa.
###############################################################################

RUN python3 -c "
import re
path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala'
with open(path) as f:
    content = f.read()
anchor = '  def platformDriverFiles: Seq[String]  // additional files'
patch = anchor + '''

  // Nome do .cpp que contém initPlatform/deinitPlatform, dentro de
  // platformDriverFiles. Por padrão, deduzido automaticamente: o primeiro
  // .cpp da lista.
  def platformMainDriverFile: String = platformDriverFiles.find(_.endsWith(\".cpp\")).getOrElse(\"\")

  // Flags extras de compilação para platformMainDriverFile (ex: \"-fpermissive\"
  // para drivers legados de 32 bits). Vazio por padrão.
  def platformDriverExtraFlags: Seq[String] = Seq()'''
assert content.count(anchor) == 1, 'anchor not found exactly once'
content = content.replace(anchor, patch, 1)
with open(path, 'w') as f:
    f.write(content)
print('PlatformWrapper.scala patched OK')
"

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
# fpgatidbits/Main.scala — registro de ZCU106 no platformMap e fpgaPartMap
#
# Edição cirúrgica (não substitui o arquivo inteiro, que tem outras seções
# não documentadas nesta conversa) via Python, ancorada nas linhas exatas
# do PYNQZCU104CC já existentes.
###############################################################################

RUN python3 -c "
path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/Main.scala'
with open(path) as f:
    content = f.read()

anchor1 = '    \"PYNQZCU104CC\" -> {(f, targetDir) => new PYNQZCU104CCWrapper(f, targetDir)},'
patch1 = anchor1 + '\n    \"ZCU106\" -> {(f, targetDir) => new ZCU106Wrapper(f, targetDir)},'
assert content.count(anchor1) == 1, 'platformMap anchor not found exactly once'
content = content.replace(anchor1, patch1, 1)

anchor2 = '    \"PYNQZCU104CC\" -> \"xczu7ev-ffvc1156-2-e\",'
patch2 = anchor2 + '\n    \"ZCU106\" -> \"xczu7ev-ffvc1156-2-e\",'
assert content.count(anchor2) == 1, 'fpgaPartMap anchor not found exactly once'
content = content.replace(anchor2, patch2, 1)

with open(path, 'w') as f:
    f.write(content)
print('fpgatidbits/Main.scala patched OK')
"
