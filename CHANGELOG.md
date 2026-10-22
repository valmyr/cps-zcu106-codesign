# Changelog - Dockerfile (Lingua Franca `chisel3` fork / Codesign target)
History of the fixes applied to the image, in the order in which they were
introduced. Each entry references the corresponding item in `README.md`
(the "READ BEFORE USING" section), where the full explanation of the problem
and of the fix is detailed.

## [Unreleased]

### Fixed
- **`PYNQZCU104CCWrapper` never actually copied the driver files.**
  The wrapper only *defined* `platformDriverFiles`, without calling
  `resourceCopyBulk`/`generateRegDriver` - `build/` never contained
  `platform.h`/`CodesignTopReactor.hpp`/the driver for that platform.
  Fixed; it now also overrides `platformDriverExtraFlags` with
  `-fpermissive` (needed because `linuxphysregdriver.hpp` was written for
  32-bit ARM and casts `void*` to `unsigned int`, which is rejected by
  default on ARM64).

  See README, item 8.1.

- **The `xlnk` driver does not exist in modern `linux-xlnx` kernels.**
  Confirmed by reading the driver's own source code in the BSP of a real
  ZCU106 (PetaLinux 2025.2): `xlnk`'s `of_match_table` is empty by default,
  so the device is never created. Replaced with a canonical driver based on
  the kernel's UIO subsystem (`uio_pdrv_genirq` + `mmap` of `/dev/uioN`),
  with no dependency on any proprietary Xilinx library - `uiodriver.hpp` /
  `platform-mpsoc-uio.cpp`, both new.

  See README, item 8.2.

- **`mainCodesign` had the driver to compile *hardcoded* for
  `VerilatedTester` only.**
  Generalized: `PlatformWrapper.scala` gained the fields
  `platformMainDriverFile` (inferred automatically) and
  `platformDriverExtraFlags` (empty default), with safe defaults that do not
  break the pre-existing wrappers that do not override them.
  `mainCodesign` (`reactor-chisel/src/main/scala/Main.scala`) now reads
  those fields from the wrapper instance instead of having the file
  name/flags fixed in the code - any new real board now works without
  editing that file again. Validated by creating a fictitious board
  (`TESTBOARD`) touching a single new file + two registration lines.

  Also fixed along the way: instantiating the wrapper outside the by-name
  block passed to `emitVerilog` failed with
  `java.lang.IllegalArgumentException: requirement failed: must be inside
  Builder context` (`Module` elaboration is only allowed inside the Chisel
  Builder context) - resolved by capturing the instance in an external
  `var`, assigned inside the by-name block itself.

  See README, item 8.3.

- **The software was never compiled automatically for real boards (only for
  `VerilatedTester`).**
  `CodesignGenerator.kt`: the condition `if
  (targetConfig.fpgaBoard.equals("VerilatedTester"))` that gated the
  `swEmitter.compile()` call became unconditional (`if (true)`).

  See README, item 9.1.

- **`--target-compiler` did not reach the second internal software
  generation pass, even with the fix above.**
  `CodesignSwEmitter.compile()` creates a new `MainContext`/`TargetConfig`
  internally, with an empty `Properties()` as command-line arguments - the
  original `--target-compiler` never reached that second generation, making
  the automatic compilation always fall back to the native x86 compiler and
  fail at link time against the ARM64 `lfFPGA.a`
  (`Relocations in generic ELF (EM: 183)` /
  `error adding symbols: file in wrong format`). Fixed in
  `generateTarget()` (`CodesignSwEmitter.kt`): it now writes
  `compiler: "<value>"` into the generated `_SwTop.lf` whenever
  `targetConfig.compiler` is not empty, propagating the compiler correctly
  through the second generation.

  See README, item 9.2.

- **The `file` package was missing from the base image.**
  Needed both for the smoke tests (checking the architecture of the
  generated binary) and for general manual use; added to the `apt-get
  install` package list.

  See README, item 11.

- **`csr` name collision in `AXIPlatformWrapper.scala`.**
  Hardware elaboration failed with:
  `Attempted to name ZedBoardWrapper.csr: IO[AXILiteExternalIF] with a
  duplicated name 'csr'. Use suggestName to seed a unique name`.
  The internal `Wire` of the regfile logic, previously named `csr`, was
  renamed to `csrSlave` (the external `csr` IO was kept). The fix is now
  applied to both copies of the file found in the repository:
  - `core/src/main/resources/lib/chisel/.../platform-wrapper/axi/AXIPlatformWrapper.scala`
  - `core/build/resources/main/lib/chisel/.../platform-wrapper/axi/AXIPlatformWrapper.scala`

  Taking advantage of the same edit, the construction of the write data
  queue changed from `FPGAQueue(writeBurstAdp.out_writeData, 2) <>
  mem(i).writeData` to an explicit `Module(new FPGAQueue(...))` instance.

  See README, item 8.

