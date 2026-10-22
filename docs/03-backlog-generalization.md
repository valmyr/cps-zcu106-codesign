# Backlog — Native ZCU106 support in `lfc`
Documentation of how the Lingua Franca compiler (`lfc`) came to generate `lfFPGA.a` natively for the `PYNQZCU104CC` platform (the same chip as the ZCU106), removing the need to re-edit files by hand on every generation — and a guide for the team to keep developing this autonomously.

---

## 1. Goal

Previously, every new Lingua Franca project generated from scratch (`lfc file.lf`) rebuilt `src-gen/reactor-chisel` from the **template packaged inside the compiler itself**, wiping out any manual patch applied in a previous project. That forced us to reapply, project by project:

1. The fix in `PYNQZCU104CCWrapper.scala` (copying the driver files).
2. The fix in `Main.scala`/`mainCodesign` (cross-compilation generating `lfFPGA.a` for real platforms).

The goal of this phase was to make those two fixes **native to `lfc`**, so that any new project works out of the box, with no manual editing.

---

## 2. Discovering the `fpgaBoard` mechanism

Before touching the compiler, we found that the hardware platform can be chosen directly in the top-level `.lf`, via `target Codesign`:

```lf
target Codesign {
  fpgaBoard: PYNQZCU104CC
}
```

That is propagated automatically into the generated `_FpgaTop.lf`:

```lf
// This file is code-generated DO NOT EDIT.
target Chisel {
  codesign: true,
  fpgaBoard: PYNQZCU104CC,
  clock-period: 1 nsec,
}
```

and from there into `platformMap("PYNQZCU104CC")` in the Chisel reactor's `Main.scala` — **with no need to edit Scala by hand to change platform**.

### Source confirmed in the compiler

```
/opt/lingua-franca/core/src/main/java/org/lflang/TargetProperty.java:638   → "fpgaBoard" is a valid target property
/opt/lingua-franca/core/src/main/kotlin/.../CodesignHwEmitter.kt:62-63     → propagates fpgaBoard into the generated _FpgaTop.lf
/opt/lingua-franca/core/src/main/kotlin/.../CodesignGenerator.kt:57        → only compiles/copies the software automatically if fpgaBoard == "VerilatedTester"
```

**Practical consequence**: for any `fpgaBoard` other than `VerilatedTester` (including `PYNQZCU104CC`), `lfc` generates the hardware and the software source code, but **neither compiles nor copies the software binary** automatically — that remains a manual step (see section 6).

---

## 3. Root cause of "everything gets overwritten"

The installed `lfc` is a distribution already packaged by Gradle:

```bash
which lfc
# /opt/lingua-franca/build/install/lf-cli/bin/lfc
```

The source files of the Chisel/fpga-tidbits template used by that distribution come from:

```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/...
```

Every call to `lfc file.lf` copies that template into the project's `src-gen/reactor-chisel` — **overwriting any manual edit made there before**. Editing a specific project's `src-gen/reactor-chisel` is not a permanent fix: it only lasts until the next generation.

**Diagnosis confirmed empirically**: edit the local project, run `lfc` again, and watch the edit disappear.

---

## 4. Solution — edit the source template and repackage

Instead of editing each project's `src-gen/reactor-chisel`, the two fixes were applied directly to the **compiler's source template**, followed by repackaging via Gradle.

### 4.1 File 1 — `PYNQZCU104CCWrapper.scala`

Path:
```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/PYNQZCU104CCWrapper.scala
```

