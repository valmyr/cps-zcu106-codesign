Backlog — Lingua Franca Codesign + Chisel + FPGA Real (ZCU106)
Continuação do projeto de codesign LF + Chisel + FPGA, migrando do fluxo de simulação (VerilatedTester) para hardware real na placa Xilinx ZCU106 (Zynq UltraScale+ MPSoC), com geração da biblioteca lfFPGA.a associada ao driver de acesso via UIO (sem depender do driver descontinuado xlnk).

1. Objetivo desta fase
A partir do marco já alcançado (LF + Chisel + Verilator + C++ ARM em simulação, ver backlog anterior), o objetivo passou a ser:

Substituir o FPGA virtual (TesterWrapper) pelo hardware físico (PYNQZCU104CCWrapper), gerando lfFPGA.a de fato associado à MPSoC (ZCU106), com um driver de registradores compatível com o kernel linux-xlnx moderno da placa.

2. Contexto de ambiente
Placa alvo: ZCU106 (mapeada via wrapper PYNQZCU104CC, part number xczu7ev-ffvc1156-2-e).
BSP: PetaLinux 2025.2, kernel linux-xlnx 6.12.40+git+v2025.2.
Ambiente de desenvolvimento: container Docker, com gcc-aarch64-linux-gnu / g++-aarch64-linux-gnu instalados (cross-compiler puro, sem SDK/sysroot completo da Xilinx).
RAM da placa: 4GB (2GB banco baixo 0x0–0x7FFFFFFF, 2GB banco alto 0x800000000–0x87FFFFFFF).
3. Problema 1 — Build do software falha por falta do hardware
Erro
No rule to make target '.../src-gen/hardware/_FpgaTop/build/lfFPGA.a'
fatal error: platform.h: No such file or directory
Causa
O pipeline de hardware (Chisel → Verilator) precisa rodar antes do pipeline de software, gerando platform.h, CodesignTopReactor.hpp e lfFPGA.a dentro de src-gen/hardware/_FpgaTop/build/.

Correção
cd ~/codesign-gen/main_target_board/src-gen/hardware/_FpgaTop
sbt run
cd build
make clean && make lib
4. Problema 2 — Wrapper de plataforma trocado sem querer
O Main.scala do reactor Chisel foi regenerado apontando para platformMap("PYNQZCU104CC") em vez de "VerilatedTester", quebrando o fluxo de simulação (gerando PYNQZCU104CCWrapper.v sem platform.h/driver/Makefile).

Lição: src/main/scala/Main.scala é regenerado do zero em cada geração do hardware LF — qualquer ajuste manual de plataforma precisa ser reaplicado, ou versionado à parte.

5. Decisão: migrar de fato para hardware real (não voltar à simulação)
A partir daqui o objetivo passou a ser gerar lfFPGA.a para a ZCU106 de verdade, não apenas restaurar a simulação.

5.1 Por que lfFPGA.a de simulação não se aplica a hardware real
Simulação (VerilatedTester)	Hardware real (PYNQZCU104CC)
RTL	Compilado como C++ (Verilator)	Vira bitstream (Vivado), carregado na PL
lfFPGA.a	Modelo de hardware inteiro em C++	Só o driver de acesso a registradores
Build	make lib (Makefile gerado)	Não existe Makefile — precisa cross-compilar manualmente
6. Problema 3 — PYNQZCU104CCWrapper não copiava nenhum arquivo de driver
Diagnóstico
Comparando com PYNQWrapper/ZedBoardWrapper/GenericSDAccelWrapper, que chamam resourceCopyBulk(...) e generateRegDriver(targetDir) no corpo da classe, PYNQZCU104CCWrapper só definia platformDriverFiles, sem nunca copiar nada. Por isso build/ só continha .v/.fir/.anno.json.

