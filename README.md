# Lingua Franca on FPGA
> Hardware and software described at the same level of abstraction:
> reactors with deterministic concurrency, on both sides of the PL/PS
> boundary of a Zynq UltraScale+.

Native support for the **Xilinx ZCU106** board in the Lingua Franca compiler
(`erlingrj/lingua-franca` fork, `chisel3` branch, `Codesign` target).
A single `.lf` file describes *hardware reactors*, synthesized through
`reactor-chisel` into the programmable logic, and software reactors,
compiled to C++ for the processor — one command generates, cross-compiles
and packages both.

Based on *Codesign of Reactor-Oriented Hardware and Software for
Cyber-Physical Systems* (Jellum, Schoeberl, Lee and Orlandic, ACM TRETS
17(4), 2024). This repository contributes the port to the ZCU106 and the
automation of the build flow.
---

## 1. What was done

Previously, generating hardware + software for a real board required
reapplying manual Scala patches on every new project, plus two separate
compilation commands. Now:

```bash
lfc --target-compiler aarch64-linux-gnu-g++ my_test.lf
```

generates the RTL (`ZCU106Wrapper.v`), compiles the driver (`lfFPGA.a`) and
cross-compiles the software (`ELF … ARM aarch64`) — **in a single command,
with no manual editing at all.**

---

## 2. Repository layout

```
Cyber_PhysicalSystems/
├── README.md                     <- what was done
├── CHANGELOG.md                  <- chronological history of the fixes
├── LICENSE
│
├── docker/
│   ├── lingua-franca/
│   │   └── Dockerfile            <- lfc compiler with the fixes + smoke tests
│   └── petalinux/
│       └── Dockerfile            <- PetaLinux 2025.2 build environment
│
├── patches/                      <- THE FIXES, isolated from the Dockerfile
│   ├── reactor-chisel/
│   │   └── Main.scala                      (generic mainCodesign — the centerpiece)
│   ├── fpga-tidbits/
│   │   ├── axi/ZCU106Wrapper.scala         (new board, its own identity)
│   │   ├── axi/PYNQZCU104CCWrapper.scala   (driver copy + -fpermissive)
│   │   ├── axi/AXIPlatformWrapper.scala    (`csr` name collision)
│   │   ├── cpp/uiodriver.hpp               (64-bit UIO driver, testable)
│   │   ├── cpp/platform-mpsoc-uio.cpp      (initPlatform/deinitPlatform)
│   │   ├── patch_PlatformWrapper.py        (generic driver fields)
│   │   └── patch_Main_platformMap.py       (ZCU106 registration)
│   └── compiler-kotlin/
│       ├── patch_CodesignGenerator.py      (compiles software for any board)
│       └── patch_CodesignSwEmitter.py      (propagates --target-compiler)
│
├── tools/
│   ├── Makefile                  <- compile/package, with a simulation-or-FPGA guard
│   ├── sync-dockerfile-payloads.py  <- keeps the Dockerfile heredocs == patches/
│   ├── check-translations.py        <- flags pt-BR docs behind their English source
│   └── apply-translation.sh         <- lands this delivery in an existing clone
│
├── examples/
│   ├── loopback_sim.lf                                       (simulation)
│   ├── smoke4.lf                                             (ZCU106, smoke test)
│   ├── ExplorationMultipleReactorsCodesignAccelsOpBasics.lf  (ZCU106, 3 accelerators)
│   ├── ExplorationMultipleReactoresCodesign.lf
│   └── led.lf
│
├── docs/
│   ├── 01-dockerfile-lfc.md          (the 13 "read before using" items)
│   ├── 02-petalinux-vivado.md        (Vivado → XSA → SDT → PetaLinux → boot → deploy)
│   ├── 03-backlog-generalization.md  (how we got there, phase by phase)
│   ├── 04-translation-strategy.md    (pt-BR → English: scope, commits, guards)
│   ├── 05-bilingual-maintenance.md   (how both languages are kept in step)
│   └── pt-BR/                        <- Portuguese counterparts
│
├── references/                   <- papers, reports and supporting material
└── images/                       <- screenshots used by the tutorials
```

The files under `patches/` are **exactly** the content that
`docker/lingua-franca/Dockerfile` injects via heredoc.

---

## 3. The fixes, in dependency order