- **`chisel3`/`chiseltest` out of sync across the 4 files that declare the
  version.**
  The previous version bump (`3.5.3 → 3.6.0`, `0.5.1 → 0.6.0`) did not
  cover `fpga-tidbits/build.sbt` (which started from `3.5.6`/`0.5.6`),
  causing the binary conflict `chisel3_2.13:3.6.0 ... is selected over
  3.5.6 for test` / `chiseltest_2.13:0.5.6 (depends on 3.5.6)`. The
  replacement now uses a patch-version regex
  (`3\.5\.[0-9]+` → `3.6.0`, `0\.5\.[0-9]+` → `0.6.0`) instead of exact
  values, guaranteeing that all 4 files end up on the same compatible pair.

  See README, item 2.

- **Submodules pointing at SSH (`git@github.com:...`) with no key available
  inside the container.**
  The `lingua-franca` `.gitmodules` is fixed via `sed` to HTTPS; the nested
  submodule (`reactor-chisel` → `fpga-tidbits`) is covered by the global
  `git config --global url."https://github.com/".insteadOf
  git@github.com:`, with no need for a second `sed`.

  See README, item 3.

- **`.asUInt()` incompatible with the updated Chisel version** in
  `PlatformWrapper.scala` (`fpga-tidbits`), fixed via `sed` to `.asUInt`
  (without parentheses).

### Added
- **Native support for the `ZCU106` board** (`fpgaBoard: ZCU106`), with its
  own wrapper (`ZCU106Wrapper.scala`, same chip as the PYNQZCU104CC,
  `xczu7ev-ffvc1156-2-e`), registered in `platformMap`/`fpgaPartMap`
  (`fpgatidbits/Main.scala`). It compiles cleanly, with no warnings (unlike
  `PYNQZCU104CC`, which uses the legacy 32-bit driver with
  `-fpermissive`).

  See README, item 8.

- **Four smoke tests embedded in the `docker build`**, validating: the
  `UioDriver` logic in isolation, without hardware; the end-to-end
  simulation flow (`VerilatedTester`); generation of `lfFPGA.a` for
  `ZCU106`; automatic compilation of the software for `ZCU106` with a
  single command. A failure in any of them prevents the image from being
  built, instead of only showing up the first time someone uses the
  container.

  See README, the "Embedded smoke tests" section.

- **Oh My Zsh theme (`jonathan`)**, configured via `sed` in `~/.zshrc`
  right after installation. Purely cosmetic.

  See README, item 13.

- **`fpgaBoard` requirement in `target Codesign`.**
  Documented the obligation to declare the target platform in every `.lf`
  that uses `target Codesign` (e.g. `fpgaBoard: "PYNQZCU104CC"`), together
  with the list of platforms supported by `fpga-tidbits` and the manual
  equivalent via `sbt "fpgatidbits/run verilog <accel> <platform>"` inside
  `src-gen/reactor-chisel`.

  See README, item 7.

- **Disk space requirement:** at least 3 GB free before the `docker build`,
  not counting what `sbt` downloads during the first build of a real
  Codesign target (`chisel3`/`chiseltest`/Scala).

  See README, the "Requirements" section.

### Known (not fixed in this image)
- **GCC 11 `internal compiler error: Segmentation fault`** when compiling
  `main.cc` (generated automatically by the framework, which uses
  `std::regex` internally) while cross-compiling for AArch64. It occurred
  intermittently during development; insufficient memory was ruled out as
  the cause (62 GB available in the environment where it happened).
  Hypotheses not yet confirmed: a GCC 11 bug specific to that template
  pattern; sensitivity to the optimization level.

  See README, item 10.

- **The `ZCU106Wrapper` reset has to be connected manually to the
  active-high output of `proc_sys_reset` in the Vivado Block Design**
  (`peripheral_reset`, not `peripheral_aresetn`) - outside the scope of
  this Dockerfile/compiler, documented in the physical board bring-up
  backlog.

- **Bug under investigation in logical time coordination**:
  `logicalTagComplete` gets stuck at `Tag.FOREVER` (`0x80000000`) once the
  accumulated logical execution time goes beyond ~2.15 seconds (`2^31` ns) -
  an improper signed comparison somewhere in the generated Chisel logic is
  suspected; no confirmed fix yet.

  See README, "Known open items".
