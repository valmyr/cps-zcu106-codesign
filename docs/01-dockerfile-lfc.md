# Dockerfile - Build environment for Lingua Franca (`chisel3` fork / Codesign target)
> For the chronological history of every fix applied to the image, see
> [`CHANGELOG.md`](../CHANGELOG.md).

## Requirements

- **Disk space: at least 15 GB free** before starting the `docker build`.
  That figure covers the base image + JDK 17 + the gradle/sbt toolchain. It
  does **not** include what sbt downloads during the first build of a real
  Codesign target (`chisel3`/`chiseltest`/Scala and transitive dependencies -
  see item 5 below), so reserve extra space beyond that minimum, especially
  if you are going to build several different Codesign tests (each one
  creates its own folder under `codesign-gen/`).
- Docker with support for `# syntax=docker/dockerfile:1` (heredocs in `RUN`).
- Network access during the build (see item 6 below).
- **Recommended memory for the `docker build`: at least 8 GB free.**
  It is not a blocker in most cases (62 GB total was enough in testing), but
  compiling `main.cc` (generated automatically by the framework, which uses
  `std::regex` internally) has already triggered a GCC 11 *internal compiler
  error* when cross-compiling for AArch64 under certain conditions - see
  item 10 (Known problems) before assuming it is a shortage of memory.

## READ BEFORE USING

1) **This uses a fork, not the official lf-lang.**
   This Dockerfile clones `erlingrj/lingua-franca` on the `chisel3` branch, a
   research fork that adds the `Codesign` target: it generates hardware
   (Chisel → FPGA, through the `reactor-chisel` and `fpga-tidbits`
   submodules) and software (C++) from the same `.lf`. It is not the
   "mainline" `lf-lang/lingua-franca` compiler - if you only need the
   standard targets (C, C++, Python, Rust, TypeScript), this is not the right
   image.

2) **`chisel3` and `chiseltest` must always move as a pair (3.6.x with
   0.6.x).**
   The repo declares those two versions in 4 different files
   (`ChiselSbtGenerator.kt`, `reactor-chisel/build.sbt`,
   `reactor-chisel/build.sc`, `fpga-tidbits/build.sbt`), each starting from a
   different patch version. If the Chisel version is ever bumped again,
   update all 4 occurrences and check the official compatibility table
   (chisel-lang.org/docs/appendix/versioning) before changing the numbers.
   Migrating only some of them is exactly what caused, here, the error
   `chisel3_2.13:3.6.0 ... is selected over 3.5.6` /
   `chiseltest_2.13:0.5.6 (depends on 3.5.6)` - and it only shows up when
   someone actually runs a Codesign-target `.lf` (not on `lfc --help`), so it
   is easy to publish the image without noticing that it is broken.

3) **Submodules use SSH by default upstream.**
   Both the `lingua-franca` `.gitmodules` (which points at `reactor-chisel`)
   and `reactor-chisel`'s own (which points at the nested `fpga-tidbits`) use
   `git@github.com:...`. Without an SSH key inside the container, that fails
   at `git submodule update`. The Dockerfile rewrites it to HTTPS via `sed`
   on `.gitmodules` + `git config --global
   url."https://github.com/".insteadOf git@github.com:` (that global config
   is what covers the nested submodule, with no need for another `sed`). If
   upstream adds another submodule, check whether it also uses SSH.

4) **The container runs as the `lingua-franca` user, not root.**
   That user owns `/opt/lingua-franca` - it has to, because that is where
   `lfc` writes the generated code (`test/Codesign/codesign-gen/...`). To run
   something as root inside an already started container:
   `docker exec -u root -it <container> bash`.

5) **The cache copied over to the user does not include
   `chisel3`/`chiseltest`.**
   The image build only compiles `lfc` itself (`./gradlew assemble`) and calls
   `lfc --help` - neither of those touches the Chisel sbt project. In other
   words: the first time anyone runs `lfc <file>.lf` on a real Codesign
   target inside a container, sbt still downloads `chisel3`/`chiseltest`/the
   Scala compiler from scratch (minutes, depending on the network) - once per
   container created with `docker run`, not once per command. Eliminating
   that wait would require running a real Codesign test *during* the build
   (trading build time for first-run time); smoke test 4 (item 8) already
   does exactly that for `ZCU106`, so containers built from this Dockerfile
   already come with the sbt cache "warm" for that board specifically.

