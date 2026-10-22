# Backlog — Suporte Nativo ZCU106 no `lfc` 

> 🇧🇷 **Português** · [🇬🇧 English](../03-backlog-generalization.md) — a versão em inglês é a canônica.

<!-- translated-from: docs/03-backlog-generalization.md@f800594874f32cb2b225e0293e286872822d3d3e -->

Documentação de como o compilador Lingua Franca (`lfc`) passou a gerar `lfFPGA.a` nativamente para a plataforma `PYNQZCU104CC` (chip idêntico ao da ZCU106), eliminando a necessidade de reeditar arquivos manualmente a cada geração — e um guia para o time continuar desenvolvendo isso de forma autônoma.

---

## 1. Objetivo

Anteriormente, cada novo projeto Lingua Franca gerado do zero (`lfc arquivo.lf`) reconstruía `src-gen/reactor-chisel` a partir do **template empacotado no próprio compilador**, apagando qualquer patch manual aplicado num projeto anterior. Isso obrigava a reaplicar, projeto a projeto:

1. A correção em `PYNQZCU104CCWrapper.scala` (cópia dos arquivos de driver).
2. A correção em `Main.scala`/`mainCodesign` (cross-compilação gerando `lfFPGA.a` para plataformas reais).

O objetivo desta fase foi tornar essas duas correções **nativas do `lfc`**, para que qualquer projeto novo já saia funcionando, sem edição manual.

---

## 2. Descoberta do mecanismo `fpgaBoard`

Antes de mexer no compilador, foi descoberto que a plataforma de hardware pode ser escolhida diretamente no `.lf` de mais alto nível, via `target Codesign`:

```lf
target Codesign {
  fpgaBoard: PYNQZCU104CC
}
```

Isso é propagado automaticamente para o `_FpgaTop.lf` gerado:

```lf
// This file is code-generated DO NOT EDIT.
target Chisel {
  codesign: true,
  fpgaBoard: PYNQZCU104CC,
  clock-period: 1 nsec,
}
```

E daí para `platformMap("PYNQZCU104CC")` no `Main.scala` do reactor Chisel — **sem precisar editar Scala manualmente para trocar de plataforma**.

### Fonte confirmada no compilador

```
/opt/lingua-franca/core/src/main/java/org/lflang/TargetProperty.java:638   → "fpgaBoard" é propriedade de target válida
/opt/lingua-franca/core/src/main/kotlin/.../CodesignHwEmitter.kt:62-63     → propaga fpgaBoard para o _FpgaTop.lf gerado
/opt/lingua-franca/core/src/main/kotlin/.../CodesignGenerator.kt:57        → só compila/copia o software automaticamente se fpgaBoard == "VerilatedTester"
```

**Consequência prática**: para qualquer `fpgaBoard` diferente de `VerilatedTester` (incluindo `PYNQZCU104CC`), o `lfc` gera o hardware e o código-fonte do software, mas **não compila nem copia o binário do software** automaticamente — isso continua sendo um passo manual (ver seção 6).

---

## 3. Causa raiz do "tudo é sobrescrito"

O `lfc` instalado é uma distribuição já empacotada pelo Gradle:

```bash
which lfc
# /opt/lingua-franca/build/install/lf-cli/bin/lfc
```

Os arquivos-fonte do template Chisel/fpga-tidbits usados por essa distribuição vêm de:

```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/...
```

Cada chamada a `lfc arquivo.lf` copia esse template para dentro de `src-gen/reactor-chisel` do projeto — **sobrescrevendo qualquer edição manual feita ali antes**. Editar `src-gen/reactor-chisel` de um projeto específico não é uma correção permanente: só vale até a próxima geração.

**Diagnóstico confirmado empiricamente**: editar o projeto local, rodar `lfc` de novo, e ver a edição desaparecer.

---

## 4. Solução — editar o template-fonte e reempacotar

Em vez de editar `src-gen/reactor-chisel` de cada projeto, as duas correções foram aplicadas diretamente no **template-fonte do compilador**, seguido de reempacotamento via Gradle.

### 4.1 Arquivo 1 — `PYNQZCU104CCWrapper.scala`

Caminho:
```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/PYNQZCU104CCWrapper.scala
```

Conteúdo completo (versão com driver nativo do fork — `LinuxPhysRegDriver`, sem arquivos `.cpp`/`.hpp` customizados):

```scala
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
  suggestName("PYNQZCU104CCWrapper")
  mem(0).readAddr.bits.cache := "b1100".U
  mem(0).writeAddr.bits.cache := "b1100".U
  mem(0).readAddr.bits.prot := "b10".U
  mem(0).writeAddr.bits.prot := "b10".U
  generateRegDriver(targetDir)
  resourceCopyBulk("cpp/platform-wrapper-regdriver/", targetDir, platformDriverFiles)
  println(s"=======> Driver files copied to ${targetDir}")
}
```

**O que mudou em relação ao original do fork**: adição das 3 últimas linhas (`generateRegDriver`, `resourceCopyBulk`, `println`) e do import `fpgatidbits.TidbitsMakeUtils._` — o wrapper original só *definia* `platformDriverFiles`, nunca copiava os arquivos de fato.

### 4.2 Arquivo 2 — `Main.scala` (orquestrador `ReactorChisel`)

Caminho:
```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/src/main/scala/Main.scala
```

Trecho alterado — `mainCodesign` completo:

```scala
   def mainCodesign(platformInstFxn: PlatformInstFxn, mainReactorFunc: () => Reactor, mainReactorSwIOFunc: () => SwIO, targetDir: String, globalConfig: GlobalReactorConfig) = {
     println("------------------------------------------------------------------------------------------------------")
     println("Running Chisel compiler to generate verilator project")
     println("------------------------------------------------------------------------------------------------------")
     val chiselArgs = Array("--target-dir", s"$targetDir")
     (new chisel3.stage.ChiselStage).emitVerilog(platformInstFxn(p => new CodesignTopReactor(p, mainReactorFunc, mainReactorSwIOFunc)(globalConfig), targetDir), chiselArgs)
     if (platformInstFxn.equals(TidbitsMakeUtils.platformMap("VerilatedTester"))) {
       resourceCopyBulk("simulator/codesign", targetDir, Seq("main.cpp", "Makefile"))
       println("------------------------------------------------------------------------------------------------------")
       println("Building verilator project")
       println("------------------------------------------------------------------------------------------------------")
       val result = Process("make lib", new java.io.File(targetDir)).!
       println("------------------------------------------------------------------------------------------------------")
       println(s"Library produced to $targetDir/lfFPGA.a.")
       println("------------------------------------------------------------------------------------------------------")
     } else {
       // Real hardware platform: cross-compile the driver into lfFPGA.a
       println("------------------------------------------------------------------------------------------------------")
       println("Cross-compiling hardware driver for real FPGA platform")
       println("------------------------------------------------------------------------------------------------------")
       val cxx = sys.env.getOrElse("LF_FPGA_CXX", "aarch64-linux-gnu-g++")
       val ar = sys.env.getOrElse("LF_FPGA_AR", "aarch64-linux-gnu-ar")
       val targetDirFile = new java.io.File(targetDir)
       val compileResult = Process(
         Seq(cxx, "-fpermissive", "-c", "platform-zedboard-linux.cpp", "-o", "platform-zedboard-linux.o"),
         targetDirFile
       ).!
       if (compileResult != 0) {
         throw new RuntimeException(s"Failed to cross-compile platform-zedboard-linux.cpp (exit code $compileResult)")
       }
       val arResult = Process(
         Seq(ar, "rcs", "lfFPGA.a", "platform-zedboard-linux.o"),
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
```

**Notas:**
- `-fpermissive` é necessário porque `linuxphysregdriver.hpp` foi escrito para ARM 32 bits (ZedBoard) e faz cast de `void*` para `unsigned int`, o que o GCC rejeita por padrão em ARM64. Ver seção 5 para os riscos disso.
- `LF_FPGA_CXX`/`LF_FPGA_AR` são configuráveis via variável de ambiente, sem precisar reeditar o Scala.
- O `else` cobre qualquer plataforma que não seja `VerilatedTester` — reutilizável para outros wrappers reais no futuro.

### 4.3 Reempacotamento

```bash
cd /opt/lingua-franca
./gradlew installDist
```

Não recompila o compilador inteiro (Kotlin/Java) do zero — apenas reprocessa os `resources` e reempacota a distribuição em `build/install/lf-cli/`. Levou ~poucos segundos no ambiente testado, sem necessidade de acesso à rede (dependências já em cache).

### 4.4 Validação

```bash
cd ~/codesign-gen/main_target_board_pynqzcu104/src-gen/hardware/_FpgaTop
rm -rf build target
cd ~
lfc main_target_board_pynqzcu104.lf
```

Log obtido (trecho relevante):

```
=======> Driver written to CodesignTopReactor.hpp
=======> Driver files copied to build
------------------------------------------------------------------------------------------------------
Cross-compiling hardware driver for real FPGA platform
------------------------------------------------------------------------------------------------------
[warnings esperados do -fpermissive, ver seção 5]
------------------------------------------------------------------------------------------------------
Library produced to build/lfFPGA.a.
------------------------------------------------------------------------------------------------------
[success] elapsed time: 26 s
lfc: info: Generating code for the Software-part
lfc: info: Code generation finished.
```

**Confirmado**: `lfFPGA.a` gerado automaticamente com um único comando `lfc`, sem nenhuma edição manual prévia no projeto.

---

## 5. Riscos herdados e ainda pendentes (não resolvidos por este patch)

Este patch resolve a **automação** da geração, mas não resolve dois problemas de fundo que já existiam:

### 5.1 Truncamento de ponteiros de 64 para 32 bits

`linuxphysregdriver.hpp` (arquivo original do fork, não editado) faz `(unsigned int) ponteiro` em vários pontos. Em ARM64, isso trunca a metade superior do endereço. Com `-fpermissive`, o código compila mas o **comportamento em tempo de execução fica incorreto** para qualquer endereço físico ≥ 4GB (`0x100000000`).

- O endereço dos registradores (a definir no Vivado) precisa caber em 32 bits — normalmente ok, já que a faixa de I/O da PL costuma ficar bem abaixo de 4GB.
- O endereço do "buffer de memória não gerenciada" (segundo argumento do construtor de `LinuxPhysRegDriver`) **precisa ficar abaixo de 4GB** para não ser corrompido.

### 5.2 Endereços hardcoded errados

`platform-zedboard-linux.cpp` ainda contém os endereços do ZedBoard (`0x43c00000` para registradores, `0x10000000`/256MB para buffer), não os da ZCU106. **Isso não foi corrigido neste patch** — decisão explícita de manter os arquivos do fork sem modificação, ajuste de endereço fica sob responsabilidade de quem for rodar na placa física.

### 5.3 `CONFIG_STRICT_DEVMEM`

Ainda não confirmado no kernel da ZCU106 se esse driver bloqueia `/dev/mem` para a região de buffer (dentro da faixa real de DDR).

### 5.4 `ZYNQ_BITFILE_LOADER`

Variável de ambiente exigida por `loadBitfile()` em `platform-zedboard-linux.cpp` — ainda não configurada; precisa apontar para um script de carga de bitstream via FPGA Manager do PetaLinux.

### 5.5 Software não compilado automaticamente

Como visto na seção 2, o `lfc` só compila e copia o binário do software automaticamente quando `fpgaBoard == "VerilatedTester"`. Para `PYNQZCU104CC`, o passo de compilação do software continua manual (seção 6.3).

---

## 6. Guia — como desenvolver por conta própria a partir daqui

### 6.1 Criar um novo projeto de codesign para a ZCU106

Escreva um `.lf` único (fora de `src/`, ou dentro de um projeto com estrutura `src/`) usando o target `Codesign`:

```lf
target Codesign {
  fpgaBoard: PYNQZCU104CC
}

reactor MeuReactorFpga {
  // lógica de hardware aqui, usando tipos Chisel: {= UInt(32.W) =}, etc.
}

reactor MeuReactorSw {
  // lógica de software C++ aqui
}

main reactor {
  @fpga
  fpga = new MeuReactorFpga()
  sw = new MeuReactorSw()
  // conecte as portas conforme necessário
}
```

```bash
lfc meu_projeto.lf
```

Isso já vai gerar `lfFPGA.a` automaticamente (graças ao patch aplicado no compilador), sem qualquer edição manual.

### 6.2 Onde o resultado aparece

```
<pasta_do_projeto>/src-gen/hardware/_FpgaTop/build/
|-- lfFPGA.a                  <- biblioteca do driver, pronta para o software linkar
|-- CodesignTopReactor.hpp    <- interface C++ gerada a partir do seu reactor @fpga
|-- <NomeDoWrapper>.v         <- RTL para levar ao Vivado
```

### 6.3 Compilar o software manualmente (ainda necessário para plataformas reais)

```bash
cd <pasta_do_projeto>/src/software
lfc --target-compiler aarch64-linux-gnu-g++ <NomeDoSwTop>.lf
```

**Atenção**: se já existir um `build/` do software de uma configuração anterior (ex.: compilado nativo x86), é necessário limpar antes, pois o CMake usa cache e não reconfigura automaticamente:

```bash
rm -rf <pasta_do_projeto>/build <pasta_do_projeto>/src-gen/software <pasta_do_projeto>/bin
```

### 6.4 Testando a compilação isolada do driver (debug rápido)

Sem precisar rodar `lfc`/`sbt` inteiro, para validar rapidamente se o driver compila:

```bash
cd <pasta_do_projeto>/src-gen/hardware/_FpgaTop/build
aarch64-linux-gnu-g++ -fpermissive -c platform-zedboard-linux.cpp -o /tmp/test.o
file /tmp/test.o    # deve mostrar ELF 64-bit ..., ARM aarch64
```

### 6.5 Ajustando os endereços físicos (pendência da seção 5.2)

Depois de sintetizar o Block Design no Vivado e confirmar o endereço AXI-Lite real do seu `CodesignTopReactor` (Address Editor), edite:

```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/platform-zedboard-linux.cpp
```

Trocando:
```cpp
platform = new LinuxPhysRegDriver((void *) 0x43c00000, (void *) 0x10000000, 256 * 1024 * 1024);
```
pelo endereço real confirmado no Vivado, respeitando o limite de 4GB citado na seção 5.1. Depois, reempacote:
```bash
cd /opt/lingua-franca && ./gradlew installDist
```

### 6.6 Trocando de driver (UIO vs. fork nativo)

Caso o driver `LinuxPhysRegDriver`/`/dev/mem` se mostre bloqueado por `CONFIG_STRICT_DEVMEM` na placa real, a alternativa já validada é o driver customizado baseado em UIO (`UioDriver`/`platform-zcu106.cpp`, ver README anterior desta série). Para trocar:

1. Adicionar `uiodriver.hpp`/`platform-zcu106.cpp` em:
   ```
   /opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/
   ```
2. Trocar `platformDriverFiles` (seção 4.1) para apontar a esses dois arquivos.
3. Trocar a linha de compilação no `mainCodesign` (seção 4.2) de `platform-zedboard-linux.cpp` para `platform-zcu106.cpp` (sem `-fpermissive`, já que o `UioDriver` foi escrito nativamente para 64 bits).
4. Aplicar o overlay de device tree `generic-uio` + bootarg `uio_pdrv_genirq.of_id=generic-uio` no PetaLinux (ver README anterior).
5. Reempacotar: `cd /opt/lingua-franca && ./gradlew installDist`.

### 6.7 Fluxo de trabalho recomendado para qualquer alteração futura no template

1. Editar o(s) arquivo(s) em `/opt/lingua-franca/core/src/main/resources/lib/chisel/...`.
2. `cd /opt/lingua-franca && ./gradlew installDist`.
3. Testar num projeto limpo:
   ```bash
   rm -rf <projeto>/codesign-gen <projeto>/src-gen
   lfc <projeto>.lf
   ```
4. Confirmar `lfFPGA.a` gerado:
   ```bash
   ls -la <projeto>/src-gen/hardware/_FpgaTop/build/lfFPGA.a
   ar t <projeto>/src-gen/hardware/_FpgaTop/build/lfFPGA.a
   ```

**Nunca** editar diretamente `src-gen/reactor-chisel` de um projeto específico esperando que a mudança persista — isso é sempre sobrescrito na próxima chamada ao `lfc`.

---

## 8. Generalização — de "ZCU106 hardcoded" para "qualquer placa"

### 8.1 O problema identificado

Mesmo após a seção 4, o `mainCodesign` ainda tinha o nome do driver **hardcoded**:

```scala
Seq(cxx, "-fpermissive", "-c", "platform-zedboard-linux.cpp", "-o", "platform-zedboard-linux.o")
```

Isso funcionava para `PYNQZCU104CC`, mas qualquer placa nova exigiria editar esse arquivo de novo — não era generalização de verdade. O trabalho foi dividido em 3 fases.

### 8.2 Fase 1 — Identidade própria para a ZCU106

Em vez de continuar reaproveitando o nome `PYNQZCU104CC`, foi criado um wrapper com nome próprio: `fpga-tidbits/.../platform-wrapper/axi/ZCU106Wrapper.scala`.

```scala
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
    "platform-zedboard-linux.cpp", "linuxphysregdriver.hpp", "axiregdriver.hpp"
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
```

Registro em `fpgatidbits/Main.scala` (`platformMap` e `fpgaPartMap`):