Correção aplicada em PYNQZCU104CCWrapper.scala
package fpgatidbits.PlatformWrapper
import chisel3._
import fpgatidbits.TidbitsMakeUtils._
// platform wrapper for PYNQ on Ultra-96 with one cache-coherent port
object PYNQZCU104CCParams extends PlatformWrapperParams {
  val platformName = "PYNQZCU104CC"
  val memAddrBits = 32
  val memDataBits = 64
  val memIDBits = 6
  val memMetaBits = 1
  val numMemPorts = 1
  val sameIDInOrder = true
  val typicalMemLatencyCycles = 32
  val burstBeats = 8  // TODO why cap bursts at 8? AXI can do more
  val coherentMem = true
}
class PYNQZCU104CCWrapper(instFxn: PlatformWrapperParams => GenericAccelerator, targetDir: String)
  extends AXIPlatformWrapper(PYNQZCU104Params, instFxn) {
  val platformDriverFiles = baseDriverFiles ++ Array[String](
    "platform-zcu106.cpp", "uiodriver.hpp"
  )
  suggestName("PYNQZCU104CCWrapper")
  // override AXI MM signals for cache coherency
  mem(0).readAddr.bits.cache := "b1100".U
  mem(0).writeAddr.bits.cache := "b1100".U
  mem(0).readAddr.bits.prot := "b10".U
  mem(0).writeAddr.bits.prot := "b10".U
  // Generate the RegFile driver
  generateRegDriver(targetDir)
  // Copy over the other needed files
  resourceCopyBulk("cpp/platform-wrapper-regdriver/", targetDir, platformDriverFiles)
  println(s"=======> Driver files copied to ${targetDir}")
}
Após essa correção, sbt run passou a gerar corretamente:

build/
|-- PYNQZCU104CCWrapper.v / .fir / .anno.json
|-- CodesignTopReactor.hpp   (mapeamento real dos registradores do reactor)
|-- platform.h
|-- wrapperregdriver.h
|-- platform-zcu106.cpp      (driver novo, ver seção 8)
|-- uiodriver.hpp            (driver novo, ver seção 8)
Registradores mapeados (confirmados no log do sbt run)
Registrador	Índice
signature	0
terminate	1
ports_out_data	2
ports_out_present	3
ports_in_data	4
ports_in_present	5
cmd	6
coordination_shutdownCommand_independent	7
coordination_shutdownCommand_valid	8
coordination_logicalTagComplete	9
coordination_tagAdvanceGrant	10
coordination_nextEventTag	11
7. Problema 4 — Driver xlnk não existe no kernel da placa
Investigação
platformDriverFiles originalmente apontava para platform-mpsoc-cc-xlnk.cpp / xlnkdriver.hpp, que dependem de libxlnk_cma.h (driver de kernel xlnk, descontinuado pela Xilinx).

Confirmação empírica (no código-fonte do próprio BSP)
find xilinx-zcu106-2025.2/build/tmp/work/.../linux-xlnx/6.12.40+git+v2025.2/.../.config \
  | xargs grep -l "XLNK"
# → nenhum resultado: driver xlnk não configurado no kernel
aarch64-linux-gnu-g++ -c platform-mpsoc-cc-xlnk.cpp -o /tmp/test.o
# → fatal error: libxlnk_cma.h: No such file or directory
Alternativa disponível, confirmada na config do kernel
CONFIG_UIO=y
CONFIG_UIO_PDRV_GENIRQ=m
CONFIG_UIO_DMEM_GENIRQ=m
# CONFIG_UDMABUF is not set
CONFIG_DMABUF_HEAPS=y
CONFIG_DMABUF_HEAPS_CMA=y
uio_pdrv_genirq está disponível — caminho escolhido para acesso aos registradores via mmap() em /dev/uioN.

Confirmação do binding exato (direto na fonte do driver, não por suposição)
// uio_pdrv_genirq.c
module_param_string(of_id, uio_of_genirq_match[0].compatible, 128, 0);
→ compatible = "generic-uio" no device tree, mais o bootarg:

uio_pdrv_genirq.of_id=generic-uio
uio_dmem_genirq.c, nesta árvore, não tem esse mecanismo de of_id — não bindaria via device tree. Como o CodesignTopReactor.hpp gerado só usa registradores escalares (readReg/writeReg, sem allocAccelBuffer), essa limitação não impede o projeto: um único nó UIO, só para os registradores, é suficiente.

8. Driver novo: UioDriver + platform-zcu106.cpp
Implementam a interface WrapperRegDriver (confirmada em wrapperregdriver.h) usando mmap() de /dev/uioN, sem nenhuma dependência de bibliotecas proprietárias da Xilinx.