6) **Network required during the build:** GitHub (clone + submodules), the
   sbt repository (`repo.scala-sbt.org`) and the Ubuntu `apt` mirrors. Maven
   Central / Coursier come into play from the first real `sbt run`/`lfc` that
   generates hardware (item 5), including during this Dockerfile's own smoke
   tests (item 8).

7) **Specifying the target platform in the `Codesign` target is mandatory.**
   Every `.lf` that uses `target Codesign` must declare the `fpgaBoard`
   property, stating which board/platform the Chisel hardware should be
   generated for:
   ```lf
   target Codesign {
       fpgaBoard: ZCU106
   }
   ```
   `fpgaBoard` is one of the options supported by `fpga-tidbits`:
   - `VerilatedTester` (simulation - the only value for which the original
     `lfc` compiled the software automatically; see item 8 about extending
     that automation to any board)
   - `GenericSDAccel`
   - `PYNQZ1`
   - `PYNQZCU104`
   - `ZC706`
   - `PYNQZCU104CC`
   - **`ZCU106`** *(added in this Dockerfile's fork - see item 8)*
   - `ZedBoard`
   - `WX690T`
   - `PYNQU96CC`
   - `Tester`
   - `PYNQU96`

   That same platform is what you pass manually to `fpga-tidbits`' `sbt` when
   generating/running the hardware outside the `lfc` flow, inside the
   generated project's `src-gen/reactor-chisel`:
   ```bash
   cd Project/src-gen/reactor-chisel
   sbt "fpgatidbits/run verilog HelloAccel PYNQZCU104CC"
   sbt "fpgatidbits/run"
   ```
   Without `fpgaBoard` set in the target (or passed to `sbt`), hardware
   generation does not know which board to compile for and fails.

8) **`ZCU106` - full native support (driver, `lfFPGA.a` generation,
   automatic software compilation).**

   The ZCU106 uses the same chip as the ZCU104 (`xczu7ev-ffvc1156-2-e`), but
   the original fork had neither a wrapper by that name nor a driver that
   worked outside the PYNQ ecosystem. Three problems were fixed, in a chain:

   **8.1) `PYNQZCU104CCWrapper` never actually copied the driver files.**
   The original wrapper only *defined* `platformDriverFiles`, never calling
   `resourceCopyBulk`/`generateRegDriver` - which is why `build/` never
   contained `platform.h`/`CodesignTopReactor.hpp`/the driver itself for that
   platform. Fixed (it also overrides `platformDriverExtraFlags` with
   `-fpermissive`, needed because `linuxphysregdriver.hpp` was written for
   32-bit ARM and casts `void*` to `unsigned int`, which is rejected by
   default on ARM64).

   **8.2) `xlnk` (the kernel driver used by PYNQ) does not exist in modern
   `linux-xlnx` kernels.** Confirmed by reading the driver's own source code
   in the BSP of a real ZCU106 running PetaLinux 2025.2 - `xlnk`'s
   `of_match_table` is empty by default and the device is never created.
   Instead of depending on `xlnk`, a canonical driver was written
   (`uiodriver.hpp` / `platform-mpsoc-uio.cpp`) based on the kernel's **UIO**
   subsystem (`uio_pdrv_genirq` + `mmap` of `/dev/uioN`), which is the
   mechanism natively supported by any modern Linux kernel, with no
   dependency on any proprietary Xilinx library. The device name is
   configurable by macro (`LF_FPGA_UIO_NAME`, default `"lf_fpga_regs"`) and
   by constructor argument - during real bring-up on a ZCU106, the name that
   `sdtgen`/PetaLinux actually exposes in `/sys/class/uio/uioN/name` was
   `"ZCU106Wrapper@a0000000"`, not `"lf_fpga_regs"`, so
   `platform-mpsoc-uio.cpp` passes `"ZCU106Wrapper"` as an explicit hint to
   the constructor (the lookup is by substring, so the full address is not
   needed).

   **8.3) `mainCodesign` had the driver to compile *hardcoded* for
   `VerilatedTester` only.** Generalized: the `PlatformWrapper.scala` base
   class gained two fields with safe defaults -
   `platformMainDriverFile` (inferred automatically from the first `.cpp` in
   `platformDriverFiles`) and `platformDriverExtraFlags` (empty by default,
   overridden where needed, as in 8.1). `mainCodesign`
   (`reactor-chisel/src/main/scala/Main.scala`) now reads those two fields
   from the wrapper instance itself, instead of having the file name/flags
   fixed in the code - any new board (real, not `VerilatedTester`) now works
   without editing that file again, just by creating the wrapper and
   registering it in `platformMap`/`fpgaPartMap` (`fpgatidbits/Main.scala`).
   Validated by creating a fictitious board (`TESTBOARD`, same chip, same
   driver) touching a single new file + two registration lines, with no edit
   at all in `mainCodesign`.

   *Implementation pitfall found along the way:* instantiating the wrapper
   **outside** the by-name block passed to `emitVerilog` fails with
   `java.lang.IllegalArgumentException: requirement failed: must be inside
   Builder context`, because `PlatformWrapper` extends Chisel's `Module` and
   elaboration is only allowed inside the Builder context that `emitVerilog`
   itself opens. Solution: capture the instance in an external `var`,
   assigned **inside** the by-name block.

   **Practical result**: `fpgaBoard: ZCU106` generates `lfFPGA.a` with the
   `UioDriver` driver (`platform-mpsoc-uio.o`), compiling cleanly with no
   warnings (unlike `PYNQZCU104CC`, which still uses the legacy 32-bit driver
   with `-fpermissive`) - see Smoke Test 4 in the Dockerfile, which validates
   this on every image build.