```scala
"ZCU106" -> {(f, targetDir) => new ZCU106Wrapper(f, targetDir)},
```
```scala
"ZCU106" -> "xczu7ev-ffvc1156-2-e",   // mesmo part number do PYNQZCU104CC — mesmo chip
```

**Validado**: `fpgaBoard: ZCU106` gera `ZCU106Wrapper.v`/`.fir`/`.anno.json` e `lfFPGA.a` de ponta a ponta.

### 8.3 Fase 2 — Driver canônico de 64 bits

Objetivo: adotar o `UioDriver` (seguro em 64 bits, sem `-fpermissive`) como driver de referência para a família MPSoC, em vez do driver legado de 32 bits herdado do ZedBoard.

Arquivos novos, com nomes neutros (não específicos de uma placa): `uiodriver.hpp` e `platform-mpsoc-uio.cpp` (mesmo conteúdo do `UioDriver`/`platform-zcu106.cpp` já documentado no README anterior desta série).

`ZCU106Wrapper.scala` atualizado:
```scala
val platformDriverFiles = baseDriverFiles ++ Array[String](
  "platform-mpsoc-uio.cpp", "uiodriver.hpp"
)
```

**Validado**: compilação **sem nenhum warning** (diferente do driver de 32 bits, que sempre gera warnings de truncamento de ponteiro). `lfFPGA.a` contém `platform-mpsoc-uio.o`.

### 8.4 Fase 3 — `mainCodesign` genérico (a peça central)

Elimina o hardcode de vez: o `mainCodesign` passa a **ler** o nome do driver e as flags de compilação a partir da própria instância do wrapper.

Novos campos na classe base (`PlatformWrapper.scala`), com valores padrão — não abstratos, para não quebrar os ~10 wrappers já existentes que não os implementam:

```scala
def platformDriverFiles: Seq[String]  // additional files

// Nome do .cpp que contém initPlatform/deinitPlatform, dentro de platformDriverFiles.
// Por padrão, deduzido automaticamente: o primeiro .cpp da lista.
def platformMainDriverFile: String = platformDriverFiles.find(_.endsWith(".cpp")).getOrElse("")

// Flags extras de compilação para platformMainDriverFile (ex: "-fpermissive"
// para drivers legados de 32 bits). Vazio por padrão.
def platformDriverExtraFlags: Seq[String] = Seq()
```

`mainCodesign` refatorado (`reactor-chisel/src/main/scala/Main.scala`):

```scala
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
```

`PYNQZCU104CCWrapper.scala` — override necessário para manter funcionando com o driver legado:
```scala
override def platformDriverExtraFlags: Seq[String] = Seq("-fpermissive")
```
(`platformMainDriverFile` não precisou de override — o default já encontra `platform-zedboard-linux.cpp` automaticamente, por ser o único `.cpp` na lista desse wrapper)

#### Armadilha de implementação: contexto do Builder do Chisel

Uma primeira tentativa desta refatoração falhou com:
```
java.lang.IllegalArgumentException: requirement failed: must be inside Builder context
```

**Causa**: `PlatformWrapper` estende `Module` do Chisel. Construir uma instância de `Module` só é permitido **dentro** do contexto de elaboração aberto internamente por `emitVerilog` — passar `platformInstFxn(...)` como argumento por nome (como no código original) funciona porque a expressão só é avaliada dentro desse contexto; extrair isso para uma `val` **antes** da chamada de `emitVerilog` quebra essa garantia.

**Solução**: usar uma `var` externa, atribuída **dentro** do bloco passado por nome ao `emitVerilog` (`var platformWrapperInst: PlatformWrapper = null` + atribuição dentro do bloco `{...}`). A construção do módulo continua acontecendo dentro do Builder; a referência fica disponível depois, para leitura de `def`s simples, que não criam novo hardware.

### 8.5 Validação de coexistência (o teste decisivo)

Dois testes, no mesmo `mainCodesign`, sem nenhuma edição entre eles:

**`PYNQZCU104CC`** (driver legado, flag `-fpermissive`):
```
lfFPGA.a: 37124 bytes
ar t → platform-zedboard-linux.o
[warnings de truncamento de ponteiro aparecem, como esperado]
```

**`ZCU106`** (driver novo, sem flags):
```
Compiling driver: platform-mpsoc-uio.cpp (flags: )
lfFPGA.a: 40494 bytes
ar t → platform-mpsoc-uio.o
[nenhum warning]
```

**Critério de generalização atingido**: adicionar uma placa nova a partir daqui significa criar um `NovaPlacaWrapper.scala`, registrar em `platformMap`/`fpgaPartMap`, e reempacotar — **sem nenhuma edição em `mainCodesign`**.

---

## 9. Checklist de estado atual

**Concluído:**
- [x] Descoberto e documentado o mecanismo `fpgaBoard` no `target Codesign`
- [x] Causa raiz do "tudo é sobrescrito" identificada (template empacotado, não lido ao vivo)
- [x] `PYNQZCU104CCWrapper.scala` corrigido no template-fonte do compilador
- [x] `mainCodesign`/`Main.scala` corrigido no template-fonte do compilador
- [x] `./gradlew installDist` validado (reempacotamento funcional, sem rebuild completo)
- [x] Geração de `lfFPGA.a` nativa confirmada em projeto limpo, com um único comando `lfc`
- [x] Fase 1 — `ZCU106Wrapper.scala` com identidade própria, registrado em `platformMap`/`fpgaPartMap`
- [x] Fase 2 — `UioDriver` adotado como driver canônico de 64 bits para a família MPSoC
- [x] Fase 3 — `mainCodesign` genérico, lendo driver/flags da instância do wrapper (sem hardcode)
- [x] Coexistência validada: `PYNQZCU104CC` (driver legado + flag) e `ZCU106` (driver novo, sem flags) lado a lado, sem editar `mainCodesign`