uiodriver.hpp
// Driver de registradores via UIO genérico (uio_pdrv_genirq), para uso em
// kernels linux-xlnx onde o módulo xlnk foi removido (confirmado ausente
// no kernel 6.12.40+git+v2025.2 da ZCU106 - ver backlog do projeto).
//
// Requer:
//   - Overlay de device tree com um nó compatible = "generic-uio" mapeando
//     os registradores do CodesignTopReactor (ver lf_fpga_uio.dtsi).
//   - Bootarg: uio_pdrv_genirq.of_id=generic-uio
//   - Módulo carregado: modprobe uio_pdrv_genirq
//
// Este driver implementa apenas writeReg/readReg/attach/detach, pois o
// CodesignTopReactor gerado para este reactor troca dados via registradores
// escalares (ports_in_data/ports_out_data), sem uso de allocAccelBuffer.

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

// Nome esperado do nó no device tree (rótulo lf_fpga_regs -> aparece em
// /sys/class/uio/uioN/name). Ajuste se o rótulo do overlay for outro.
#ifndef LF_FPGA_UIO_NAME
#define LF_FPGA_UIO_NAME "lf_fpga_regs"
#endif

// Tamanho da janela de registradores mapeada (deve bater com o "reg" do
// device tree, ex.: 0x10000).
#ifndef LF_FPGA_UIO_MAP_SIZE
#define LF_FPGA_UIO_MAP_SIZE 0x10000
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

    // UIO map0 sempre começa no offset (mapNumber * pagesize) do arquivo
    // de dispositivo; para o primeiro (e único) map, offset = 0.
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

  // attach/detach: nada especial a fazer aqui - o bitstream já deve estar
  // carregado na FPGA (via FPGA Manager) antes de o programa iniciar.
  // O check de assinatura (readReg(0)) já acontece no construtor do
  // CodesignTopReactor.
  virtual void attach(const char *name) { (void)name; }
  virtual void detach() {}

private:
  size_t m_mapSize;
  volatile AccelReg *m_reg;
  int m_fd;

  // Procura em /sys/class/uio/uioN/name pelo nome do device tree e
  // retorna o caminho /dev/uioN correspondente. Se nameHint for nullptr
  // ou não encontrado, cai para /dev/uio0 como último recurso.
  static std::string findUioDevice(const std::string &nameHint) {
    const char *sysDir = "/sys/class/uio";
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
        // remove newline
        devName.erase(devName.find_last_not_of("\n\r") + 1);
        if (devName.find(nameHint) != std::string::npos) {
          fclose(f);
          closedir(dir);
          return "/dev/" + uioName;
        }
      }
      fclose(f);
      if (fallback.empty()) fallback = "/dev/" + uioName;
    }
    closedir(dir);
    return fallback; // último recurso: primeiro uioN encontrado
  }
};

#endif // UIODRIVER_H
platform-zcu106.cpp
// Implementação de plataforma para ZCU106 via UIO genérico.
// Substitui platform-mpsoc-cc-xlnk.cpp (que depende de libxlnk_cma,
// indisponível no kernel linux-xlnx 6.12.40+git+v2025.2 desta placa).

#include "platform.h"
#include "uiodriver.hpp"

WrapperRegDriver *initPlatform(bool tracing) {
  (void)tracing;
  return new UioDriver();
}

void deinitPlatform(WrapperRegDriver *driver) {
  if (driver) {
    delete driver;
  }
}
Onde os arquivos foram colocados
reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/
|-- platform-zcu106.cpp   (novo)
|-- uiodriver.hpp         (novo)
|-- platform-mpsoc-cc-xlnk.cpp   (original, mantido, não usado)
|-- xlnkdriver.hpp               (original, mantido, não usado)
Validação de compilação isolada
aarch64-linux-gnu-g++ -c platform-zcu106.cpp -o /tmp/test.o
file /tmp/test.o
# ELF 64-bit LSB relocatable, ARM aarch64, version 1 (SYSV), not stripped
9. Overlay de device tree — lf_fpga_uio.dtsi
Expõe os registradores do reactor via generic-uio. Endereço confirmado no Address Editor do Vivado: 0xa0000000, tamanho assumido 0x10000 (ajustar se o Vivado mostrar outro valor).

/*
 * Overlay para o reactor Lingua Franca / Chisel (TesterWrapper / PYNQZCU104CCWrapper)
 * na ZCU106 — kernel linux-xlnx 6.12.40+git+v2025.2.
 *
 * uio_pdrv_genirq: of_match_table vazia por padrão, populada via param de
 * módulo `of_id`. Precisa do bootarg:
 *     uio_pdrv_genirq.of_id=generic-uio
 *
 * uio_dmem_genirq NÃO é usado (match table vazia nesta árvore, sem suporte
 * a `of_id` — não bindaria via device tree). O CodesignTopReactor deste
 * projeto usa apenas registradores escalares, então um único nó UIO é
 * suficiente (sem necessidade de buffer DMA).
 */