9) **Automatic software compilation for any board (not just
   `VerilatedTester`) + correct propagation of `--target-compiler`.**

   Two complementary fixes in Kotlin:

   **9.1) `CodesignGenerator.kt`** originally only called
   `swEmitter.compile()` (and copied the binary into `bin/`) when
   `fpgaBoard.equals("VerilatedTester")` - for any real board, the software
   source code was generated but never compiled, requiring a second manual
   command (`lfc --target-compiler aarch64-linux-gnu-g++ _SwTop.lf`, inside
   `src/software/`). Patch: the condition became unconditional (`if (true)`),
   so automatic compilation now happens for any `fpgaBoard`.

   **9.2) That alone was not enough.** `CodesignSwEmitter.compile()` creates
   a **new** `MainContext`/`TargetConfig` internally, using an empty
   `Properties()` as the command-line arguments - meaning the
   `--target-compiler` passed to the original `lfc` never reaches that second
   internal generation pass. The rebuilt `TargetConfig` only reflects what is
   *written in the generated `.lf` itself* (via `TargetProperty.COMPILER`,
   keyword `compiler` in the `Cpp` target). Without that second fix, the
   automatic compilation (9.1) always falls back to the system's native
   compiler (x86 `g++`), producing linker errors of the kind
   `Relocations in generic ELF (EM: 183)` / `error adding symbols: file in
   wrong format` when trying to link against an ARM64 `lfFPGA.a`. Patch:
   `generateTarget()` (`CodesignSwEmitter.kt`) now writes
   `compiler: "<value>"` into the generated `_SwTop.lf` whenever
   `targetConfig.compiler` is not empty - the right compiler is then
   propagated correctly through the second internal generation.

   **Practical result**: `lfc --target-compiler aarch64-linux-gnu-g++
   file.lf` on its own now generates **and compiles** hardware + software,
   with no additional manual command - for any `fpgaBoard`, not just
   `VerilatedTester`.

10) **Known problem, still under investigation: GCC 11 `internal compiler
    error: Segmentation fault` when compiling `main.cc` (code generated
    automatically by the framework, which uses `std::regex` internally)
    while cross-compiling for AArch64.**
    It occurred intermittently during the development of this Dockerfile, in
    the `_M_word_boundary` function of libstdc++'s `std::regex`
    implementation, during the `cddce` optimization pass. Insufficient memory
    was ruled out as the cause (62 GB available in the environment where it
    happened). Hypotheses not yet confirmed: a bug specific to GCC 11
    (Ubuntu 22.04) with that template pattern under cross-compilation;
    sensitivity to the optimization level (`-O2`, the CMake `Release`
    default, vs. `-O1`/`-O0`). If Smoke Test 4 fails with that specific
    message, try lowering the optimization level
    (`ENV CXXFLAGS="-O1"` before `./gradlew assemble`) or consider
    installing a newer version of `g++-aarch64-linux-gnu` via a PPA, since
    Ubuntu 22.04 only ships GCC 11 in the official repositories.