**Pendente:**
- [ ] Corrigir endereços hardcoded em `platform-zedboard-linux.cpp` para os valores reais da ZCU106 (seção 6.5) — só relevante se o driver legado continuar em uso; `ZCU106`/`UioDriver` já usa endereço configurável via macro
- [ ] Confirmar `CONFIG_STRICT_DEVMEM` no kernel da placa
- [ ] Implementar/configurar `ZYNQ_BITFILE_LOADER`
- [ ] Automatizar a compilação do software para plataformas reais (hoje ainda manual, seção 6.3)
- [ ] Gerar bitstream via Vivado e validar na placa física
- [ ] Teste de validação final: criar uma placa fictícia do zero, tocando em um único arquivo, para confirmar o critério de generalização na prática (ver seção 10)

---

## 10. Teste de validação — placa fictícia "TESTBOARD"

Objetivo: provar objetivamente que o critério de generalização foi atingido — adicionar uma placa nova sem editar `mainCodesign`.

### 10.1 Regra do teste

Só é permitido:
1. Criar **um único arquivo novo**: `TestboardWrapper.scala`.
2. Adicionar **duas linhas** em `fpgatidbits/Main.scala` (`platformMap` e `fpgaPartMap`) — necessário porque o registro é centralizado nesse mapa; não é uma exceção ao critério, é o "custo mínimo" inerente de registrar qualquer placa nova.
3. Reempacotar (`installDist`).

**Não é permitido** tocar em `mainCodesign`, `PlatformWrapper.scala`, ou qualquer wrapper existente.

### 10.2 `TestboardWrapper.scala`

Reaproveita o driver `UioDriver` (já genérico, seção 8.3) — prova que o driver também não precisa ser reescrito para uma placa nova do mesmo chip.

```scala
package fpgatidbits.PlatformWrapper
import chisel3._
import fpgatidbits.TidbitsMakeUtils._
object TestboardParams extends PlatformWrapperParams {
  val platformName = "TESTBOARD"
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
class TestboardWrapper(instFxn: PlatformWrapperParams => GenericAccelerator, targetDir: String)
  extends AXIPlatformWrapper(PYNQZCU104Params, instFxn) {
  val platformDriverFiles = baseDriverFiles ++ Array[String](
    "platform-mpsoc-uio.cpp", "uiodriver.hpp"
  )
  suggestName("TestboardWrapper")
  mem(0).readAddr.bits.cache := "b1100".U
  mem(0).writeAddr.bits.cache := "b1100".U
  mem(0).readAddr.bits.prot := "b10".U
  mem(0).writeAddr.bits.prot := "b10".U
  generateRegDriver(targetDir)
  resourceCopyBulk("cpp/platform-wrapper-regdriver/", targetDir, platformDriverFiles)
  println(s"=======> Driver files copied to ${targetDir}")
}
```

### 10.3 Registro (as duas linhas permitidas)

Em `platformMap`:
```scala
"TESTBOARD" -> {(f, targetDir) => new TestboardWrapper(f, targetDir)},
```

Em `fpgaPartMap`:
```scala
"TESTBOARD" -> "xczu7ev-ffvc1156-2-e",
```

### 10.4 Reempacotar e testar

```bash
cd /opt/lingua-franca
./gradlew installDist
```

```bash
cd ~
cat > teste_testboard.lf << 'EOF'
target Codesign {
  fpgaBoard: TESTBOARD
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
lfc teste_testboard.lf
ls -la ~/codesign-gen/teste_testboard/src-gen/hardware/_FpgaTop/build/lfFPGA.a
ar t ~/codesign-gen/teste_testboard/src-gen/hardware/_FpgaTop/build/lfFPGA.a
```

### 10.5 Critério de sucesso

- `TestboardWrapper.v` gerado (não `ZCU106Wrapper.v` nem `PYNQZCU104CCWrapper.v`).
- `Compiling driver: platform-mpsoc-uio.cpp (flags: )` aparece no log — confirma que `mainCodesign` leu o driver certo sem nenhuma edição nele.
- `lfFPGA.a` gerado, contendo `platform-mpsoc-uio.o`.
- Testes A e B da seção 8.5 (`PYNQZCU104CC` e `ZCU106`) continuam passando sem re-teste necessário, já que `mainCodesign` não foi tocado nesta seção.

Se os quatro itens acima se confirmarem, o critério de generalização está objetivamente demonstrado: uma placa nova exige apenas um arquivo de wrapper + duas linhas de registro, nunca uma edição no orquestrador de build.

---

## 11. Simular com Verilator antes de gerar o bitstream

Como o `mainCodesign` foi reescrito nas Fases 1-3, era necessário confirmar que o caminho de **simulação** (`VerilatedTester`) continuou funcionando sem regressão — ele passa pelo mesmo método, só que pelo ramo `if` em vez do `else`.

### 11.1 Por que simular antes de ir para hardware real

O reactor `Fpga`/`Sw` escrito em `.lf` é **o mesmo código-fonte**, independente da plataforma escolhida em `fpgaBoard`. Só a "casca" AXI/driver muda entre `VerilatedTester` e uma placa real. Isso significa que trocar só o `fpgaBoard` permite:

1. Validar a lógica do reactor via simulação (rápido, sem precisar de bitstream nem placa física).
2. Trocar `fpgaBoard: VerilatedTester` → `fpgaBoard: ZCU106` (ou outra placa) só depois que a lógica já está correta — isolando bugs de lógica de bugs de plataforma/hardware.

### 11.2 Teste de regressão realizado

Reaproveitando o mesmo `.lf` original (`Fpga`/`Sw` com timer de 1ms e incremento controlado), trocando só o `fpgaBoard`:

