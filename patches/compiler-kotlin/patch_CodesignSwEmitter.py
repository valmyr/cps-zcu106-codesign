path = 'core/src/main/kotlin/org/lflang/generator/codesign/CodesignSwEmitter.kt'
with open(path) as f:
    content = f.read()

anchor = '''        if (targetConfig.cmakeBuildType != null) {
            builder.appendLine("  build-type: ${targetConfig.cmakeBuildType.name},")
        }'''

patch = anchor + '''
        if (targetConfig.compiler.isNotEmpty()) {
            builder.appendLine("  compiler: \\"${targetConfig.compiler}\\",")
        }'''

assert content.count(anchor) == 1, 'anchor not found exactly once in CodesignSwEmitter.kt'
content = content.replace(anchor, patch, 1)

with open(path, 'w') as f:
    f.write(content)
print('CodesignSwEmitter.kt patched OK')
