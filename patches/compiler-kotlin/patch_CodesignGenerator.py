path = 'core/src/main/kotlin/org/lflang/generator/codesign/CodesignGenerator.kt'
with open(path) as f:
    content = f.read()

anchor = 'if (targetConfig.fpgaBoard.equals("VerilatedTester")) {'
patch = 'if (true) { // PATCHED: always compile software, regardless of fpgaBoard (see backlog)'

assert content.count(anchor) == 1, 'anchor not found exactly once in CodesignGenerator.kt'
content = content.replace(anchor, patch, 1)

with open(path, 'w') as f:
    f.write(content)
print('CodesignGenerator.kt patched OK')