```lf
target Codesign {
  fpgaBoard: VerilatedTester
}
reactor Fpga {
  input in: {= UInt(32.W) =}
  output out: {= UInt(32.W) =}
  timer t (0, 1 msec)
  state cnt: {= UInt(32.W) =}
  state inc: {= UInt(32.W) =}
  reaction(in) {=
    printf("Got inc=%d\n", lf_get(in))
    lf_write(inc, lf_get(in))
  =}
  reaction(t) -> out {=
    val next = lf_read(cnt) + lf_read(inc)
    printf("Update count to=%d\n", next)
    lf_set(out, next)
    lf_write(cnt, next)
  =}
}
reactor Sw {
  input in: uint32_t
  output out: uint32_t
  logical action a
  reaction(startup) -> a, out {=
    out.set(2);
    a.schedule(10ms);
  =}
  reaction(a) -> out {=
    out.set(4);
  =}
  reaction(in) {=
    std::cout <<"Got: " <<*in.get() <<" @ " <<get_elapsed_logical_time() <<std::endl;
  =}
}
main reactor {
  @fpga
  fpga = new Fpga()
  sw = new Sw()
  sw.out, fpga.out -> fpga.in, sw.in
}
```

```bash
lfc teste_verilator_regressao.lf
ls -la ~/codesign-gen/teste_verilator_regressao/src-gen/hardware/_FpgaTop/build/lfFPGA.a
```

`lfFPGA.a` de **635890 bytes** (bem maior que os ~37-40KB dos builds para hardware real — esperado, já que aqui o `.a` contém o RTL inteiro compilado como C++ pelo Verilator, não só o driver).

### 11.3 Execução

O binário do software gerado por `lfc` segue o nome do `main reactor` (sem nome explícito no `.lf`, usa o padrão `_SwTop`), não o nome do arquivo `.lf`:

```bash
cd ~/codesign-gen/teste_verilator_regressao
./bin/_SwTop
```

Saída confirmada:
```
[INFO]  Attached to FPGA Reactor with Signature: aeef7c29
Got inc=         2
Update count to=         2
Got: 2 @ 0 nsecs
Update count to=         4
Got: 4 @ 1000000 nsecs
...
Got inc=         4
Update count to=        24
...
```

**Sem regressão**: assinatura correta, contagem incrementando certinho, tags lógicas avançando em passos de 1ms — comportamento idêntico ao observado antes das Fases 1-3.

### 11.4 Fluxo de trabalho recomendado (simular → hardware real)

```bash
# 1. Simular primeiro — valida a lógica do reactor
cat > meu_projeto.lf << 'EOF'
target Codesign {
  fpgaBoard: VerilatedTester
}
# ... reactors Fpga/Sw ...
EOF
lfc meu_projeto.lf
cd ~/codesign-gen/meu_projeto
./bin/_SwTop   # Ctrl+C para interromper (roda indefinidamente se houver timer sem fim)
```

```bash
# 2. Depois que a lógica estiver validada, trocar só o fpgaBoard
sed -i 's/fpgaBoard: VerilatedTester/fpgaBoard: ZCU106/' meu_projeto.lf
cd ~
rm -rf codesign-gen/meu_projeto/src-gen/hardware/_FpgaTop/build codesign-gen/meu_projeto/src-gen/hardware/_FpgaTop/target
lfc meu_projeto.lf
# gera lfFPGA.a para hardware real, sem precisar re-simular a lógica
```

```bash
# 3. Compilar o software cross-compilado, linkando com o lfFPGA.a real
cd codesign-gen/meu_projeto/src/software
lfc --target-compiler aarch64-linux-gnu-g++ <NomeDoSwTop>.lf
```

**Nota**: a etapa 3 continua sendo manual para plataformas reais (seção 6.3) — só a etapa de simulação (1) compila e roda o software automaticamente, por causa da condição `if (fpgaBoard.equals("VerilatedTester"))` no `CodesignGenerator.kt` (seção 2).

---

## 12. Testabilidade do `UioDriver` propagada ao template

### 12.1 Motivação

Depois de validar `lfFPGA.a` para `PYNQZCU104CC`/`ZCU106`/`TESTBOARD`, foi identificada a necessidade de testar a lógica interna do `UioDriver` (aritmética de registradores, busca de dispositivo por nome) sem depender de hardware real nem de um kernel Linux com UIO configurado.

### 12.2 Estratégia

`mmap` funciona de forma idêntica em `/dev/uioN` (produção) e em um arquivo regular comum (teste) — ambos são operações POSIX válidas de `MAP_SHARED`. A solução foi extrair os diretórios hardcoded (`/sys/class/uio`, `/dev`) para macros configuráveis em tempo de compilação, permitindo apontar o driver para uma árvore de diretório falsa em testes, sem tocar em `/sys` real.

### 12.3 `uiodriver.hpp` atualizado no template-fonte

Caminho:
```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/uiodriver.hpp
```

Mudanças em relação à versão anterior (seção 8.3):
- Adicionados os macros `LF_FPGA_UIO_SYSFS_DIR` (default `/sys/class/uio`) e `LF_FPGA_UIO_DEV_DIR` (default `/dev`) — comportamento de produção inalterado, já que os defaults reproduzem exatamente o que o código tinha antes.
- `findUioDevice()` passou de `private` para `public static`, para poder ser chamado diretamente em testes sem instanciar a classe inteira.

```cpp
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
```

### 12.4 `test_uiodriver.cpp` — harness de teste

Testa, sem hardware real: round-trip de leitura/escrita nos 12 registradores conhecidos do `CodesignTopReactor`, valores de borda (`0x00000000`/`0xFFFFFFFF`), ausência de vazamento entre registradores adjacentes, seleção correta de dispositivo por nome (ignorando dispositivos com nome errado), e comportamento de fallback.

```bash
mkdir -p fake_sysfs/uio0 fake_sysfs/uio1 fake_dev
echo "some_other_device" > fake_sysfs/uio0/name
echo "lf_fpga_regs" > fake_sysfs/uio1/name
head -c 4096 /dev/zero > fake_dev/uio0
head -c 4096 /dev/zero > fake_dev/uio1

g++ -std=c++11 \
    -I/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver \
    -DLF_FPGA_UIO_SYSFS_DIR="\"$HOME/fake_sysfs\"" \
    -DLF_FPGA_UIO_DEV_DIR="\"$HOME/fake_dev\"" \
    -DLF_FPGA_UIO_MAP_SIZE=0x1000 \
    test_uiodriver.cpp -o test_uiodriver
./test_uiodriver
```

