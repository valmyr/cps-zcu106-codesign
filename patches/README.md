# patches/ — the fixes in isolation
Each file here is **exactly** the content that `docker/Dockerfile` injects into the
compiler tree during the build. They are extracted here so they can be read, reviewed
and versioned as code, not as a string inside a `RUN cat >`.

## Where each one lands

Clone root: `/opt/lingua-franca`
Chisel template prefix: `core/src/main/resources/lib/chisel/reactor-chisel/`

| File here | Destination in the compiler |
|---|---|
| `reactor-chisel/Main.scala` | `<prefix>/src/main/scala/Main.scala` |
| `fpga-tidbits/axi/AXIPlatformWrapper.scala` | `<prefix>/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/axi/` |
| `fpga-tidbits/axi/PYNQZCU104CCWrapper.scala` | same as above |
| `fpga-tidbits/axi/ZCU106Wrapper.scala` | same as above (new file) |
| `fpga-tidbits/cpp/uiodriver.hpp` | `<prefix>/fpga-tidbits/src/main/resources/cpp/platform-wrapper-regdriver/` (new) |
| `fpga-tidbits/cpp/platform-mpsoc-uio.cpp` | same as above (new) |
| `fpga-tidbits/patch_PlatformWrapper.py` | edits `<prefix>/fpga-tidbits/.../platform-wrapper/PlatformWrapper.scala` |
| `fpga-tidbits/patch_Main_platformMap.py` | edits `<prefix>/fpga-tidbits/src/main/scala/fpgatidbits/Main.scala` |
| `compiler-kotlin/patch_CodesignGenerator.py` | edits `core/src/main/kotlin/org/lflang/generator/codesign/CodesignGenerator.kt` |
| `compiler-kotlin/patch_CodesignSwEmitter.py` | edits `core/src/main/kotlin/org/lflang/generator/codesign/CodesignSwEmitter.kt` |

The `.py` files edit by **exact anchor** with `assert content.count(anchor) == 1` — if
upstream changes the reference line, the build fails loudly instead of silently
applying the wrong patch.

## Why in the source template and not in the generated project

Every call to `lfc file.lf` re-copies the packaged template into
`src-gen/reactor-chisel`, wiping out any edit made there. A permanent fix only exists
by editing the template and repackaging:

```bash
cd /opt/lingua-franca && ./gradlew installDist
```

## Adding a new board (the generalization criterion)

1. Copy `fpga-tidbits/axi/ZCU106Wrapper.scala` → `NewBoardWrapper.scala`, changing
   `platformName` and `suggestName`.
2. Two lines in `fpgatidbits/Main.scala`: `platformMap` and `fpgaPartMap`.
3. `./gradlew installDist`.

No edit in `mainCodesign` — it reads `platformMainDriverFile` and
`platformDriverExtraFlags` from the wrapper instance itself.