/dts-v1/;
/plugin/;

/ {
	fragment@0 {
		target-path = "/amba";
		__overlay__ {
			#address-cells = <2>;
			#size-cells = <2>;

			/* Registradores do reactor (11 regs de 32b, seção 6). */
			lf_fpga_regs: lf_fpga_regs@a0000000 {
				compatible = "generic-uio";
				reg = <0x0 0xa0000000 0x0 0x10000>;
				status = "okay";
			};
		};
	};
};
Passos para aplicar no PetaLinux
Copiar o conteúdo relevante para project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi, dentro do nó /amba existente.
Adicionar o bootarg em /chosen (ou via petalinux-config → DTG Settings → Kernel Bootargs):
uio_pdrv_genirq.of_id=generic-uio
Garantir que o módulo carregue no boot (ou carregar manualmente):
modprobe uio_pdrv_genirq
petalinux-build para regenerar a imagem.
Validar na placa:
ls /sys/class/uio/
cat /sys/class/uio/uio0/name   # deve mostrar "lf_fpga_regs"
10. mainCodesign — geração de lfFPGA.a para hardware real
Diagnóstico
mainCodesign (em reactor-chisel/src/main/scala/Main.scala) só tratava o caso VerilatedTester (copiava main.cpp/Makefile, rodava make lib). Para qualquer outra plataforma, a função terminava sem gerar nada além do Verilog.