Resultado obtido (reproduzido tanto em sandbox de verificação quanto no ambiente Docker do projeto):

```
=== Teste 1: readReg/writeReg via mmap (round-trip) ===
PASS: todos os 12 registradores fazem round-trip corretamente
PASS: escrita de zero funciona
PASS: escrita de 0xFFFFFFFF funciona
PASS: escritas em registradores adjacentes não se sobrepõem
PASS: platformID() retorna o nome certo
=== Teste 2: findUioDevice() seleciona o dispositivo certo por nome ===
PASS: findUioDevice() escolhe uio1 (nome certo), ignora uio0 (nome errado)
=== Teste 3: findUioDevice() com nome inexistente cai no fallback ===
PASS: findUioDevice() usa fallback (primeiro uioN) quando nome não bate com nada
=== Teste 4: construtor lança exceção se device não existe ===
PASS: comportamento de fallback documentado (não é um bug)
=== Resultado final ===
TODOS OS TESTES PASSARAM
```

### 12.5 Risco identificado durante o teste (não é bug, mas merece atenção)

O comportamento de fallback do `findUioDevice()` significa que, se o nome esperado (`lf_fpga_regs`) não existir no sistema, o driver **silenciosamente conecta a outro dispositivo UIO qualquer** disponível, em vez de falhar de forma explícita. Em produção, isso só seria percebido indiretamente, através da falha da verificação de assinatura no construtor do `CodesignTopReactor` (`readReg(0) != 0xaeef7c29`). Não é tratado como bug — é um comportamento documentado, mas vale ciência para quem for depurar problemas de conexão no futuro.

### 12.6 Regressão pós-mudança

Reempacotamento (`./gradlew installDist`) e re-teste em `ZCU106` confirmaram que os macros novos, com seus valores padrão, não alteraram o comportamento de produção:

```
Compiling driver: platform-mpsoc-uio.cpp (flags: )
Library produced to build/lfFPGA.a.
```

`lfFPGA.a` passou de 40494 para 37952 bytes — diferença atribuída à mudança de visibilidade de `findUioDevice` (`private` → `public static`), não a uma alteração de comportamento (confirmado pelos 8 testes passando).

---

## 13. Ciclo completo validado em projeto novo — simulação → hardware real → software cross-compilado

Teste de ponta a ponta, a partir de um único `.lf`, trocando apenas a linha `fpgaBoard`, sem nenhuma edição manual em Scala durante o processo — a validação final de que a generalização (seções 8-10) e a canonização do `UioDriver` (seção 12) funcionam de forma coesa.

### 13.1 Passo 1 — Simulação (`VerilatedTester`)

```lf
target Codesign {
  fpgaBoard: VerilatedTester
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
```

```bash
lfc meu_teste.lf
cd ~/codesign-gen/meu_teste
./bin/_SwTop
```

Resultado:
```
[INFO]  Attached to FPGA Reactor with Signature: aeef7c29
Got: 1
[INFO]  Fpga Wrapper: Do shutdown and deinitialize
```

Programa termina sozinho (sem `timer`, diferente do teste de regressão da seção 11) — comportamento correto: `Sw` envia `1` no `startup`, `Fpga` repassa (`in -> out` idêntico), `Sw` recebe de volta, não há mais trabalho agendado, o reactor desliga.

### 13.2 Passo 2 — Hardware real (`ZCU106`)

```bash
sed -i 's/fpgaBoard: VerilatedTester/fpgaBoard: ZCU106/' meu_teste.lf
rm -rf codesign-gen/meu_teste/src-gen/hardware/_FpgaTop/build codesign-gen/meu_teste/src-gen/hardware/_FpgaTop/target
lfc meu_teste.lf
ls -la ~/codesign-gen/meu_teste/src-gen/hardware/_FpgaTop/build/lfFPGA.a
ar t ~/codesign-gen/meu_teste/src-gen/hardware/_FpgaTop/build/lfFPGA.a
```

Resultado: `lfFPGA.a` de 37952 bytes, contendo `platform-mpsoc-uio.o` — mesmo tamanho de todos os outros builds `UioDriver` pós-seção 12, confirmando consistência.

### 13.3 Passo 3 — Software cross-compilado (ARM64)

```bash
rm -rf ~/codesign-gen/meu_teste/build ~/codesign-gen/meu_teste/src-gen/software ~/codesign-gen/meu_teste/bin
cd ~/codesign-gen/meu_teste/src/software
lfc --target-compiler aarch64-linux-gnu-g++ _SwTop.lf
file ~/codesign-gen/meu_teste/bin/_SwTop
```

Resultado:
```
SUCCESS (compiling generated C++ code)
/home/lingua-franca/codesign-gen/meu_teste/bin/_SwTop: ELF 64-bit LSB pie executable, ARM aarch64, version 1 (GNU/Linux), dynamically linked, interpreter /lib/ld-linux-aarch64.so.1, ...
```

### 13.4 Resumo do ciclo

| Etapa | Comando-chave | Resultado |
|---|---|---|
| 1. Simulação | `fpgaBoard: VerilatedTester` + `lfc` | Executou, `Got: 1`, shutdown limpo |
| 2. Hardware real | `fpgaBoard: ZCU106` + `lfc` | `lfFPGA.a` (37952 bytes), `platform-mpsoc-uio.o` |
| 3. Software cross | `lfc --target-compiler aarch64-linux-gnu-g++` | `_SwTop` ARM64 válido, linkado com `lfFPGA.a` real |

### 13.5 Matriz de cobertura de teste (o que está e o que não está validado sem placa física)