11) **The `file` package is included explicitly.**
    It does not come by default in this minimal base image, despite looking
    like a basic Unix utility - it is needed both for Smoke Test 4 (checking
    the architecture of the generated binary) and for general manual use (it
    is the standard tool used throughout the physical board bring-up flow to
    confirm `ELF ... ARM aarch64` on the cross-compiled binaries).

12) **SSH enabled, port 22 exposed.**
    `openssh-server` (installed from the beginning of the Dockerfile) is now
    configured and started: host keys generated with `ssh-keygen -A`,
    `PasswordAuthentication yes`, `PermitRootLogin no`, `/run/sshd` created.
    Since `CMD` runs as the `lingua-franca` user (not root), `sshd` is
    brought up by an entrypoint (`/usr/local/bin/entrypoint.sh`) via
    `sudo /usr/sbin/sshd` before handing over the shell/`CMD` as usual.
    Password login uses the container's own user/password:
    `lingua-franca` / `1234`. To get in, map the port when running the
    container (see "HOW TO USE").

13) **Oh My Zsh theme: `jonathan`.**
    Configured via `sed` in `~/.zshrc` right after installing Oh My Zsh,
    replacing the default theme (`robbyrussell`). Purely decorative; it does
    not affect any build/generation flow - documented here only so that
    whoever customizes it later knows where to look (`ZSH_THEME=` in
    `~/.zshrc`, a single `sed -i 's/^ZSH_THEME=.*/ZSH_THEME="jonathan"/'
    ~/.zshrc`).

## Smoke tests embedded in the build

This Dockerfile runs four functional tests **during** the `docker build`
(not afterwards) - if any of them fails, the whole image fails to build,
instead of you only finding out about the problem the first time you use the
container:

| # | What it validates |
|---|---|
| 1 | The `UioDriver` logic in isolation (register round-trip, device lookup by name), without real hardware, using a fake directory tree in place of `/sys/class/uio`. |
| 2 | The end-to-end simulation flow (`VerilatedTester`): generates hardware+software, runs the binary, confirms the expected `CodesignTopReactor` signature in the output. |
| 3 | Generation of `lfFPGA.a` for real hardware (`ZCU106`), confirming `platform-mpsoc-uio.o` is present in the archive, with no compilation warnings. |
| 4 | **Automatic software compilation** for `ZCU106` with a single command (`lfc --target-compiler aarch64-linux-gnu-g++ file.lf`, with no second manual step), confirming `ELF ... ARM aarch64` in the final binary. |

## Automation Makefile (building, packaging and simulation/FPGA detection)

The image already ships with a ready-to-use `Makefile` at
`/home/lingua-franca/Makefile` (the `WORKDIR` of the `lingua-franca` user) —
it is generated **during the `docker build`**, via heredoc
(`RUN cat <<'EOF' > Makefile ... EOF`); it is not copied from an external
file. In other words, every container created from this image already has
`make` available for building/packaging without having to remember the full
`lfc` commands.

### Variables

| Variable | Default | Description |
|---|---|---|
| `PROJECT` | `loopback` | Name of the `.lf` file (without a path extension) to build. Also used to name the folder generated under `codesign-gen/` and the final package. `PROJETO`, the former name of this variable, is still accepted as a deprecated alias. |
| `ARCH` | `arm` | Target architecture of the compiler. Accepts `arm` (uses `aarch64-linux-gnu-g++`) or `x86_64` (uses native `g++`). Any other value makes `make` abort with `$(error ...)`. |

### Automatic detection: simulation vs. FPGA

The Makefile reads the `./$(PROJECT)` file and looks for the string
`fpgaboard` in it (via `grep`, once, at the moment Make is invoked):

- **Contains `fpgaboard`** → `MODE=simulation` → expects `ARCH=x86_64`.
- **Does not contain it** → `MODE=fpga` → expects `ARCH=arm`.

