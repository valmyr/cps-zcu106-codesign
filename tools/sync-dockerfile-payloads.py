#!/usr/bin/env python3
"""Sync the heredoc payloads embedded in docker/lingua-franca/Dockerfile with
the standalone files under patches/ and tools/, so the two never drift."""
import os
import re
import sys

REPO = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCKERFILE = os.path.join(REPO, "docker/lingua-franca/Dockerfile")

# basename of the heredoc target -> source file in the repo
SOURCES = {
    "AXIPlatformWrapper.scala": "patches/fpga-tidbits/axi/AXIPlatformWrapper.scala",
    "Main.scala": "patches/reactor-chisel/Main.scala",
    "PYNQZCU104CCWrapper.scala": "patches/fpga-tidbits/axi/PYNQZCU104CCWrapper.scala",
    "ZCU106Wrapper.scala": "patches/fpga-tidbits/axi/ZCU106Wrapper.scala",
    "uiodriver.hpp": "patches/fpga-tidbits/cpp/uiodriver.hpp",
    "platform-mpsoc-uio.cpp": "patches/fpga-tidbits/cpp/platform-mpsoc-uio.cpp",
    "Makefile": "tools/Makefile",
}


def read(path):
    with open(os.path.join(REPO, path), encoding="utf-8") as fh:
        return fh.read().rstrip("\n")


def main():
    with open(DOCKERFILE, encoding="utf-8") as fh:
        text = fh.read()

    # RUN cat > <path> <<'EOF' ... EOF   and   RUN cat <<'EOF' > <path> ... EOF
    pattern = re.compile(
        r"(RUN cat (?:> (?P<p1>\S+) <<'EOF'|<<'EOF' > (?P<p2>\S+))\n)"
        r"(?P<body>.*?)"
        r"(?P<tail>\nEOF\n)",
        re.S,
    )
    changed = []

    def repl(m):
        target = m.group("p1") or m.group("p2")
        base = os.path.basename(target)
        src = SOURCES.get(base)
        if not src:
            return m.group(0)
        new_body = read(src)
        if new_body != m.group("body"):
            changed.append((base, src))
        return m.group(1) + new_body + m.group("tail")

    new_text = pattern.sub(repl, text)

    if new_text != text:
        with open(DOCKERFILE, "w", encoding="utf-8") as fh:
            fh.write(new_text)
    for base, src in changed:
        print("synced %s from %s" % (base, src))
    if not changed:
        print("Dockerfile heredocs already in sync")


if __name__ == "__main__":
    main()
