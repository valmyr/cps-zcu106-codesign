# Backlog — Suporte Nativo ZCU106 no `lfc` 

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
