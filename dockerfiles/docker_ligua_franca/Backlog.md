# Backlog — Suporte Nativo ZCU106 no `lfc` + Guia de Desenvolvimento Autônomo

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

## 7. Checklist de estado atual

**Concluído:**
- [x] Descoberto e documentado o mecanismo `fpgaBoard` no `target Codesign`
- [x] Causa raiz do "tudo é sobrescrito" identificada (template empacotado, não lido ao vivo)
- [x] `PYNQZCU104CCWrapper.scala` corrigido no template-fonte do compilador
- [x] `mainCodesign`/`Main.scala` corrigido no template-fonte do compilador
- [x] `./gradlew installDist` validado (reempacotamento funcional, sem rebuild completo)
- [x] Geração de `lfFPGA.a` nativa confirmada em projeto limpo, com um único comando `lfc`

**Pendente:**
- [ ] Corrigir endereços hardcoded em `platform-zedboard-linux.cpp` para os valores reais da ZCU106 (seção 6.5)
- [ ] Confirmar `CONFIG_STRICT_DEVMEM` no kernel da placa
- [ ] Implementar/configurar `ZYNQ_BITFILE_LOADER`
- [ ] Automatizar a compilação do software para plataformas reais (hoje ainda manual, seção 6.3)
- [ ] Gerar bitstream via Vivado e validar na placa física
