path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/Main.scala'
with open(path) as f:
    content = f.read()

anchor1 = '    "PYNQZCU104CC" -> {(f, targetDir) => new PYNQZCU104CCWrapper(f, targetDir)},'
patch1 = anchor1 + '\n    "ZCU106" -> {(f, targetDir) => new ZCU106Wrapper(f, targetDir)},'
assert content.count(anchor1) == 1, 'platformMap anchor not found exactly once'
content = content.replace(anchor1, patch1, 1)

anchor2 = '    "PYNQZCU104CC" -> "xczu7ev-ffvc1156-2-e",'
patch2 = anchor2 + '\n    "ZCU106" -> "xczu7ev-ffvc1156-2-e",'
assert content.count(anchor2) == 1, 'fpgaPartMap anchor not found exactly once'
content = content.replace(anchor2, patch2, 1)

with open(path, 'w') as f:
    f.write(content)
print('fpgatidbits/Main.scala patched OK')