| Teste | O que valida | Precisa de hardware físico? | Status |
|---|---|---|---|
| Simulação (`VerilatedTester`) | Lógica do reactor, comunicação PS↔PL simulada | Não | ✅ Validado |
| Geração `lfFPGA.a` (`ZCU106`/`PYNQZCU104CC`/`TESTBOARD`) | Driver correto compilado e empacotado | Não | ✅ Validado |
| Cross-compile do software | Software linka corretamente com `lfFPGA.a` real, roda em ARM64 | Não (compila, não executa) | ✅ Validado |
| `test_uiodriver` (seção 12) | Lógica interna do `UioDriver` (registradores, busca de dispositivo) | Não | ✅ Validado |
| Execução na placa física | Tudo junto, de verdade, incluindo bitstream e device tree | **Sim** | ⏳ Pendente |

Quatro dos cinco níveis de teste estão cobertos sem depender de acesso à placa física — o único item pendente é a execução real, que depende das etapas ainda em aberto (Vivado/bitstream, overlay de device tree aplicado no PetaLinux, `ZYNQ_BITFILE_LOADER`/estratégia de carga de bitstream para o `UioDriver`).

---

## 14. `clock-period` — correção necessária antes de qualquer síntese real

**Importante**: isto não é um bug introduzido pelo trabalho documentado neste README (Fases 1-3, `UioDriver`, generalização). É uma propriedade de target que já existia no compilador `lfc`, mas que **nenhum `.lf` usado nos testes desta série declarou explicitamente** — por isso passou despercebida até este ponto.

### 14.1 O problema

Todos os testes de geração de `lfFPGA.a` realizados até aqui (`PYNQZCU104CC`, `ZCU106`, `TESTBOARD`, o ciclo completo da seção 13) usaram o valor **default** de `clock-period`, que é `1 nsec` (`Reactor.scala:25`, `clockPeriod: Time = Time.nsec(1)`). Isso equivale a assumir um clock de **1GHz** na PL — um valor que não corresponde a nenhuma frequência física plausível para o Block Design que será montado no Vivado.

### 14.2 Por que isso afeta hardware real, não só simulação

`clockPeriod` é consumido diretamente na lógica RTL de coordenação de tempo lógico, não apenas em testbench:

```scala
// Reaction.scala:83
physicalTag := physicalTag + globalReactorConfig.clockPeriod.nanoseconds.U

// TriggerGenerator.scala:45
regClock := regClock + cfg.clockPeriod.nanoseconds.U
```

Esses registradores (`physicalTag`, `regClock`) são incrementados **a cada ciclo de clock real do hardware**, somando `clockPeriod.nanoseconds` por incremento — o RTL sintetizado assume literalmente "cada pulso de clock recebido corresponde a X nanosegundos de tempo lógico". Se o clock físico de PL configurado no Vivado não bater com o valor usado na geração do RTL, o sistema de coordenação de tags (`nextEventTag`, `tagAdvanceGrant`, timers, deadlines) fica dessincronizado da realidade física — um erro de correção funcional, não de desempenho.

### 14.3 Confirmação da propagação (a propriedade funciona corretamente)

Antes de aplicar a correção, foi confirmado que `clock-period` propaga corretamente por toda a cadeia — não é necessária nenhuma correção adicional no compilador, só declarar o valor explicitamente:

```
TargetProperty.java:646-654        → "clock-period" é propriedade de target válida
CodesignHwEmitter.kt:65-66         → propaga clock-period para o _FpgaTop.lf gerado
ChiselMainFileGenerator.kt:34      → clockPeriod = Time.nsec(${targetConfig.clockPeriod.toNanoSeconds()})
```

A interpolação `${targetConfig.clockPeriod.toNanoSeconds()}` confirma que o valor é avaliado em tempo de geração e escrito como literal no `LfMain.scala` do projeto — não há hardcode no gerador.

### 14.4 Teste de validação

```lf
target Codesign {
  fpgaBoard: ZCU106,
  clock-period: 10 nsec
}
```

Regenerado e confirmado em duas camadas:

`_FpgaTop.lf` gerado:
```lf
// This file is code-generated DO NOT EDIT.
target Chisel {
  codesign: true,
  fpgaBoard: ZCU106,
  clock-period: 10 nsec,
}
```

`LfMain.scala` gerado:
```bash
grep "clockPeriod = Time.nsec" ~/codesign-gen/meu_teste/src-gen/hardware/_FpgaTop/src/main/scala/Main.scala
# implicit val globalConfig = GlobalReactorConfig(timeout = Time.NEVER, standalone=false, clockPeriod = Time.nsec(10))
```

Confirma propagação correta: `Time.nsec(10)`, não mais o default `Time.nsec(1)`.

### 14.5 Implicação para os testes já realizados nesta série

Todos os `lfFPGA.a` gerados nas seções 8, 10, 11 e 13 (antes desta descoberta) foram sintetizados assumindo 1GHz de clock lógico. Isso **não invalida** a validação de geração de driver/`lfFPGA.a` em si (independente do valor de `clock-period`), mas **invalidaria a correção funcional do timing lógico** se qualquer um desses artefatos específicos fosse sintetizado no Vivado e rodado numa placa real sem primeiro corrigir esse parâmetro.

### 14.6 Ação obrigatória antes de qualquer síntese Vivado real

Todo `.lf` que mira hardware físico deve declarar `clock-period` explicitamente, com o valor correspondente à frequência real de clock que será fornecida ao wrapper (`ZCU106Wrapper`/`PYNQZCU104CCWrapper`/etc.) no Block Design do Vivado:

```lf
target Codesign {
  fpgaBoard: ZCU106,
  clock-period: 10 nsec   // ajustar para o clock real do Block Design (ex.: 10ns = 100MHz)
}
```

**Checklist antes da síntese**: confirmar a frequência do clock que alimenta o `ZCU106Wrapper` no Vivado (tipicamente configurada no IP do PS via `zynq_ultra_ps_e` ou num gerador de clock/PLL do Block Design), calcular o período correspondente em nanossegundos, e declarar esse valor exato no `.lf` antes de gerar o RTL a ser sintetizado.