If the `ARCH` passed does not match the mode detected in the file, the
`check-string` target (a prerequisite of `compiler`) aborts with `exit 1`
before calling `lfc` — this prevents building for the wrong architecture by
mistake.

### Targets

| Target | What it does |
|---|---|
| `check-string` | Detects the mode (simulation/FPGA) and validates it against `ARCH`. Runs automatically before `compiler`. |
| `compiler` | Runs `lfc --target-compiler=<compiler> $(PROJECT)` (hardware + software, see item 9 above). |
| `deploy` | Runs `compiler` and, **only if `MODE=fpga`**, packages `ZCU106Wrapper.v` + `lib/` + the binary into `./<project>_<timestamp>_deploy.tar.gz`. In simulation mode it only warns and produces no package (packaging hardware/bitstream from a simulated run makes no sense). |
| `all` | Alias for `deploy` (the default target, run by `make` with no arguments). |
| `clean` | Removes the `codesign-gen/` folder (artifacts generated by `lfc`). |

### Usage examples

```bash
# Inside the container, from /home/lingua-franca

# Simulation (.lf file with fpgaBoard: VerilatedTester/fpgaboard) on x86_64
make deploy PROJECT=my_test.lf ARCH=x86_64
# -> builds, but skips packaging (warns in yellow)

# Real FPGA implementation (e.g. ZCU106), cross-compiling for ARM
make deploy PROJECT=my_test.lf ARCH=arm
# -> builds and produces my_test_<timestamp>_deploy.tar.gz

# Build only, no packaging
make compiler PROJECT=my_test.lf ARCH=arm

# ARCH incompatible with the file's content -> aborts before building
make compiler PROJECT=my_test.lf ARCH=x86_64   # if the file does NOT have fpgaboard
# ERROR: file indicates FPGA, but ARCH=x86_64. Use ARCH=arm

# Clean generated artifacts
make clean
```

**Careful:** the `PROJECT` referenced by the `grep` (`./$(PROJECT)`) has to
be a readable path relative to the directory where `make` runs — normally the
`.lf` file itself. If the `.lf` lives in another folder (for example, inside
the mounted volume — see the next section), run `make` from there or pass
`PROJECT=relative/path/file.lf`.

## HOW TO USE
```bash
docker build -t lf-chisel .
docker run -it --rm -v "$(pwd)/my-lf:/workspace" lf-chisel
```
Inside the container, `lfc <file>.lf` is already on the PATH. The fork's
tests live in `/opt/lingua-franca/test/` (the Codesign target specifically in
`/opt/lingua-franca/test/Codesign/src/` - `HelloCodesign.lf`, `VectorAdd.lf`,
etc.). To use your own `.lf` files, mount them at `/workspace`, which is the
container's `WORKDIR`.

**Careful**: if you pass an explicit command to `docker run` (e.g.
`docker run -it lf-chisel bash`), that overrides the image's
`CMD ["/bin/zsh"]` - to land directly in zsh (with the `jonathan` theme), run
`docker run -it --rm lf-chisel` without specifying any command at the end.

### Generating hardware + software for the ZCU106 with a single command
```bash
cat > my_test.lf << 'EOF'
target Codesign {
  fpgaBoard: ZCU106,
  clock-period: 10 nsec
}
reactor Fpga {
  input in: {= UInt(32.W) =}
  output out: {= UInt(32.W) =}
  reaction(in) -> out {= lf_set(out, lf_get(in)) =}
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
lfc --target-compiler aarch64-linux-gnu-g++ my_test.lf
file codesign-gen/my_test/bin/_SwTop
```
It should report `ELF 64-bit ... ARM aarch64` with no additional command
(see item 9 above).

### Access over SSH
Map the container's port 22 to a local port (e.g. 2222) when bringing it up:
```bash
docker build -t lf-chisel .
docker run -d -p 2222:22 -v "$(pwd)/my-lf:/workspace" --name lf lf-chisel
docker ps                              # confirms the container is still "Up"
ssh -p 2222 lingua-franca@localhost    # password: 1234
```
If you would rather not leave the container in the background, `-it --rm`
also works normally - `sshd` comes up the same way before `CMD`.