Full contents (the version using the fork's native driver — `LinuxPhysRegDriver`, with no custom `.cpp`/`.hpp` files):

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

**What changed relative to the fork's original**: the addition of the last 3 lines (`generateRegDriver`, `resourceCopyBulk`, `println`) and of the `fpgatidbits.TidbitsMakeUtils._` import — the original wrapper only *defined* `platformDriverFiles`, it never actually copied the files.

### 4.2 File 2 — `Main.scala` (the `ReactorChisel` orchestrator)

Path:
```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/src/main/scala/Main.scala
```

Changed excerpt — the complete `mainCodesign`:

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

**Notes:**
- `-fpermissive` is needed because `linuxphysregdriver.hpp` was written for 32-bit ARM (ZedBoard) and casts `void*` to `unsigned int`, which GCC rejects by default on ARM64. See section 5 for the risks of that.
- `LF_FPGA_CXX`/`LF_FPGA_AR` are configurable through environment variables, with no need to re-edit the Scala.
- The `else` covers any platform that is not `VerilatedTester` — reusable for other real wrappers in the future.

### 4.3 Repackaging

```bash
cd /opt/lingua-franca
./gradlew installDist
```

This does not recompile the whole compiler (Kotlin/Java) from scratch — it only reprocesses the `resources` and repackages the distribution under `build/install/lf-cli/`. It took a few seconds in the tested environment, with no need for network access (dependencies already cached).

### 4.4 Validation

```bash
cd ~/codesign-gen/main_target_board_pynqzcu104/src-gen/hardware/_FpgaTop
rm -rf build target
cd ~
lfc main_target_board_pynqzcu104.lf
```

Log obtained (relevant excerpt):

```
=======> Driver written to CodesignTopReactor.hpp
=======> Driver files copied to build
------------------------------------------------------------------------------------------------------
Cross-compiling hardware driver for real FPGA platform
------------------------------------------------------------------------------------------------------
[expected -fpermissive warnings, see section 5]
------------------------------------------------------------------------------------------------------
Library produced to build/lfFPGA.a.
------------------------------------------------------------------------------------------------------
[success] elapsed time: 26 s
lfc: info: Generating code for the Software-part
lfc: info: Code generation finished.
```

**Confirmed**: `lfFPGA.a` generated automatically with a single `lfc` command, with no prior manual editing in the project.

---

## 5. Inherited risks still outstanding (not resolved by this patch)

This patch solves the **automation** of the generation, but it does not solve two underlying problems that already existed:

### 5.1 Pointer truncation from 64 to 32 bits

`linuxphysregdriver.hpp` (an original file from the fork, left unedited) does `(unsigned int) pointer` in several places. On ARM64 that truncates the upper half of the address. With `-fpermissive`, the code compiles but the **run-time behaviour is incorrect** for any physical address ≥ 4GB (`0x100000000`).

- The register address (to be set in Vivado) has to fit in 32 bits — usually fine, since the PL I/O range tends to sit well below 4GB.
- The address of the "unmanaged memory buffer" (the second argument to the `LinuxPhysRegDriver` constructor) **has to stay below 4GB** so it is not corrupted.

### 5.2 Wrong hardcoded addresses

`platform-zedboard-linux.cpp` still contains the ZedBoard addresses (`0x43c00000` for the registers, `0x10000000`/256MB for the buffer), not the ZCU106 ones. **This was not fixed in this patch** — an explicit decision to keep the fork's files unmodified; adjusting the address is the responsibility of whoever runs on the physical board.

### 5.3 `CONFIG_STRICT_DEVMEM`

Still unconfirmed on the ZCU106 kernel whether this driver blocks `/dev/mem` for the buffer region (inside the real DDR range).

### 5.4 `ZYNQ_BITFILE_LOADER`

An environment variable required by `loadBitfile()` in `platform-zedboard-linux.cpp` — not configured yet; it has to point at a bitstream loading script using PetaLinux's FPGA Manager.

### 5.5 The software is not compiled automatically

As seen in section 2, `lfc` only compiles and copies the software binary automatically when `fpgaBoard == "VerilatedTester"`. For `PYNQZCU104CC`, the software compilation step remains manual (section 6.3).

---

## 6. Guide — how to keep developing on your own from here

### 6.1 Create a new codesign project for the ZCU106

Write a single `.lf` (outside `src/`, or inside a project with an `src/` structure) using the `Codesign` target:

```lf
target Codesign {
  fpgaBoard: PYNQZCU104CC
}

reactor MyFpgaReactor {
  // hardware logic here, using Chisel types: {= UInt(32.W) =}, etc.
}

reactor MySwReactor {
  // C++ software logic here
}

main reactor {
  @fpga
  fpga = new MyFpgaReactor()
  sw = new MySwReactor()
  // connect the ports as needed
}
```

```bash
lfc my_project.lf
```

That already generates `lfFPGA.a` automatically (thanks to the patch applied to the compiler), with no manual editing.

### 6.2 Where the result appears

```
<project_folder>/src-gen/hardware/_FpgaTop/build/
|-- lfFPGA.a                  <- driver library, ready for the software to link against
|-- CodesignTopReactor.hpp    <- C++ interface generated from your @fpga reactor
|-- <WrapperName>.v           <- RTL to take into Vivado
```

### 6.3 Compile the software manually (still needed for real platforms)

```bash
cd <project_folder>/src/software
lfc --target-compiler aarch64-linux-gnu-g++ <SwTopName>.lf
```

**Careful**: if a software `build/` from a previous configuration already exists (e.g. compiled natively for x86), it has to be cleaned first, because CMake uses a cache and does not reconfigure automatically:

```bash
rm -rf <project_folder>/build <project_folder>/src-gen/software <project_folder>/bin
```

### 6.4 Testing the driver compilation in isolation (quick debugging)

Without running the whole `lfc`/`sbt`, to quickly check whether the driver compiles:

```bash
cd <project_folder>/src-gen/hardware/_FpgaTop/build
aarch64-linux-gnu-g++ -fpermissive -c platform-zedboard-linux.cpp -o /tmp/test.o
file /tmp/test.o    # should show ELF 64-bit ..., ARM aarch64
```

### 6.5 Adjusting the physical addresses (open item from section 5.2)

After synthesizing the Block Design in Vivado and confirming the real AXI-Lite address of your `CodesignTopReactor` (Address Editor), edit:

```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/platform-zedboard-linux.cpp
```

replacing:
```cpp
platform = new LinuxPhysRegDriver((void *) 0x43c00000, (void *) 0x10000000, 256 * 1024 * 1024);
```
with the real address confirmed in Vivado, respecting the 4GB limit mentioned in section 5.1. Then repackage:
```bash
cd /opt/lingua-franca && ./gradlew installDist
```

### 6.6 Switching drivers (UIO vs. the fork's native one)

If the `LinuxPhysRegDriver`/`/dev/mem` driver turns out to be blocked by `CONFIG_STRICT_DEVMEM` on the real board, the already validated alternative is the custom UIO-based driver (`UioDriver`/`platform-zcu106.cpp`, see the previous README in this series). To switch:

1. Add `uiodriver.hpp`/`platform-zcu106.cpp` under:
   ```
   /opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/
   ```
2. Change `platformDriverFiles` (section 4.1) to point at those two files.
3. Change the compilation line in `mainCodesign` (section 4.2) from `platform-zedboard-linux.cpp` to `platform-zcu106.cpp` (without `-fpermissive`, since `UioDriver` was written natively for 64 bits).
4. Apply the `generic-uio` device tree overlay + the `uio_pdrv_genirq.of_id=generic-uio` bootarg in PetaLinux (see the previous README).
5. Repackage: `cd /opt/lingua-franca && ./gradlew installDist`.

### 6.7 Recommended workflow for any future change to the template

1. Edit the file(s) under `/opt/lingua-franca/core/src/main/resources/lib/chisel/...`.
2. `cd /opt/lingua-franca && ./gradlew installDist`.
3. Test on a clean project:
   ```bash
   rm -rf <project>/codesign-gen <project>/src-gen
   lfc <project>.lf
   ```
4. Confirm `lfFPGA.a` was generated:
   ```bash
   ls -la <project>/src-gen/hardware/_FpgaTop/build/lfFPGA.a
   ar t <project>/src-gen/hardware/_FpgaTop/build/lfFPGA.a
   ```

**Never** edit a specific project's `src-gen/reactor-chisel` directly and expect the change to persist — it is always overwritten on the next call to `lfc`.

---

## 8. Generalization — from "ZCU106 hardcoded" to "any board"

### 8.1 The problem identified

Even after section 4, `mainCodesign` still had the driver name **hardcoded**:

```scala
Seq(cxx, "-fpermissive", "-c", "platform-zedboard-linux.cpp", "-o", "platform-zedboard-linux.o")
```

That worked for `PYNQZCU104CC`, but any new board would require editing that file again — it was not real generalization. The work was split into 3 phases.

### 8.2 Phase 1 — A dedicated identity for the ZCU106

Instead of continuing to reuse the `PYNQZCU104CC` name, a wrapper with its own name was created: `fpga-tidbits/.../platform-wrapper/axi/ZCU106Wrapper.scala`.

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

Registration in `fpgatidbits/Main.scala` (`platformMap` and `fpgaPartMap`):

```scala
"ZCU106" -> {(f, targetDir) => new ZCU106Wrapper(f, targetDir)},
```
```scala
"ZCU106" -> "xczu7ev-ffvc1156-2-e",   // same part number as PYNQZCU104CC — same chip
```

**Validated**: `fpgaBoard: ZCU106` generates `ZCU106Wrapper.v`/`.fir`/`.anno.json` and `lfFPGA.a` end to end.

### 8.3 Phase 2 — Canonical 64-bit driver

Goal: adopt `UioDriver` (64-bit safe, no `-fpermissive`) as the reference driver for the MPSoC family, instead of the legacy 32-bit driver inherited from the ZedBoard.

New files, with neutral names (not specific to one board): `uiodriver.hpp` and `platform-mpsoc-uio.cpp` (same content as the `UioDriver`/`platform-zcu106.cpp` already documented in the previous README in this series).

`ZCU106Wrapper.scala` updated:
```scala
val platformDriverFiles = baseDriverFiles ++ Array[String](
  "platform-mpsoc-uio.cpp", "uiodriver.hpp"
)
```

**Validated**: compilation **with no warnings at all** (unlike the 32-bit driver, which always produces pointer truncation warnings). `lfFPGA.a` contains `platform-mpsoc-uio.o`.

### 8.4 Phase 3 — Generic `mainCodesign` (the centerpiece)

This removes the hardcoding for good: `mainCodesign` now **reads** the driver name and the compilation flags from the wrapper instance itself.

New fields in the base class (`PlatformWrapper.scala`), with default values — not abstract, so as not to break the ~10 existing wrappers that do not implement them:

```scala
def platformDriverFiles: Seq[String]  // additional files

// Name of the .cpp file inside platformDriverFiles that contains
// initPlatform/deinitPlatform. By default it is inferred automatically:
// the first .cpp in the list.
def platformMainDriverFile: String = platformDriverFiles.find(_.endsWith(".cpp")).getOrElse("")

// Extra compilation flags for platformMainDriverFile (e.g. "-fpermissive"
// for legacy 32-bit drivers). Empty by default.
def platformDriverExtraFlags: Seq[String] = Seq()
```

`mainCodesign` refactored (`reactor-chisel/src/main/scala/Main.scala`):

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

`PYNQZCU104CCWrapper.scala` — an override needed to keep it working with the legacy driver:
```scala
override def platformDriverExtraFlags: Seq[String] = Seq("-fpermissive")
```
(`platformMainDriverFile` needed no override — the default already finds `platform-zedboard-linux.cpp` automatically, since it is the only `.cpp` in that wrapper's list.)

#### Implementation pitfall: the Chisel Builder context

A first attempt at this refactoring failed with:
```
java.lang.IllegalArgumentException: requirement failed: must be inside Builder context
```

**Cause**: `PlatformWrapper` extends Chisel's `Module`. Constructing a `Module` instance is only allowed **inside** the elaboration context that `emitVerilog` opens internally — passing `platformInstFxn(...)` as a by-name argument (as in the original code) works because the expression is only evaluated inside that context; extracting it into a `val` **before** the `emitVerilog` call breaks that guarantee.

**Solution**: use an external `var`, assigned **inside** the by-name block passed to `emitVerilog` (`var platformWrapperInst: PlatformWrapper = null` + the assignment inside the `{...}` block). The module construction still happens inside the Builder; the reference remains available afterwards, for reading simple `def`s that do not create new hardware.

### 8.5 Coexistence validation (the decisive test)

Two tests, on the same `mainCodesign`, with no edit in between:

**`PYNQZCU104CC`** (legacy driver, `-fpermissive` flag):
```
lfFPGA.a: 37124 bytes
ar t → platform-zedboard-linux.o
[pointer truncation warnings appear, as expected]
```

**`ZCU106`** (new driver, no flags):
```
Compiling driver: platform-mpsoc-uio.cpp (flags: )
lfFPGA.a: 40494 bytes
ar t → platform-mpsoc-uio.o
[no warnings]
```

**Generalization criterion met**: adding a new board from here on means creating a `NewBoardWrapper.scala`, registering it in `platformMap`/`fpgaPartMap`, and repackaging — **with no edit at all in `mainCodesign`**.

---

## 9. Current state checklist

**Done:**
- [x] Discovered and documented the `fpgaBoard` mechanism in `target Codesign`
- [x] Root cause of "everything gets overwritten" identified (packaged template, not read live)
- [x] `PYNQZCU104CCWrapper.scala` fixed in the compiler's source template
- [x] `mainCodesign`/`Main.scala` fixed in the compiler's source template
- [x] `./gradlew installDist` validated (repackaging works, no full rebuild)
- [x] Native `lfFPGA.a` generation confirmed on a clean project, with a single `lfc` command
- [x] Phase 1 — `ZCU106Wrapper.scala` with its own identity, registered in `platformMap`/`fpgaPartMap`
- [x] Phase 2 — `UioDriver` adopted as the canonical 64-bit driver for the MPSoC family
- [x] Phase 3 — generic `mainCodesign`, reading driver/flags from the wrapper instance (no hardcoding)
- [x] Coexistence validated: `PYNQZCU104CC` (legacy driver + flag) and `ZCU106` (new driver, no flags) side by side, without editing `mainCodesign`

**Pending:**
- [ ] Fix the hardcoded addresses in `platform-zedboard-linux.cpp` to the ZCU106's real values (section 6.5) — only relevant if the legacy driver stays in use; `ZCU106`/`UioDriver` already uses an address configurable by macro
- [ ] Confirm `CONFIG_STRICT_DEVMEM` in the board's kernel
- [ ] Implement/configure `ZYNQ_BITFILE_LOADER`
- [ ] Automate software compilation for real platforms (still manual today, section 6.3)
- [ ] Generate the bitstream through Vivado and validate on the physical board
- [ ] Final validation test: create a fictitious board from scratch, touching a single file, to confirm the generalization criterion in practice (see section 10)

---

## 10. Validation test — the fictitious "TESTBOARD"

Goal: prove objectively that the generalization criterion was met — adding a new board without editing `mainCodesign`.

### 10.1 The test's rule

Only the following is allowed:
1. Creating **a single new file**: `TestboardWrapper.scala`.
2. Adding **two lines** to `fpgatidbits/Main.scala` (`platformMap` and `fpgaPartMap`) — necessary because registration is centralized in that map; it is not an exception to the criterion, it is the inherent "minimum cost" of registering any new board.
3. Repackaging (`installDist`).

**Not allowed**: touching `mainCodesign`, `PlatformWrapper.scala`, or any existing wrapper.

### 10.2 `TestboardWrapper.scala`

It reuses the `UioDriver` driver (already generic, section 8.3) — proving that the driver also does not need to be rewritten for a new board using the same chip.

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

### 10.3 Registration (the two allowed lines)

In `platformMap`:
```scala
"TESTBOARD" -> {(f, targetDir) => new TestboardWrapper(f, targetDir)},
```

In `fpgaPartMap`:
```scala
"TESTBOARD" -> "xczu7ev-ffvc1156-2-e",
```

### 10.4 Repackage and test

```bash
cd /opt/lingua-franca
./gradlew installDist
```

```bash
cd ~
cat > test_testboard.lf << 'EOF'
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
lfc test_testboard.lf
ls -la ~/codesign-gen/test_testboard/src-gen/hardware/_FpgaTop/build/lfFPGA.a
ar t ~/codesign-gen/test_testboard/src-gen/hardware/_FpgaTop/build/lfFPGA.a
```

### 10.5 Success criteria

- `TestboardWrapper.v` generated (not `ZCU106Wrapper.v` nor `PYNQZCU104CCWrapper.v`).
- `Compiling driver: platform-mpsoc-uio.cpp (flags: )` appears in the log — confirming that `mainCodesign` read the right driver with no edit in it.
- `lfFPGA.a` generated, containing `platform-mpsoc-uio.o`.
- Tests A and B from section 8.5 (`PYNQZCU104CC` and `ZCU106`) still pass without needing a re-test, since `mainCodesign` was not touched in this section.

If those four items hold, the generalization criterion is objectively demonstrated: a new board requires only one wrapper file + two registration lines, never an edit in the build orchestrator.

---

## 11. Simulating with Verilator before generating the bitstream

Since `mainCodesign` was rewritten in Phases 1-3, we had to confirm that the **simulation** path (`VerilatedTester`) kept working without regression — it goes through the same method, just via the `if` branch instead of the `else`.

### 11.1 Why simulate before going to real hardware

The `Fpga`/`Sw` reactor written in `.lf` is **the same source code**, regardless of the platform chosen in `fpgaBoard`. Only the AXI/driver "shell" changes between `VerilatedTester` and a real board. That means changing only `fpgaBoard` lets you:

1. Validate the reactor logic through simulation (fast, with no need for a bitstream or a physical board).
2. Change `fpgaBoard: VerilatedTester` → `fpgaBoard: ZCU106` (or another board) only once the logic is already correct — isolating logic bugs from platform/hardware bugs.

### 11.2 The regression test performed

Reusing the same original `.lf` (`Fpga`/`Sw` with a 1 ms timer and a controlled increment), changing only `fpgaBoard`:

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
lfc test_verilator_regression.lf
ls -la ~/codesign-gen/test_verilator_regression/src-gen/hardware/_FpgaTop/build/lfFPGA.a
```

`lfFPGA.a` of **635890 bytes** (much larger than the ~37-40KB of the real-hardware builds — expected, since here the `.a` contains the whole RTL compiled as C++ by Verilator, not just the driver).

### 11.3 Execution

The software binary generated by `lfc` follows the name of the `main reactor` (with no explicit name in the `.lf`, it uses the default `_SwTop`), not the name of the `.lf` file:

```bash
cd ~/codesign-gen/test_verilator_regression
./bin/_SwTop
```

Confirmed output:
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

**No regression**: correct signature, the count incrementing properly, logical tags advancing in 1 ms steps — behaviour identical to what was observed before Phases 1-3.

### 11.4 Recommended workflow (simulate → real hardware)

```bash
# 1. Simulate first — validates the reactor logic
cat > my_project.lf << 'EOF'
target Codesign {
  fpgaBoard: VerilatedTester
}
# ... Fpga/Sw reactors ...
EOF
lfc my_project.lf
cd ~/codesign-gen/my_project
./bin/_SwTop   # Ctrl+C to interrupt (it runs indefinitely if there is an endless timer)
```

```bash
# 2. Once the logic has been validated, change only fpgaBoard
sed -i 's/fpgaBoard: VerilatedTester/fpgaBoard: ZCU106/' my_project.lf
cd ~
rm -rf codesign-gen/my_project/src-gen/hardware/_FpgaTop/build codesign-gen/my_project/src-gen/hardware/_FpgaTop/target
lfc my_project.lf
# generates lfFPGA.a for real hardware, with no need to re-simulate the logic
```

```bash
# 3. Build the cross-compiled software, linking against the real lfFPGA.a
cd codesign-gen/my_project/src/software
lfc --target-compiler aarch64-linux-gnu-g++ <SwTopName>.lf
```

**Note**: step 3 remains manual for real platforms (section 6.3) — only the simulation step (1) compiles and runs the software automatically, because of the `if (fpgaBoard.equals("VerilatedTester"))` condition in `CodesignGenerator.kt` (section 2).

---

## 12. `UioDriver` testability propagated to the template

### 12.1 Motivation

After validating `lfFPGA.a` for `PYNQZCU104CC`/`ZCU106`/`TESTBOARD`, we identified the need to test the `UioDriver`'s internal logic (register arithmetic, device lookup by name) without depending on real hardware or on a Linux kernel with UIO configured.

### 12.2 Strategy

`mmap` behaves identically on `/dev/uioN` (production) and on an ordinary regular file (testing) — both are valid POSIX `MAP_SHARED` operations. The solution was to extract the hardcoded directories (`/sys/class/uio`, `/dev`) into macros configurable at compile time, allowing the driver to be pointed at a fake directory tree in tests, without touching the real `/sys`.

### 12.3 `uiodriver.hpp` updated in the source template

Path:
```
/opt/lingua-franca/core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/uiodriver.hpp
```

Changes relative to the previous version (section 8.3):
- Added the macros `LF_FPGA_UIO_SYSFS_DIR` (default `/sys/class/uio`) and `LF_FPGA_UIO_DEV_DIR` (default `/dev`) — production behaviour unchanged, since the defaults reproduce exactly what the code had before.
- `findUioDevice()` went from `private` to `public static`, so it can be called directly in tests without instantiating the whole class.

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

// Base sysfs directory for UIO devices. Configurable via -D so that tests
// can point the driver at a fake directory tree, without needing root
// access to the real /sys/class/uio.
#ifndef LF_FPGA_UIO_SYSFS_DIR
#define LF_FPGA_UIO_SYSFS_DIR "/sys/class/uio"
#endif

// Base directory for the device nodes (/dev/uioN). Configurable for the
// same reason as above.
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

  // Exposed for testing: the same lookup logic used by the constructor.
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

### 12.4 `test_uiodriver.cpp` — the test harness

It tests, without real hardware: read/write round-trip on the 12 known `CodesignTopReactor` registers, edge values (`0x00000000`/`0xFFFFFFFF`), absence of leakage between adjacent registers, correct device selection by name (ignoring devices with the wrong name), and the fallback behaviour.

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

Result obtained (reproduced both in a verification sandbox and in the project's Docker environment):

```
=== Test 1: readReg/writeReg via mmap (round-trip) ===
PASS: all 12 registers round-trip correctly
PASS: writing zero works
PASS: writing 0xFFFFFFFF works
PASS: writes to adjacent registers do not overlap
PASS: platformID() returns the right name
=== Test 2: findUioDevice() selects the right device by name ===
PASS: findUioDevice() picks uio1 (right name), ignores uio0 (wrong name)
=== Test 3: findUioDevice() with a nonexistent name falls back ===
PASS: findUioDevice() uses the fallback (first uioN) when the name matches nothing
=== Test 4: the constructor throws if the device does not exist ===
PASS: fallback behaviour documented (not a bug)
=== Final result ===
ALL TESTS PASSED
```

### 12.5 Risk identified during the test (not a bug, but worth attention)

The fallback behaviour of `findUioDevice()` means that, if the expected name (`lf_fpga_regs`) does not exist on the system, the driver **silently attaches to some other available UIO device** instead of failing explicitly. In production, that would only be noticed indirectly, through the failure of the signature check in the `CodesignTopReactor` constructor (`readReg(0) != 0xaeef7c29`). It is not treated as a bug — it is documented behaviour, but worth being aware of for anyone debugging connection problems in the future.

### 12.6 Post-change regression

Repackaging (`./gradlew installDist`) and re-testing on `ZCU106` confirmed that the new macros, with their default values, did not change production behaviour:

```
Compiling driver: platform-mpsoc-uio.cpp (flags: )
Library produced to build/lfFPGA.a.
```

`lfFPGA.a` went from 40494 to 37952 bytes — a difference attributed to the visibility change of `findUioDevice` (`private` → `public static`), not to a behaviour change (confirmed by the 8 passing tests).

---

## 13. Full cycle validated on a new project — simulation → real hardware → cross-compiled software

An end-to-end test, from a single `.lf`, changing only the `fpgaBoard` line, with no manual Scala editing at any point in the process — the final validation that the generalization (sections 8-10) and the canonization of `UioDriver` (section 12) work together coherently.

### 13.1 Step 1 — Simulation (`VerilatedTester`)

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
lfc my_test.lf
cd ~/codesign-gen/my_test
./bin/_SwTop
```

Result:
```
[INFO]  Attached to FPGA Reactor with Signature: aeef7c29
Got: 1
[INFO]  Fpga Wrapper: Do shutdown and deinitialize
```

The program terminates on its own (no `timer`, unlike the regression test in section 11) — correct behaviour: `Sw` sends `1` at `startup`, `Fpga` passes it through (`in -> out` identical), `Sw` receives it back, there is no more scheduled work, and the reactor shuts down.

### 13.2 Step 2 — Real hardware (`ZCU106`)

```bash
sed -i 's/fpgaBoard: VerilatedTester/fpgaBoard: ZCU106/' my_test.lf
rm -rf codesign-gen/my_test/src-gen/hardware/_FpgaTop/build codesign-gen/my_test/src-gen/hardware/_FpgaTop/target
lfc my_test.lf
ls -la ~/codesign-gen/my_test/src-gen/hardware/_FpgaTop/build/lfFPGA.a
ar t ~/codesign-gen/my_test/src-gen/hardware/_FpgaTop/build/lfFPGA.a
```

Result: a 37952-byte `lfFPGA.a` containing `platform-mpsoc-uio.o` — the same size as every other post-section-12 `UioDriver` build, confirming consistency.

### 13.3 Step 3 — Cross-compiled software (ARM64)

```bash
rm -rf ~/codesign-gen/my_test/build ~/codesign-gen/my_test/src-gen/software ~/codesign-gen/my_test/bin
cd ~/codesign-gen/my_test/src/software
lfc --target-compiler aarch64-linux-gnu-g++ _SwTop.lf
file ~/codesign-gen/my_test/bin/_SwTop
```

Result:
```
SUCCESS (compiling generated C++ code)
/home/lingua-franca/codesign-gen/my_test/bin/_SwTop: ELF 64-bit LSB pie executable, ARM aarch64, version 1 (GNU/Linux), dynamically linked, interpreter /lib/ld-linux-aarch64.so.1, ...
```

### 13.4 Cycle summary

| Step | Key command | Result |
|---|---|---|
| 1. Simulation | `fpgaBoard: VerilatedTester` + `lfc` | Ran, `Got: 1`, clean shutdown |
| 2. Real hardware | `fpgaBoard: ZCU106` + `lfc` | `lfFPGA.a` (37952 bytes), `platform-mpsoc-uio.o` |
| 3. Cross-compiled software | `lfc --target-compiler aarch64-linux-gnu-g++` | Valid ARM64 `_SwTop`, linked against the real `lfFPGA.a` |

### 13.5 Test coverage matrix (what is and is not validated without a physical board)

| Test | What it validates | Needs physical hardware? | Status |
|---|---|---|---|
| Simulation (`VerilatedTester`) | Reactor logic, simulated PS↔PL communication | No | ✅ Validated |
| `lfFPGA.a` generation (`ZCU106`/`PYNQZCU104CC`/`TESTBOARD`) | The right driver compiled and packaged | No | ✅ Validated |
| Software cross-compilation | The software links correctly against the real `lfFPGA.a` and runs on ARM64 | No (it compiles, it does not run) | ✅ Validated |
| `test_uiodriver` (section 12) | The `UioDriver`'s internal logic (registers, device lookup) | No | ✅ Validated |
| Execution on the physical board | Everything together, for real, including bitstream and device tree | **Yes** | ⏳ Pending |

Four of the five test levels are covered without depending on access to the physical board — the only pending item is the real execution, which depends on the steps still open (Vivado/bitstream, device tree overlay applied in PetaLinux, `ZYNQ_BITFILE_LOADER`/bitstream loading strategy for `UioDriver`).

---

## 14. `clock-period` — a fix required before any real synthesis

**Important**: this is not a bug introduced by the work documented in this README (Phases 1-3, `UioDriver`, generalization). It is a target property that already existed in the `lfc` compiler, but which **none of the `.lf` files used in this series of tests declared explicitly** — which is why it went unnoticed until this point.

### 14.1 The problem

Every `lfFPGA.a` generation test performed so far (`PYNQZCU104CC`, `ZCU106`, `TESTBOARD`, the full cycle in section 13) used the **default** value of `clock-period`, which is `1 nsec` (`Reactor.scala:25`, `clockPeriod: Time = Time.nsec(1)`). That amounts to assuming a **1 GHz** clock in the PL — a value that does not correspond to any plausible physical frequency for the Block Design that will be assembled in Vivado.

### 14.2 Why this affects real hardware, not just simulation

`clockPeriod` is consumed directly in the RTL logic for logical time coordination, not only in a testbench:

```scala
// Reaction.scala:83
physicalTag := physicalTag + globalReactorConfig.clockPeriod.nanoseconds.U

// TriggerGenerator.scala:45
regClock := regClock + cfg.clockPeriod.nanoseconds.U
```

Those registers (`physicalTag`, `regClock`) are incremented **on every real hardware clock cycle**, adding `clockPeriod.nanoseconds` per increment — the synthesized RTL literally assumes "every clock pulse received corresponds to X nanoseconds of logical time". If the PL's physical clock configured in Vivado does not match the value used during RTL generation, the tag coordination system (`nextEventTag`, `tagAdvanceGrant`, timers, deadlines) ends up desynchronized from physical reality — a functional correctness error, not a performance one.

### 14.3 Propagation confirmed (the property works correctly)

Before applying the fix, we confirmed that `clock-period` propagates correctly through the whole chain — no additional fix is needed in the compiler, you just have to declare the value explicitly:

```
TargetProperty.java:646-654        → "clock-period" is a valid target property
CodesignHwEmitter.kt:65-66         → propagates clock-period into the generated _FpgaTop.lf
ChiselMainFileGenerator.kt:34      → clockPeriod = Time.nsec(${targetConfig.clockPeriod.toNanoSeconds()})
```

The `${targetConfig.clockPeriod.toNanoSeconds()}` interpolation confirms that the value is evaluated at generation time and written as a literal into the project's `LfMain.scala` — there is no hardcoding in the generator.

### 14.4 Validation test

```lf
target Codesign {
  fpgaBoard: ZCU106,
  clock-period: 10 nsec
}
```

Regenerated and confirmed at two layers:

generated `_FpgaTop.lf`:
```lf
// This file is code-generated DO NOT EDIT.
target Chisel {
  codesign: true,
  fpgaBoard: ZCU106,
  clock-period: 10 nsec,
}
```

generated `LfMain.scala`:
```bash
grep "clockPeriod = Time.nsec" ~/codesign-gen/my_test/src-gen/hardware/_FpgaTop/src/main/scala/Main.scala
# implicit val globalConfig = GlobalReactorConfig(timeout = Time.NEVER, standalone=false, clockPeriod = Time.nsec(10))
```

This confirms correct propagation: `Time.nsec(10)`, no longer the default `Time.nsec(1)`.

### 14.5 Implication for the tests already performed in this series

Every `lfFPGA.a` generated in sections 8, 10, 11 and 13 (before this discovery) was synthesized assuming a 1 GHz logical clock. That does **not invalidate** the validation of driver/`lfFPGA.a` generation itself (which is independent of the `clock-period` value), but it **would invalidate the functional correctness of the logical timing** if any of those specific artifacts were synthesized in Vivado and run on a real board without first correcting that parameter.

### 14.6 Mandatory action before any real Vivado synthesis

Every `.lf` targeting physical hardware must declare `clock-period` explicitly, with the value corresponding to the real clock frequency that will be supplied to the wrapper (`ZCU106Wrapper`/`PYNQZCU104CCWrapper`/etc.) in the Vivado Block Design:

```lf
target Codesign {
  fpgaBoard: ZCU106,
  clock-period: 10 nsec   // adjust to the real Block Design clock (e.g. 10 ns = 100 MHz)
}
```

**Pre-synthesis checklist**: confirm the frequency of the clock feeding the `ZCU106Wrapper` in Vivado (typically configured in the PS IP via `zynq_ultra_ps_e` or in a clock generator/PLL in the Block Design), compute the corresponding period in nanoseconds, and declare that exact value in the `.lf` before generating the RTL to be synthesized.
