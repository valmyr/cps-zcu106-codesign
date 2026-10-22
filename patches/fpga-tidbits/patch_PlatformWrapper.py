import re
path = 'core/src/main/resources/lib/chisel/reactor-chisel/fpga-tidbits/src/main/scala/fpgatidbits/platform-wrapper/PlatformWrapper.scala'
with open(path) as f:
    content = f.read()
anchor = '  def platformDriverFiles: Seq[String]  // additional files'
patch = anchor + '''

  // Name of the .cpp file inside platformDriverFiles that contains
  // initPlatform/deinitPlatform. By default it is inferred automatically:
  // the first .cpp in the list.
  def platformMainDriverFile: String = platformDriverFiles.find(_.endsWith(".cpp")).getOrElse("")

  // Extra compilation flags for platformMainDriverFile (e.g. "-fpermissive"
  // for legacy 32-bit drivers). Empty by default.
  def platformDriverExtraFlags: Seq[String] = Seq()'''
assert content.count(anchor) == 1, 'anchor not found exactly once'
content = content.replace(anchor, patch, 1)
with open(path, 'w') as f:
    f.write(content)
print('PlatformWrapper.scala patched OK')
