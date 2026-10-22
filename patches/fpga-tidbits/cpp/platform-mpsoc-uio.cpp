#include "platform.h"
#include "uiodriver.hpp"

// The "ZCU106Wrapper" hint matches, as a substring, the actual device name
// that sdtgen exposes in /sys/class/uio/uioN/name (e.g. "ZCU106Wrapper@a0000000"),
// as confirmed during real bring-up on the board. The LF_FPGA_UIO_NAME macro
// on its own ("lf_fpga_regs") never matches the name the generated device
// tree actually uses -- hence the hint must be passed explicitly here.
WrapperRegDriver *initPlatform(bool tracing) {
  (void)tracing;
  return new UioDriver("ZCU106Wrapper");
}

void deinitPlatform(WrapperRegDriver *driver) {
  if (driver) {
    delete driver;
  }
}
