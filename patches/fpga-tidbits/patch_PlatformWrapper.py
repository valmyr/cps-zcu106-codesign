import re
path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala'
with open(path) as f:
    content = f.read()
anchor = '  def platformDriverFiles: Seq[String]  // additional files'
patch = anchor + '''

  // Nome do .cpp que contém initPlatform/deinitPlatform, dentro de
  // platformDriverFiles. Por padrão, deduzido automaticamente: o primeiro
  // .cpp da lista.
  def platformMainDriverFile: String = platformDriverFiles.find(_.endsWith(".cpp")).getOrElse("")

  // Flags extras de compilação para platformMainDriverFile (ex: "-fpermissive"
  // para drivers legados de 32 bits). Vazio por padrão.
  def platformDriverExtraFlags: Seq[String] = Seq()'''
assert content.count(anchor) == 1, 'anchor not found exactly once'
content = content.replace(anchor, patch, 1)
with open(path, 'w') as f:
    f.write(content)
print('PlatformWrapper.scala patched OK')
