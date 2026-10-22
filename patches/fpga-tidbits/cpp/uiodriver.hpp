#ifndef UIODRIVER_H
#define UIODRIVER_H

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "wrapperregdriver.h"

#ifndef LF_FPGA_UIO_NAME
#define LF_FPGA_UIO_NAME "lf_fpga_regs"
#endif

#ifndef LF_FPGA_UIO_MAP_SIZE
#define LF_FPGA_UIO_MAP_SIZE 0x10000
#endif

// Base sysfs directory for UIO devices. Configurable via -D so that tests
// can point the driver at a fake directory tree, without needing root
// access to the real /sys/class/uio.
#ifndef LF_FPGA_UIO_SYSFS_DIR
#define LF_FPGA_UIO_SYSFS_DIR "/sys/class/uio"
#endif

// Base directory for the device nodes (/dev/uioN). Configurable for the
// same reason as above.
#ifndef LF_FPGA_UIO_DEV_DIR
#define LF_FPGA_UIO_DEV_DIR "/dev"
#endif

class UioDriver : public WrapperRegDriver {
public:
  UioDriver(const char *uioNameHint = LF_FPGA_UIO_NAME,
            size_t mapSize = LF_FPGA_UIO_MAP_SIZE)
      : m_mapSize(mapSize), m_reg(nullptr), m_fd(-1) {
    std::string devPath = findUioDevice(uioNameHint);
    if (devPath.empty()) {
      throw "UioDriver: no UIO device found matching name hint";
    }

    m_fd = open(devPath.c_str(), O_RDWR | O_SYNC);
    if (m_fd < 0) {
      throw "UioDriver: failed to open UIO device";
    }

    void *mapped = mmap(nullptr, m_mapSize, PROT_READ | PROT_WRITE,
                         MAP_SHARED, m_fd, 0);
    if (mapped == MAP_FAILED) {
      close(m_fd);
      throw "UioDriver: mmap failed";
    }
    m_reg = reinterpret_cast<volatile AccelReg *>(mapped);
  }

  virtual ~UioDriver() {
    if (m_reg) {
      munmap((void *)m_reg, m_mapSize);
    }
    if (m_fd >= 0) {
      close(m_fd);
    }
  }

  virtual std::string platformID() { return "UioDriver"; }

  virtual void writeReg(unsigned int regInd, AccelReg regValue) {
    m_reg[regInd] = regValue;
  }

  virtual AccelReg readReg(unsigned int regInd) { return m_reg[regInd]; }

  virtual void attach(const char *name) { (void)name; }
  virtual void detach() {}

  // Exposed for testing: the same lookup logic used by the constructor.
  static std::string findUioDevice(const std::string &nameHint) {
    const char *sysDir = LF_FPGA_UIO_SYSFS_DIR;
    DIR *dir = opendir(sysDir);
    if (!dir) return "";

    std::string fallback;
    struct dirent *entry;
    while ((entry = readdir(dir)) != nullptr) {
      std::string uioName = entry->d_name;
      if (uioName.substr(0, 3) != "uio") continue;

      std::string namePath = std::string(sysDir) + "/" + uioName + "/name";
      FILE *f = fopen(namePath.c_str(), "r");
      if (!f) continue;
      char buf[256] = {0};
      if (fgets(buf, sizeof(buf), f)) {
        std::string devName(buf);
        devName.erase(devName.find_last_not_of("\n\r") + 1);
        if (devName.find(nameHint) != std::string::npos) {
          fclose(f);
          closedir(dir);
          return std::string(LF_FPGA_UIO_DEV_DIR) + "/" + uioName;
        }
      }
      fclose(f);
      if (fallback.empty()) fallback = std::string(LF_FPGA_UIO_DEV_DIR) + "/" + uioName;
    }
    closedir(dir);
    return fallback;
  }

private:
  size_t m_mapSize;
  volatile AccelReg *m_reg;
  int m_fd;
};

#endif // UIODRIVER_H