| # | Problem | Fix | File |
|---|---|---|---|
| 1 | Submodules over SSH, no key inside the container | `.gitmodules` → HTTPS + global `insteadOf` (covers the nested one) | `docker/lingua-franca/Dockerfile` |
| 2 | `chisel3`/`chiseltest` out of sync across 4 files | `sed` with a patch-version regex → 3.6.x / 0.6.x pair | `docker/lingua-franca/Dockerfile` |
| 3 | Deprecated `.asUInt()` | `sed` → `.asUInt` | `docker/lingua-franca/Dockerfile` |
| 4 | `csr` name collision during elaboration | internal `Wire` renamed to `csrSlave` | `patches/fpga-tidbits/axi/AXIPlatformWrapper.scala` |
| 5 | Wrapper never copied the driver files | `generateRegDriver` + `resourceCopyBulk` | `patches/fpga-tidbits/axi/PYNQZCU104CCWrapper.scala` |
| 6 | `xlnk` does not exist in modern `linux-xlnx` kernels | canonical driver via **UIO** (`mmap` of `/dev/uioN`) | `patches/fpga-tidbits/cpp/uiodriver.hpp` |
| 7 | Driver hardcoded for `VerilatedTester` only | `platformMainDriverFile` / `platformDriverExtraFlags` fields read from the wrapper instance | `patch_PlatformWrapper.py` + `reactor-chisel/Main.scala` |
| 8 | No identity for the ZCU106 | dedicated wrapper + registration in `platformMap`/`fpgaPartMap` | `ZCU106Wrapper.scala` + `patch_Main_platformMap.py` |
| 9 | Software was only compiled for simulation | condition removed | `patch_CodesignGenerator.py` |
| 10 | `--target-compiler` lost in the 2nd internal generation | writes `compiler:` into the generated `_SwTop.lf` | `patch_CodesignSwEmitter.py` |