### Shared folder host ↔ container (bind mount)

Besides the `/workspace` used in the examples above, you can also mount a
folder dedicated to the deploy artifacts alone (the `.tar.gz` files produced
by `make deploy`), without mixing them with the `.lf` source code:

```bash
docker run -v $(pwd)/deploy_host:/home/lingua-franca/deploy_container -it lf-chisel zsh
```

What that command does:

- **`$(pwd)/deploy_host`** (host): a folder in your local environment,
  created automatically by Docker if it does not exist yet. It lands in the
  directory where you ran the command.
- **`/home/lingua-franca/deploy_container`** (container): the mount point
  inside the container, in the `$HOME` of the `lingua-franca` user — the same
  user that runs `zsh` and has the `Makefile` in the directory right above
  (`/home/lingua-franca`).
- **`-it ... zsh`**: overrides the image's default `CMD` and opens the
  interactive shell directly (equivalent to the default behaviour, but
  explicit).
- Any file written on either side shows up automatically on the other - it is
  a two-way street, not a one-off copy.

**How to use it together with `make deploy`:** since the Makefile's `deploy`
writes the `.tar.gz` into the **current** directory (`./`), for the package to
show up directly in `deploy_host` on your computer, run `make` from inside the
mounted folder:

```bash
# Already inside the container (zsh opened by the docker run above)
cd ~/deploy_container
make deploy PROJECT=/path/to/my_test.lf ARCH=arm
```

The resulting `my_test_<timestamp>_deploy.tar.gz` lands in
`~/deploy_container/` inside the container and, thanks to the bind mount, in
`./deploy_host/` on the host - immediately accessible outside Docker, with no
need for `docker cp`.

**Notes:**

- If you run `make` from `/home/lingua-franca` (outside the mounted folder),
  the `.tar.gz` stays inside the container only and disappears when it is
  removed (unless copied manually with `docker cp`).
- Permissions: the container runs as the `lingua-franca` user (item 4). If
  your user on the host has a different UID, files created inside
  `deploy_host/` from the container may show up with a "strange" numeric
  owner/group on the host (normal behaviour for bind mounts on Linux) -
  reading/writing still works, only the owner display in the host's `ls -l`
  may not match a named user.
- You can combine both volumes in the same `docker run` (source code in
  `/workspace` + artifacts in `deploy_container`):
  ```bash
  docker run -it --rm \
    -v "$(pwd)/my-lf:/workspace" \
    -v "$(pwd)/deploy_host:/home/lingua-franca/deploy_container" \
    lf-chisel zsh
  ```

DISK SPACE: we did not measure the exact usage, but count on **at least
15 GB** for the base image + JDK 17 + the gradle/sbt toolchain, plus
whatever sbt downloads during the first build of a Codesign target (item 5) -
`chisel3`/`chiseltest`/Scala pull in quite a lot transitively. It is not on
the order of an FPGA vendor toolchain (tens of GB), but it is worth reserving
plenty of room, especially if you are going to build several different
Codesign tests (each one creates its own folder under `codesign-gen/`).

## Known open items (outside the scope of this Dockerfile)

These fixes live in other artifacts/projects (Vivado, PetaLinux), not in
`lfc`/this image, but they are real prerequisites for running on physical
hardware:

- **The `ZCU106Wrapper` reset has to be connected to the active-high output
  of `proc_sys_reset` (`peripheral_reset`), not to the active-low one
  (`peripheral_aresetn`)** - a manual wiring mistake in the Vivado Block
  Design, not something `lfc` generates or controls.
- **Bug under investigation in `TriggerGenerator`/logical time
  coordination**: `logicalTagComplete` gets stuck at the sentinel value
  `Tag.FOREVER` (`0x80000000`) once the accumulated logical execution time
  goes beyond ~2.15 seconds (`2^31` ns) - an improper signed comparison
  somewhere in the generated Chisel logic is suspected; no confirmed fix in
  the source code yet.
- **Device tree overlay + bootarg in PetaLinux**
  (`compatible = "generic-uio"` + `uio_pdrv_genirq.of_id=generic-uio"`) -
  needed for the kernel to actually create `/dev/uioN`; documented separately
  in the physical board bring-up backlog.
