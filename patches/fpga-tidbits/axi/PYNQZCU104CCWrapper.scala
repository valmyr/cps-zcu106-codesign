package fpgatidbits.PlatformWrapper
import chisel3._
import fpgatidbits.TidbitsMakeUtils._
object PYNQZCU104CCParams extends PlatformWrapperParams {
  val platformName = "PYNQZCU104CC"
  val memAddrBits = 32
  val memDataBits = 64
  val memIDBits = 6
  val memMetaBits = 1
  val numMemPorts = 1
  val sameIDInOrder = true
  val typicalMemLatencyCycles = 32
  val burstBeats = 8
  val coherentMem = true
}
class PYNQZCU104CCWrapper(instFxn: PlatformWrapperParams => GenericAccelerator, targetDir: String)
  extends AXIPlatformWrapper(PYNQZCU104Params, instFxn) {
  val platformDriverFiles = baseDriverFiles ++ Array[String](
    "platform-zedboard-linux.cpp", "linuxphysregdriver.hpp", "axiregdriver.hpp"
  )
  override def platformDriverExtraFlags: Seq[String] = Seq("-fpermissive")
  suggestName("PYNQZCU104CCWrapper")
  mem(0).readAddr.bits.cache := "b1100".U
  mem(0).writeAddr.bits.cache := "b1100".U
  mem(0).readAddr.bits.prot := "b10".U
  mem(0).writeAddr.bits.prot := "b10".U
  generateRegDriver(targetDir)
  resourceCopyBulk("cpp/platform-wrapper-regdriver/", targetDir, platformDriverFiles)
  println(s"=======> Driver files copied to ${targetDir}")
}
