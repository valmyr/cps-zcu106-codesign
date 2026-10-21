package reactor

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.util._

import scala.sys.process.Process
import java.nio.file.{Files, Paths}
import fpgatidbits.PlatformWrapper.VerilatedTesterWrapper
import fpgatidbits.TidbitsMakeUtils._
import fpgatidbits.PlatformWrapper._
import fpgatidbits.TidbitsMakeUtils

object Settings {
  def writeVerilogToFile(verilog: String, path: String) = {
    import java.io._
    val fname = path
    val f = new File(fname)
    if (!f.exists()) {
      f.getParentFile.mkdirs
      f.createNewFile()
    }
    val writer = new PrintWriter(f)
    writer.write(verilog)
    writer.close()
  }
}
 object ReactorChisel {
   def mainStandalone(mainReactorFunc: () => Reactor, targetDir: String, name: String, globalCfg: GlobalReactorConfig): Unit = {
     println("------------------------------------------------------------------------------------------------------")
     println("Running Chisel compiler to generate verilator project")
     println("------------------------------------------------------------------------------------------------------")

    val chiselArgs = Array("--target-dir", s"$targetDir/tmp")
    val verilog = (new chisel3.stage.ChiselStage).emitVerilog(new StandaloneTopReactor((mainReactorFunc))(globalCfg), chiselArgs)

     val saveLocation = targetDir + "/ReactorChisel.v"
     Settings.writeVerilogToFile(verilog, saveLocation)
     println(s"Wrote the generated verilog to `$saveLocation`")
     // Copy main cpp file for emulation
     resourceCopyBulk("simulator/standalone", targetDir, Seq("main.cpp", "Makefile"))
     println("------------------------------------------------------------------------------------------------------")
     println("Building verilator project")
     println("------------------------------------------------------------------------------------------------------")
     val result = Process("make", new java.io.File(targetDir)).!
   }

   def mainCodesign(platformInstFxn: PlatformInstFxn, mainReactorFunc: () => Reactor, mainReactorSwIOFunc: () => SwIO, targetDir: String, globalConfig: GlobalReactorConfig) = {
     println("------------------------------------------------------------------------------------------------------")
     println("Running Chisel compiler to generate verilator project")
     println("------------------------------------------------------------------------------------------------------")
     val chiselArgs = Array("--target-dir", s"$targetDir")
     var platformWrapperInst: PlatformWrapper = null
     (new chisel3.stage.ChiselStage).emitVerilog({
       platformWrapperInst = platformInstFxn(p => new CodesignTopReactor(p, mainReactorFunc, mainReactorSwIOFunc)(globalConfig), targetDir)
       platformWrapperInst
     }, chiselArgs)
     if (platformInstFxn.equals(TidbitsMakeUtils.platformMap("VerilatedTester"))) {
       resourceCopyBulk("simulator/codesign", targetDir, Seq("main.cpp", "Makefile"))
       println("------------------------------------------------------------------------------------------------------")
       println("Building verilator project")
       println("------------------------------------------------------------------------------------------------------")
       val result = Process("make lib", new java.io.File(targetDir)).!
       println(s"Library produced to $targetDir/lfFPGA.a.")
      } else {
       // Real hardware platform: cross-compile the driver into lfFPGA.a.
       // Driver file and flags are read from the wrapper instance itself
       // (platformMainDriverFile / platformDriverExtraFlags), not hardcoded here.
       println("------------------------------------------------------------------------------------------------------")
       println("Cross-compiling hardware driver for real FPGA platform")
       println("------------------------------------------------------------------------------------------------------")
       val cxx = sys.env.getOrElse("LF_FPGA_CXX", "aarch64-linux-gnu-g++")
       val ar = sys.env.getOrElse("LF_FPGA_AR", "aarch64-linux-gnu-ar")
       val targetDirFile = new java.io.File(targetDir)

       val mainDriverFile = platformWrapperInst.platformMainDriverFile
       val extraFlags = platformWrapperInst.platformDriverExtraFlags
       if (mainDriverFile.isEmpty) {
         throw new RuntimeException("No platformMainDriverFile (.cpp) found in platformDriverFiles for this platform")
       }
       val objFile = mainDriverFile.stripSuffix(".cpp") + ".o"
       println(s"Compiling driver: $mainDriverFile (flags: ${extraFlags.mkString(" ")})")
       val compileResult = Process(
         Seq(cxx) ++ extraFlags ++ Seq("-c", mainDriverFile, "-o", objFile),
         targetDirFile
       ).!
       if (compileResult != 0) {
         throw new RuntimeException(s"Failed to cross-compile $mainDriverFile (exit code $compileResult)")
       }
       val arResult = Process(
         Seq(ar, "rcs", "lfFPGA.a", objFile),
         targetDirFile
       ).!
       if (arResult != 0) {
         throw new RuntimeException(s"Failed to archive lfFPGA.a (exit code $arResult)")
       }
       println("------------------------------------------------------------------------------------------------------")
       println(s"Library produced to $targetDir/lfFPGA.a.")
       println("------------------------------------------------------------------------------------------------------")
     }



   }
 }
