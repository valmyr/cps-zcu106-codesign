package fpgatidbits.PlatformWrapper

import chisel3._
import chisel3.util._
import fpgatidbits.dma._
import fpgatidbits.regfile._
import fpgatidbits.axi._
import fpgatidbits.ocm._
import chisel3.experimental.noPrefix

// wrapper for AXI platforms

abstract class AXIPlatformWrapper(p: PlatformWrapperParams,
                                  instFxn: PlatformWrapperParams => GenericAccelerator)
  extends PlatformWrapper(p, instFxn) {

  val csrSlave = Wire(new AXILiteSlaveIF(p.memAddrBits, p.csrDataBits))
  val mem = Wire(Vec(p.numMemPorts, new AXIMasterIF(p.memAddrBits, p.memDataBits, p.memIDBits)))

  // add the actual external interfaces with the correct naming
  val extMemIf = noPrefix {VecInit(Seq.tabulate(p.numMemPorts) {idx => IO(new AXIExternalIF(p.memAddrBits, p.memDataBits, p.memIDBits)).suggestName(s"mem${idx}")}) }
  val extCsrIf = noPrefix {IO(Flipped(new AXILiteExternalIF(p.memAddrBits, p.csrDataBits))).suggestName("csr") }

  // Make the connections between the external and internal AXI interface
  for ((extIf, intIf) <- extMemIf zip mem ) {
    extIf.connect(intIf)
  }
  extCsrIf.connect(csrSlave)



    // memory port adapters and connections
  // TODO use accel numMemPorts and plug unused
  for(i <- 0 until accel.numMemPorts) {
    // instantiate AXI request and response adapters for the mem interface
    val mrp = p.toMemReqParams()
    // read requests
    val readReqAdp = Module(new AXIMemReqAdp(mrp)).io
    readReqAdp.genericReqIn <> accel.io.memPort(i).memRdReq
    readReqAdp.axiReqOut <> mem(i).readAddr
    // read responses
    val readRspAdp = Module(new AXIReadRspAdp(mrp)).io
    readRspAdp.axiReadRspIn <> mem(i).readData
    readRspAdp.genericRspOut <> accel.io.memPort(i).memRdRsp
    // write requests
    val writeReqAdp = Module(new AXIMemReqAdp(mrp)).io
    writeReqAdp.genericReqIn <> accel.io.memPort(i).memWrReq
    // write burst adapter
    val writeBurstAdp = Module(new AXIWriteBurstReqAdapter(
      p.memAddrBits, p.memDataBits, p.memIDBits
    )).io
    writeReqAdp.axiReqOut <> writeBurstAdp.in_writeAddr
    writeBurstAdp.in_writeData.bits.data := accel.io.memPort(i).memWrDat.bits
    // TODO fix this: forces all writes bytelanes valid!
    writeBurstAdp.in_writeData.bits.strb := ~0.U((p.memDataBits/8).W)
    // burst adapter will set this appropriately
    writeBurstAdp.in_writeData.bits.last := false.B
    writeBurstAdp.in_writeData.valid := accel.io.memPort(i).memWrDat.valid
    accel.io.memPort(i).memWrDat.ready := writeBurstAdp.in_writeData.ready
    writeBurstAdp.out_writeAddr <> mem(i).writeAddr
    // add a small write data queue to ensure we can provide both req ready and
    // data ready at the same time (otherwise this is up to the AXI slave)
    val q = Module(new FPGAQueue(new AXIWriteData(p.memDataBits), 2))
    q.io.enq <> writeBurstAdp.out_writeData
    q.io.deq <> mem(i).writeData
//    FPGAQueue(writeBurstAdp.out_writeData, 2) <> mem(i).writeData
    // write responses
    val writeRspAdp = Module(new AXIWriteRspAdp(mrp)).io
    writeRspAdp.axiWriteRspIn <> mem(i).writeResp
    writeRspAdp.genericRspOut <> accel.io.memPort(i).memWrRsp
  }

  // the accelerator may be using fewer memory ports than what the platform
  // exposes; plug the unused ones
  for(i <- accel.numMemPorts until p.numMemPorts) {
    println("Plugging unused memory port " + i.toString)
    mem(i).driveDefaults()
  }

  // AXI regfile read/write logic
  // slow and clumsy, but ctrl/status is not supposed to be performance-
  // critical anyway

  csrSlave.writeAddr.ready := false.B
  csrSlave.writeData.ready := false.B
  csrSlave.writeResp.valid := false.B
  csrSlave.writeResp.bits := 0.U
  csrSlave.readAddr.ready := false.B
  csrSlave.readData.valid := false.B
  csrSlave.readData.bits.data := regFile.extIF.readData.bits
  csrSlave.readData.bits.resp := 0.U

  regFile.extIF.cmd.valid := false.B
  regFile.extIF.cmd.bits.driveDefaults()

  val sRead :: sReadRsp :: sWrite :: sWriteD :: sWriteRsp :: Nil = Enum(5)
  val regState = RegInit(sRead)
  val regModeWrite = RegInit(false.B)
  val regRdReq = RegInit(false.B)
  val regRdAddr = RegInit(0.U(p.memAddrBits.W))
  val regWrReq = RegInit(false.B)
  val regWrAddr = RegInit(0.U(p.memAddrBits.W))
  val regWrData = RegInit(0.U(p.csrDataBits.W))
  // AXI typically uses byte addressing, whereas regFile indices are
  // element indices -- so the AXI addr needs to be divided by #bytes
  // in one element to get the regFile ind
  // Note that this permits reading/writing only the entire width of one
  // register
  val addrDiv = (p.csrDataBits/8).U

  when(!regModeWrite) {
    regFile.extIF.cmd.valid := regRdReq
    regFile.extIF.cmd.bits.read := true.B
    regFile.extIF.cmd.bits.regID := regRdAddr / addrDiv
  } .otherwise {
    regFile.extIF.cmd.valid := regWrReq
    regFile.extIF.cmd.bits.write := true.B
    regFile.extIF.cmd.bits.regID := regWrAddr / addrDiv
    regFile.extIF.cmd.bits.writeData := regWrData
  }

  switch(regState) {
    is(sRead) {
      csrSlave.readAddr.ready := true.B

      when(csrSlave.readAddr.valid) {
        regRdReq := true.B
        regRdAddr := csrSlave.readAddr.bits.addr
        regModeWrite := false.B
        regState := sReadRsp
      }.otherwise {
        regState := sWrite
      }
    }

    is(sReadRsp) {
      println("sReadRsp")
      csrSlave.readData.valid := regFile.extIF.readData.valid
      when (csrSlave.readData.ready & regFile.extIF.readData.valid) {
        regState := sWrite
        regRdReq := false.B
      }
    }

    is(sWrite) {
      csrSlave.writeAddr.ready := true.B

      when(csrSlave.writeAddr.valid) {
        regModeWrite := true.B
        regWrReq := false.B // need to wait until data is here
        regWrAddr := csrSlave.writeAddr.bits.addr
        regState := sWriteD
      } .otherwise {
        regState := sRead
      }
    }

    is(sWriteD) {
      csrSlave.writeData.ready := true.B
      when(csrSlave.writeData.valid) {
        regWrData := csrSlave.writeData.bits.data
        regWrReq := true.B // now we can set the request
        regState := sWriteRsp
      }
    }

    is(sWriteRsp) {
      csrSlave.writeResp.valid := true.B
      when(csrSlave.writeResp.ready) {
        regWrReq := false.B
        regState := sRead
      }
    }
  }
}