Correção — Main.scala completo
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
     (new chisel3.stage.ChiselStage).emitVerilog(platformInstFxn(p => new CodesignTopReactor(p, mainReactorFunc, mainReactorSwIOFunc)(globalConfig), targetDir), chiselArgs)
     if (platformInstFxn.equals(TidbitsMakeUtils.platformMap("VerilatedTester"))) {
       // Copy main cpp file for emulation
       resourceCopyBulk("simulator/codesign", targetDir, Seq("main.cpp", "Makefile"))
       println("------------------------------------------------------------------------------------------------------")
       println("Building verilator project")
       println("------------------------------------------------------------------------------------------------------")
       val result = Process("make lib", new java.io.File(targetDir)).!
       println("------------------------------------------------------------------------------------------------------")
       println(s"Library produced to $targetDir/lfFPGA.a.")
       println("------------------------------------------------------------------------------------------------------")
     } else {
       // Real hardware platform: cross-compile the UIO driver into lfFPGA.a
       println("------------------------------------------------------------------------------------------------------")
       println("Cross-compiling hardware driver for real FPGA platform")
       println("------------------------------------------------------------------------------------------------------")
       val cxx = sys.env.getOrElse("LF_FPGA_CXX", "aarch64-linux-gnu-g++")
       val ar = sys.env.getOrElse("LF_FPGA_AR", "aarch64-linux-gnu-ar")
       val targetDirFile = new java.io.File(targetDir)
       val compileResult = Process(
         Seq(cxx, "-c", "platform-zcu106.cpp", "-o", "platform-zcu106.o"),
         targetDirFile
       ).!
       if (compileResult != 0) {
         throw new RuntimeException(s"Failed to cross-compile platform-zcu106.cpp (exit code $compileResult)")
       }
       val arResult = Process(
         Seq(ar, "rcs", "lfFPGA.a", "platform-zcu106.o"),
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
Notas:

O else cobre qualquer plataforma que não seja VerilatedTester (reutilizável para outros wrappers reais no futuro).
LF_FPGA_CXX / LF_FPGA_AR são configuráveis via variável de ambiente, sem precisar editar o Scala.
Resultado (sbt run completo)
=======> Driver written to CodesignTopReactor.hpp
=======> Driver files copied to build
------------------------------------------------------------------------------------------------------
Cross-compiling hardware driver for real FPGA platform
------------------------------------------------------------------------------------------------------
------------------------------------------------------------------------------------------------------
Library produced to build/lfFPGA.a.
------------------------------------------------------------------------------------------------------
[success] elapsed time: 7 s
Validação do artefato final
ls -la lfFPGA.a
file lfFPGA.a
ar t lfFPGA.a
-rw-r--r-- 1 lingua-franca lingua-franca 40492 Jul 12 09:53 lfFPGA.a
lfFPGA.a: current ar archive
platform-zcu106.o
lfFPGA.a para hardware real (ZCU106) gerado e validado com sucesso.

11. Integração com o software — cross-compilação completa
Problema encontrado
/usr/bin/ld: .../lfFPGA.a(platform-zcu106.o): Relocations in generic ELF (EM: 183)
/usr/bin/ld: .../lfFPGA.a: error adding symbols: file in wrong format
Causa
O build/ do software (CMake) estava em cache de uma configuração anterior com compilador x86 nativo. Passar --target-compiler no lfc não força reconfiguração se o cache do CMake já existir.

Correção
cd ~/codesign-gen/main_target_board
rm -rf build src-gen/software bin
cd src/software
lfc --target-compiler aarch64-linux-gnu-g++ _SwTop.lf
Resultado
-- The CXX compiler identification is GNU 11.4.0
-- Check for working CXX compiler: /usr/bin/aarch64-linux-gnu-g++ - skipped
...
[100%] Linking CXX executable _SwTop
[100%] Built target _SwTop
SUCCESS (compiling generated C++ code)
fpgaLib.cmake não precisou de nenhuma alteração — o caminho para lfFPGA.a já apontava corretamente para src-gen/hardware/_FpgaTop/build/lfFPGA.a; bastou o build inteiro (software) ser refeito com o compilador cruzado certo:

set(HW_SRC_GEN "${LF_SRC_PKG_PATH}/src-gen/hardware")
target_include_directories(${LF_MAIN_TARGET} PRIVATE "${HW_SRC_GEN}/_FpgaTop/build")
target_link_libraries(${LF_MAIN_TARGET} "${HW_SRC_GEN}/_FpgaTop/build/lfFPGA.a")
12. Estado atual do projeto (fase ZCU106)
Concluído:

[x] Diagnóstico completo da lacuna de geração de driver para plataforma real
[x] Confirmação (via fonte) de que xlnk não existe no kernel da placa
[x] Confirmação (via fonte) do binding generic-uio + bootarg of_id
[x] Overlay de device tree desenhado (lf_fpga_uio.dtsi)
[x] PYNQZCU104CCWrapper.scala corrigido (copia arquivos de driver)
[x] UioDriver/platform-zcu106.cpp escritos e validados (compilação isolada ARM64)
[x] mainCodesign gerando lfFPGA.a real automaticamente
[x] lfFPGA.a validado (ar t confirma platform-zcu106.o)
[x] Software (_SwTop) cross-compilado para ARM64 e linkado com lfFPGA.a real
Pendente:

[ ] Aplicar lf_fpga_uio.dtsi em system-user.dtsi do PetaLinux + bootarg + petalinux-build
[ ] Gerar bitstream no Vivado a partir de PYNQZCU104CCWrapper.v (.bit/.hwh/.xsa)
[ ] Deploy físico: copiar _SwTop + bitstream para a placa, carregar via FPGA Manager
[ ] Validar /sys/class/uio/uio0/name == lf_fpga_regs na placa real
[ ] Executar _SwTop na ZCU106 e validar comunicação real PS ↔ PL via AXI-Lite
13. Próximas etapas
13.1 Vivado — gerar o bitstream
Chisel (PYNQZCU104CCWrapper.v)
 |
Vivado (synthesis + implementation)
 |
Bitstream (.bit / .hwh / .xsa)
13.2 PetaLinux — aplicar overlay e reconstruir imagem
# copiar overlay para system-user.dtsi (nó /amba)
# adicionar bootarg uio_pdrv_genirq.of_id=generic-uio
petalinux-build
13.3 Deploy e validação na placa
# copiar bin/_SwTop e o .bit para a placa (ex.: /home/root)
# carregar bitstream via FPGA Manager
ls /sys/class/uio/
cat /sys/class/uio/uio0/name
./_SwTop
13.4 Validar comunicação real
ARM PS (_SwTop, UioDriver)
 |
AXI-Lite (mmap /dev/uio0)
 |
FPGA PL (PYNQZCU104CCWrapper)
 |
CodesignTopReactor (registradores 0-11)
Estado final desta fase
O objetivo desta etapa — gerar lfFPGA.a associado à MPSoC (ZCU106) — foi alcançado com sucesso, com um driver de registradores baseado em UIO genérico, sem dependência do driver xlnk (indisponível no kernel moderno da placa), e já integrado ao build cross-compilado do software (_SwTop ARM64).

A próxima fase é a geração do bitstream via Vivado e o deploy físico completo na ZCU106.
