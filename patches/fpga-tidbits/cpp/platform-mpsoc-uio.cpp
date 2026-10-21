#include "platform.h"
#include "uiodriver.hpp"

// Hint "ZCU106Wrapper" bate por substring com o nome real do dispositivo
// exposto em /sys/class/uio/uioN/name pelo sdtgen (ex.: "ZCU106Wrapper@a0000000"),
// confirmado em bring-up real na placa. O macro LF_FPGA_UIO_NAME por si só
// ("lf_fpga_regs") nunca bate com o nome que o device tree gerado usa de
// fato -- por isso o hint precisa ser passado explicitamente aqui.
WrapperRegDriver *initPlatform(bool tracing) {
  (void)tracing;
  return new UioDriver("ZCU106Wrapper");
}

void deinitPlatform(WrapperRegDriver *driver) {
  if (driver) {
    delete driver;
  }
}