**Generalization to new Xilinx boards (#7):** adding a new board costs
1 wrapper file + 2 registration lines. Validated with the fictitious
`TESTBOARD`, without touching `mainCodesign`.

---

## 4. Flow

```
┌─ DOCKER · lf-chisel ───────────────────────────────────────────────────┐
│                                                                        │
│   smoke_test.lf                                                        │
│        │  make deploy PROJECT=smoke_test.lf ARCH=arm                   │
│        ▼                                                               │
│        ├──► hardware (Chisel) ─► _FpgaTop ─┬─► ZCU106Wrapper.v         │
│        │                                   │                           │
│        │                                   └─► lfFPGA.a                │
│        │                                       (UioDriver, ARM64)      │
│        │                                            │                  │
│        └──► software (C++) ─► bin/ ◄───── links ────┘                  │
│                               (ELF ARM aarch64)                        │
│                                      │                                 │
│                                      ▼                                 │
│                    smoke_test<timestamp>_deploy.tar.gz                 │
│                      ├── ZCU106Wrapper.v                               │
│                      ├── Q_srl.v                                       │
│                      ├── lib/libreactor-cpp-default.so*                │
│                      ├── bin/smoke_test                                │
│                      └── smoke_testSNAPSHOT_<timestamp>.lf             │
└────────────────────────────────────│───────────────────────────────────┘
                                     │  bind mount / docker cp
                                     ▼
                         ┌────────────────────────────┐
                         │      .tar.gz on the host   │
                         └───┬────────────────────┬───┘
         ZCU106Wrapper.v     │                    │   bin/ + lib/  (scp)
         Q_srl.v             │                    │   
                             ▼                    │
    ┌─ HOST · Vivado + Vitis (licensed) ─────┐    │
    │   block design + synthesis             │    │
    │        ├──► bitstream ───────────────┐ │    │
    │        │                             │ │    │
    │        └──► XSA ──xsct/sdtgen──► SDT │ │    │
    └──────────────────────────│───────────│─┘    │
                               │ SDT       │      │
                               ▼           │      │
         ┌─ DOCKER · petalinux ──────────┐ │      │
         │  petalinux-config             │ │      │
         │   + uio_pdrv_genirq           │ │      │
         │     .of_id=generic-uio        │ │      │
         │   + compatible = "generic-uio"│ │      │
         │        │                      │ │      │
         │  petalinux-build              │ │      │
         │        │                      │ │      │
         │        ▼                      │ │      │
         │  images/linux/                │ │      │
         │   ├── image.ub                │ │      │
         │   └── rootfs.cpio.gz.u-boot   │ │      │
         └────────│──────────────────────┘ │      │
                  │  USB stick →           │      │
                  │  fatload + bootm       │      │
                  ▼                        ▼      ▼
         ┌───────────────────────────────────────────────┐
         │  BOARD   ZCU106 — PL + PetaLinux              │
         │   xilinx-zcu106-2025.2:~$                     │
         │   export LD_LIBRARY_PATH=./lib                │
         │   ./bin/smoke_test  →  /dev/uioN → mmap       │
         └───────────────────────────────────────────────┘
```

Simulate first, without the `fpgaBoard` directive; add it only once the
logic has been validated (see 5.1). That isolates logic bugs from platform
bugs.

---

## 5. How to run

```bash
cd docker/lingua-franca && docker build -t lf-chisel .
docker run -it --rm -v "$(pwd)/../../examples:/workspace" lf-chisel
```

Inside the container:

```bash
cd /workspace
lfc --target-compiler aarch64-linux-gnu-g++ smoke4.lf
file codesign-gen/smoke4/bin/_SwTop        # ELF 64-bit … ARM aarch64
```

Requirements: ≥15 GB of disk and ≥8 GB of RAM for the `docker build`.

The container runs as `lingua-franca` (password `1234`), with `WORKDIR` at
`/home/lingua-franca`, where the `Makefile` and a sample `.lf` are already
present. The first Codesign build in each container downloads the `sbt`
dependencies and takes a few minutes. For details — bind mounts for the
deploy artifacts, SSH access, where the fork's tests live — see
[`docs/01-dockerfile-lfc.md`](docs/01-dockerfile-lfc.md).

### 5.1 Simulation vs. FPGA

You can also build through the `Makefile` (`tools/Makefile`, already present
as `~/Makefile` in the image). The mode is determined by **a single
directive in the `.lf`** — and the `ARCH` passed to `make` has to match it.

| Mode | In `target Codesign` | `ARCH` | Compiler |
|---|---|---|---|
| **FPGA** (real hardware) | `fpgaBoard: ZCU106` **present** | `arm` | `aarch64-linux-gnu-g++` |
| **Simulation** (Verilator) | `fpgaBoard` **absent** | `x86_64` | native `g++` |

> **Careful:** to simulate, the `fpgaBoard` directive has to be **removed** —
> changing its value is not enough. With it present, `lfc` generates
> `lfFPGA.a` cross-compiled for ARM64 and tries to link the software against
> it; in an x86 binary that fails at link time (`Relocations in generic ELF
> (EM: 183)` / `error adding symbols: file in wrong format`).

```bash
# FPGA — the .lf must have `fpgaBoard: ZCU106`
make deploy PROJECT=smoke4.lf ARCH=arm
# builds hardware + software and packages smoke4_<timestamp>_deploy.tar.gz

# Simulation — the .lf must NOT have the `fpgaBoard` line
make compiler PROJECT=loopback_sim.lf ARCH=x86_64
# builds and runs locally; no packaging (there is no bitstream to deliver)
```

---

## 6. Validation tests

| # | Validates | Where it runs | Status |
|---|---|---|---|
| 1 | `UioDriver` logic against a fake `/sys` (register round-trip, lookup by name) | `docker build` | Pass |
| 2 | End-to-end simulation, `aeef7c29` signature of `CodesignTopReactor` | `docker build` | Pass |
| 3 | `lfFPGA.a` for `ZCU106` containing `platform-mpsoc-uio.o`, no warnings | `docker build` | Pass |
| 4 | Automatic software compilation: `ELF … ARM aarch64` with a single command | `docker build` | Pass |
| 5 | Execution on the physical board (bitstream + device tree) | outside Docker | Pass |

A failure in any of tests 1 through 4 prevents the image from being built,
instead of the problem only showing up the first time someone uses the
container.

Test 5 does not run during `docker build`: it requires creating the Vivado
project, generating the XSA and building PetaLinux. The full procedure is in
[`docs/02-petalinux-vivado.md`](docs/02-petalinux-vivado.md).

---

## 7. Open items

Inside the compiler and the image, nothing blocking. Outside them:

- `ZCU106Wrapper` reset: wire it to `peripheral_reset` (active-high), not to
  `peripheral_aresetn` — a manual connection in the Block Design.
- `clock-period` **must** be declared in the `.lf` before any synthesis: the
  default (1 nsec ≙ 1 GHz) desynchronizes tag coordination from the physical
  clock.
- `generic-uio` device tree overlay + `uio_pdrv_genirq.of_id=generic-uio`
  bootarg in PetaLinux.
- Bug under investigation: `logicalTagComplete` gets stuck at `Tag.FOREVER`
  beyond ~2.15 s of logical time (a signed comparison is suspected).
- GCC 11 / AArch64: intermittent *internal compiler error* in `std::regex`
  when compiling `main.cc`.
