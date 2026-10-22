
# Cyber-Physical Systems — PetaLinux environment (Docker + Tutorial)

> 🇬🇧 **English** (canonical) · [🇧🇷 Português](pt-BR/02-petalinux-vivado.md)

This document brings together two parts:

1. **Dockerfile** — a containerized build environment for AMD/Xilinx PetaLinux 2025.2.
2. **Tutorial** — a complete, step-by-step walkthrough for generating a PetaLinux image from a hardware project built in Vivado, including booting the board and deploying a Cyber-Physical Systems application (a Lingua Franca reactor) onto the FPGA.

---

## Part 1 — Dockerfile: build environment for PetaLinux 2025.2

### Read before using

1. **Base = Ubuntu 22.04, not Ubuntu 26.04.**
   The `petalinux-v2025.2-11160223-installer.run` installer is validated by AMD only up to Ubuntu 22.04 (see UG1144 / the 2025.2 release notes). Ubuntu 26.04 "Resolute Raccoon" (04/2026) replaces `coreutils` and `sudo` with Rust rewrites (`uutils`/`sudo-rs`) — a low-level change big enough to risk subtly breaking the Yocto/BitBake scripts underneath PetaLinux. Nobody has documented that combination working yet, so this image uses 22.04, which is the officially supported combination and has been tested by third parties with this same installer version. If you want to risk 26.04 anyway, change the `FROM` in the Dockerfile — the rest of the file stays the same.

2. **The `ubuntu-26.04-desktop-amd64.iso` image is not part of this flow at any point.**
   A Docker image does not install from a desktop ISO: it starts straight from Docker Hub's minimal `ubuntu:22.04` rootfs. A desktop ISO would only make sense to install Ubuntu on a VM or a physical machine — there is no equivalent step in the Dockerfile.

3. **Download the installer manually before the build.**
   Log in to the AMD/Xilinx site, download `petalinux-v2025.2-11160223-installer.run` and place it in the same folder as this Dockerfile.

### How to use

```bash
docker build -t petalinux:2025.2 .
docker run -it --privileged --rm -v "$(pwd)/projects:/home/petalinux/projects" petalinux:2025.2
```

Inside the container, `petalinux-create`, `petalinux-config` and `petalinux-build` work right away: the entrypoint sources `settings.sh` automatically.

> **Disk space:** reserve at least 100–150 GB free for the image + build projects (a requirement of PetaLinux itself, not just the installer).

---

## Part 2 — Tutorial: creating a PetaLinux project from Vivado/Vitis

This tutorial shows the complete flow for generating an embedded Linux image (PetaLinux) from a hardware project created in Vivado: exporting the XSA, generating the Software Device Tree, creating the PetaLinux project, configuring it, building, generating the boot image and the final deployment onto the FPGA.

### Prerequisites

- **Vivado / Vitis** 2025.2 installed and licensed.
- **PetaLinux Tools** 2025.2 installed (`/petalinux` in this tutorial).
- The BSP of the target board (in this example, **ZCU106**).
- A Linux environment with enough disk space — the Yocto build can exceed 50 GB — and, preferably, several CPUs available to parallelize the build.
- **Total disk space requirement: 200+ GB.**

### Contents

1. [Preparing the hardware in Vivado](#1-preparing-the-hardware-in-vivado)
2. [Exporting the hardware (XSA)](#2-exporting-the-hardware-xsa)
3. [Generating the Software Device Tree (SDT)](#3-generating-the-software-device-tree-sdt)
4. [Creating and configuring the PetaLinux project](#4-creating-and-configuring-the-petalinux-project)
5. [Incorporating the hardware features into PetaLinux](#5-incorporating-the-hardware-features-into-petalinux)
6. [Configuring the RootFS](#6-configuring-the-rootfs)
7. [Building PetaLinux](#7-building-petalinux)
8. [Booting via JTAG / USB stick](#8-booting-via-jtag--usb-stick)
9. [Deploying onto the FPGA](#9-deploying-onto-the-fpga)
10. [Useful links](#10-useful-links)

---

## 1. Preparing the hardware in Vivado

This section covers what happens between the `.tar.gz` produced by the
container and the XSA export: creating the project, bringing
`ZCU106Wrapper.v` into the Block Design, assigning the accelerator's address
and generating the bitstream.

> **Input:** `ZCU106Wrapper.v` and `Q_srl.v`, extracted from
> `deploy_all_files/` in the `.tar.gz` produced by
> `make deploy ... ARCH=arm`.
> **Outputs:** the bitstream (programmed into the PL) and the `.xsa`
> (consumed by PetaLinux).

### 1.1 Load the Vitis environment

```bash
source <Vitis installation directory>/settings.sh
```

Example:

```bash
source ~/Music/2025.2/Vitis/settings.sh
```

<p align="center">
<img title="Loading the Vitis environment via source" alt="Terminal running source on the Vitis settings.sh" src="../images/passo1.png" width="100%" height="100%">
</p>

### 1.2 Create the project

**File → Project → New**, then step through the wizard.

<p align="center">
<img title="New project wizard" alt="Opening screen of Vivado's New Project wizard" src="../images/passo-01.png" width="100%" height="100%">
</p>

<p align="center">
<img title="New project wizard" alt="Second screen of the New Project wizard" src="../images/passo-02.png" width="100%" height="100%">
</p>

Give it a name and choose the project directory (in this tutorial,
`project_14` under `/home/xmen`):

<p align="center">
<img title="Project name and location" alt="Project name and directory fields filled in" src="../images/passo-03.png" width="100%" height="100%">
</p>

Select **RTL Project** and leave *Do not specify sources at this time*
unchecked — the `.v` files are added in the next step:

<p align="center">
<img title="RTL project type" alt="RTL Project selected in the wizard" src="../images/passo-04.png" width="100%" height="100%">
</p>

### 1.3 Bring in the RTL generated by the compiler

Copy `<project>_<timestamp>_deploy.tar.gz` from the container to the host
where Vivado is installed and extract it:

<p align="center">
<img title="Copying and extracting the deploy package" alt="File manager with the .tar.gz extracted into deploy_all_files" src="../images/passo-05.png" width="100%" height="100%">
</p>

In the wizard's **Add Sources** step, choose *Add or create design sources*:

<p align="center">
<img title="Adding design files" alt="Add Sources screen of the New Project wizard" src="../images/passo-07.png" width="100%" height="100%">
</p>

Navigate to the extracted folder and select **both** files:
`ZCU106Wrapper.v` and `Q_srl.v`.

> **`Q_srl.v` is not optional.** It is the only Verilog file in
> `fpga-tidbits` that is not generated by Chisel, and it is instantiated by
> `SRLQueue`/`FPGAQueue` inside the wrapper. Without it, `ZCU106Wrapper` is
> incomplete and **appears greyed out** (incompatible) when you try to add it
> to the Block Design — see 1.6.

<p align="center">
<img title="Selecting the Verilog files" alt="File browser with ZCU106Wrapper.v and Q_srl.v selected" src="../images/passo-08.png" width="100%" height="100%">
</p>

In the part selection, go to the **Boards** tab and search for `zcu106`:

<p align="center">
<img title="Part selection" alt="Part selection screen of the wizard" src="../images/passo-09.png" width="100%" height="100%">
</p>

<p align="center">
<img title="ZCU106 board selected" alt="Boards tab with the Zynq UltraScale+ ZCU106 Evaluation Platform selected" src="../images/passo-10.png" width="100%" height="100%">
</p>

That sets the part to `xczu7ev-ffvc1156-2-e` — the same part number
registered in the compiler's `fpgaPartMap` for `ZCU106`. Confirm the summary
and finish:

<p align="center">
<img title="New project summary" alt="Final wizard screen with the project summary" src="../images/passo-11.png" width="100%" height="100%">
</p>

With the project created, the Sources tab shows the accelerator's full
hierarchy — `CodesignTopReactor`, `Hardware_ZCU106` and the reactors you
wrote in the `.lf` (`Accel_Sum`, `Accel_Mult`, `Accel_Subtrac`), plus the
`RegFile` and the AXI adapters:

<p align="center">
<img title="Project hierarchy" alt="Project Summary with the ZCU106Wrapper hierarchy expanded" src="../images/passo-12.png" width="100%" height="100%">
</p>

This is a good moment to check that the reactors from your `.lf` really did
turn into hardware — each one shows up as a module of its own.

### 1.4 Create the Block Design

**IP INTEGRATOR → Create Block Design**:

<p align="center">
<img title="Hierarchy and Create Block Design" alt="Reactors highlighted in the hierarchy and an arrow pointing to Create Block Design" src="../images/passo-13.png" width="100%" height="100%">
</p>

Accept the default name (`design_1`):

<p align="center">
<img title="Block Design name" alt="Create Block Design dialog with the name design_1" src="../images/passo-14.png" width="100%" height="100%">
</p>

### 1.5 Add the processor

In the empty diagram, click **+** (Add IP), type `zynq` and choose
**Zynq UltraScale+ MPSoC**:

<p align="center">
<img title="Adding the Zynq UltraScale+ MPSoC" alt="Searching for zynq in the IP catalog" src="../images/passo-15.png" width="100%" height="100%">
</p>

Run the **Run Block Automation** offered in the green bar: it applies the
ZCU106 presets (DDR, UART, Ethernet, clocks).

### 1.6 Add the `ZCU106Wrapper`

The wrapper is project RTL, not a catalog IP. Right-click an empty area of
the diagram and choose **Add Module…**:

<p align="center">
<img title="Add Module menu" alt="Diagram context menu with Add Module highlighted" src="../images/passo-16.png" width="100%" height="100%">
</p>

Select `ZCU106Wrapper` from the list:

<p align="center">
<img title="Selecting the ZCU106Wrapper module" alt="Add Module dialog with ZCU106Wrapper selected" src="../images/passo-17.png" width="100%" height="100%">
</p>

> **If `ZCU106Wrapper` appears greyed out:** `Q_srl.v` is missing from the
> project (1.3). The submodules show up normally and only the top is
> disabled, because Vivado treats it as an incomplete hierarchy. Add the file
> through **Add Sources** and reopen the dialog. Unchecking *Hide
> incompatible modules* only reveals the problem — it does not fix it.

The block enters the diagram with its AXI interfaces already grouped: `csr`
(AXI4-Lite, slave) and `mem0..mem3` (AXI4, master), plus `clock` and `reset`.

### 1.7 Optional peripherals

LEDs and dip switches are not required by the flow — use them if you want a
visual sign that the system is alive. **Add IP → `AXI GPIO`**:

<p align="center">
<img title="Adding the AXI GPIO" alt="Searching for AXI GPIO in the IP catalog" src="../images/passo-18.png" width="100%" height="100%">
</p>

At this point the diagram has all three blocks, still unconnected:

<p align="center">
<img title="Blocks added to the diagram" alt="ZCU106Wrapper, AXI GPIO and Zynq UltraScale+ MPSoC in the diagram, disconnected" src="../images/passo-19.png" width="100%" height="100%">
</p>

### 1.8 Automatic connections

Click **Run Connection Automation** in the green bar and tick everything. The
first pass connects the wrapper's `csr` and the GPIO interfaces to the PS's
`M_AXI_HPM0_FPD`, creating the `AXI SmartConnect` and the
`Processor System Reset`:

<p align="center">
<img title="Connection Automation — csr and GPIO" alt="Run Connection Automation dialog with the ZCU106Wrapper csr and the AXI GPIO interfaces ticked" src="../images/passo-20.png" width="100%" height="100%">
</p>

Run it again for the memory ports: the wrapper's `mem0..mem3` towards the PS:

<p align="center">
<img title="Connection Automation — memory ports" alt="Run Connection Automation dialog with mem0 through mem3 ticked" src="../images/passo-21.png" width="100%" height="100%">
</p>

> **Note:** the accelerator effectively uses `mem0`; `mem1..mem3` are ports
> plugged with defaults by `AXIPlatformWrapper`. Connecting them does no
> harm, and leaving them unconnected also works — it reduces the diagram's
> wiring.

### 1.9 Clock and reset

The PS's `pl_clk0` feeds the wrapper, the `Processor System Reset` and the
SmartConnects. In this tutorial it is set to **100 MHz**.

> **That number has to make it into the `.lf`.** The generated RTL
> increments the logical time registers (`physicalTag`, `regClock`) on every
> clock pulse, adding the value of `clock-period`. At 100 MHz:
>
> ```lf
> target Codesign {
>   fpgaBoard: ZCU106,
>   clock-period: 10 nsec   // 10 ns = 100 MHz, same as pl_clk0
> }
> ```
>
> The default is 1 nsec (1 GHz). Generating the RTL with the default and
> synthesizing at 100 MHz leaves the whole tag coordination 10× out of sync
> with real time.

>**Check the reset.** The `ZCU106Wrapper` `reset` input is
> **active-high**: wire it to `peripheral_reset` of the
> `Processor System Reset`, **not** to `peripheral_aresetn`. Inverted, the
> accelerator stays permanently in reset — synthesis passes without
> complaining and the failure only shows up at run time, in the signature
> read.

### 1.10 Debugging with a System ILA (optional)

To observe the AXI transactions in the Hardware Manager, right-click the
`csr` connection and choose **Debug**:

<p align="center">
<img title="Marking the connection for debug" alt="Context menu of the AXI connection with the Debug option highlighted" src="../images/passo-22.png" width="100%" height="100%">
</p>

Connection Automation then offers to insert the **System ILA**. The defaults
(*Data and Trigger* on every channel, clock from `pl_clk0`) are fine:

<p align="center">
<img title="System ILA insertion" alt="Run Connection Automation dialog configuring the System ILA" src="../images/passo-23.png" width="100%" height="100%">
</p>

### 1.11 Accelerator address

Open the **Address Editor** tab. If it is empty, click *Generate Address
Map*:

<p align="center">
<img title="Address Map not generated yet" alt="Address Map tab with the Generate Address Map link" src="../images/passo-24.png" width="100%" height="100%">
</p>

By default Vivado assigns `csr` a high address with a 4G range — in the
example, `0x5_0000_0000`:

<p align="center">
<img title="Automatically assigned address" alt="Address Editor showing csr at 0x5_0000_0000 with a 4G range" src="../images/passo-25.png" width="100%" height="100%">
</p>

**Change it to `0xA000_0000` with a `64K` range**, the conventional PL
peripheral range on the MPSoC:

<p align="center">
<img title="Corrected csr address" alt="Address Editor with csr at 0xA000_0000 and a 64K range" src="../images/passo-27.png" width="100%" height="100%">
</p>

> **Why that address matters.** It is defined directly in the code
> (hardcoded).
>
> 1. `sdtgen` writes it into the device tree as the node
>    `ZCU106Wrapper@a0000000`.
> 2. The kernel exposes that name in `/sys/class/uio/uioN/name`.
> 3. `platform-mpsoc-uio.cpp` looks for the substring `"ZCU106Wrapper"` in
>    that file to work out which `/dev/uioN` to open.
>
> Because the lookup is by substring, changing the address does not break the
> driver — but renaming the wrapper does. And the default 4G range makes the
> accelerator occupy an absurd slice of the address map; 64K is the regfile's
> real size.

### 1.12 Generate the HDL wrapper

Validate the diagram with **Validate Design** (`F6`). Then, in the Sources
tab, right-click `design_1` and choose **Create HDL Wrapper** (let Vivado
manage it):

<p align="center">
<img title="Create HDL Wrapper" alt="design_1 context menu with Create HDL Wrapper and Generate Output Products" src="../images/passo-28.png" width="100%" height="100%">
</p>

Set `design_1_wrapper` as the project top (**Set as Top**):

<p align="center">
<img title="Setting the project top" alt="design_1_wrapper context menu with Set as Top highlighted" src="../images/passo-29.png" width="100%" height="100%">
</p>

`design_1_wrapper` then appears in bold in the hierarchy:

<p align="center">
<img title="Project ready for synthesis" alt="Hierarchy with design_1_wrapper as top and the complete diagram" src="../images/passo-30.png" width="100%" height="100%">
</p>

### 1.13 Generate the bitstream

**PROGRAM AND DEBUG → Generate Bitstream**. Set *Number of jobs* to the
number of cores available:

<p align="center">
<img title="Generate Bitstream Launch Runs" alt="Launch Runs dialog with the number of jobs configured" src="../images/passo-31.png" width="100%" height="100%">
</p>

Synthesis and implementation take tens of minutes. Once the status bar shows
`write_bitstream Complete`, move on to exporting the XSA.


## 2. Exporting the hardware (XSA)

With the hardware validated, the next step is generating the **XSA** (Xilinx Support Archive) file, which describes the hardware and will be consumed by PetaLinux to create the BSP (Board Support Package).

In Vivado, go to **File → Export → Export Hardware** and follow the wizard:

**Step 1 – Start of the export wizard.** Confirm that you want to export the current project's hardware.

<p align="center">
<img title="Hardware export wizard" alt="Opening screen of Vivado's Export Hardware wizard" src="../images/passo-32.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Hardware export wizard" alt="Second screen of the Export Hardware wizard" src="../images/passo4.png" width="100%" height="100%">
</p>

**Step 2 – Include the bitstream.** Tick the **Include Bitstream** option, so that the XSA file contains the FPGA's complete configuration.

<p align="center">
<img title="Include Bitstream option ticked" alt="Wizard screen with the Include Bitstream option selected" src="../images/passo5.png" width="100%" height="100%">
</p>

**Step 3 – Choose the destination directory.** In this example:

```bash
/home/xmen/workspace/<project_name>
```

<p align="center">
<img title="Export directory selection" alt="Screen for selecting the directory where the XSA will be saved" src="../images/passo6.png" width="100%" height="100%">
</p>

**Step 4 – Confirm the export.** Check that the `.xsa` file was generated in the right directory:

```bash
cd /home/xmen/workspace
ls
# <project name>.xsa
```

<p align="center">
<img title="Confirming the generated XSA file" alt="Terminal showing the generated .xsa file in the workspace directory" src="../images/passo8.png" width="100%" height="100%">
</p>

## 3. Generating the Software Device Tree (SDT)

Create a Tcl script called `sdt.tcl`:

<p align="center">
<img title="Creating the sdt.tcl script" alt="Text editor creating the sdt.tcl file" src="../images/passo9.png" width="100%" height="100%">
</p>

Contents of `sdt.tcl`:

```tcl
set outdir [lindex $argv 1]
set xsa [lindex $argv 0]
exec rm -rf $outdir
sdtgen set_dt_param -xsa $xsa -dir $outdir -board_dts zcu106-reva
sdtgen generate_sdt
```

> **Note:** more details about the TCL in [TCL File Mode - Xilinx System-Device-Tree-xlnx](https://github.com/Xilinx/system-device-tree-xlnx/blob/master/README.md).

> **Note:** adjust `zcu106-reva` to the identifier matching your board, if you are not using a ZCU106.

<p align="center">
<img title="Contents of the sdt.tcl script" alt="Contents of the sdt.tcl file open in the editor" src="../images/passo10.png" width="100%" height="100%">
</p>

Confirm that the file was created successfully:

```bash
cd /home/xmen/workspace/
cat sdt.tcl
```

<p align="center">
<img title="Checking the contents of sdt.tcl" alt="Terminal displaying the contents of sdt.tcl with cat" src="../images/passo11.png" width="100%" height="100%">
</p>

Run the script with `xsct`, from the workspace directory:

```bash
cd /home/xmen/workspace/
```

General syntax:

```bash
xsct sdt.tcl <path_to_XSA_file> sdt_outdir
```

The example used in this tutorial:

```bash
xsct sdt.tcl design_1_wrapper.xsa sdt_outdir
```

<p align="center">
<img title="Running xsct to generate the SDT" alt="Terminal running xsct sdt.tcl" src="../images/passo12.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Output of the Software Device Tree generation" alt="Terminal showing sdtgen processing" src="../images/passo13.png" width="100%" height="100%">
</p>

At the end, the `sdt_outdir` directory should contain the device tree files generated from the exported hardware.

## 4. Creating and configuring the PetaLinux project

Go to the PetaLinux installation directory and load the environment:

```bash
cd /petalinux
source settings.sh
```

<p align="center">
<img title="Loading the PetaLinux environment" alt="Terminal running source settings.sh for PetaLinux" src="../images/passo17.png" width="100%" height="100%">
</p>

Once it is loaded, go back to the project directory:

```bash
cd -
```

Create the PetaLinux project from the board's BSP:

```bash
petalinux-create -t project -s <path_to_file>.bsp
```

The example used in this tutorial:

```bash
petalinux-create -t project -s /petalinux/xilinx-zcu106-v2025.2-11160223.bsp
```

After it runs, the project's base directory should contain the standard structure of a PetaLinux project:

<p align="center">
<img title="Structure of the created PetaLinux project" alt="Directory listing of the freshly created PetaLinux project" src="../images/passo18.png" width="100%" height="100%">
</p>

## 5. Incorporating the hardware features into PetaLinux

Enter the directory of the project you created (the name varies with the PetaLinux version):

```bash
cd xilinx-zcu106-20xx.x
```

In this tutorial:

```bash
cd xilinx-zcu106-2025.2
```

Point the project at the exported hardware (the SDT directory generated in step 3):

```bash
petalinux-config --get-hw-description ../sdt_outdir
```

<p align="center">
<img title="Importing the hardware description" alt="Terminal running petalinux-config --get-hw-description" src="../images/passo19.png" width="100%" height="100%">
</p>

### 5.1 Parallel build threads

In the configuration menu, go to:

**PetaLinux System Configuration → Yocto Settings → Parallel Thread Execution**

<p align="center">
<img title="Parallel thread configuration menu" alt="PetaLinux System Configuration ncurses menu under Yocto Settings" src="../images/passo20.png" width="100%" height="100%">
</p>

Check how many CPUs are available in your working environment:

```bash
neofetch
```

<p align="center">
<img title="Checking the available CPU cores" alt="neofetch output showing CPU information" src="../images/cpuinfo.png" width="100%" height="100%">
</p>

In this example, an Intel i9-14900F with 32 cores:

<p align="center">
<img title="Detail of the CPU used in the example" alt="Detail of the Intel i9-14900F processor with 32 cores" src="../images/passo21.png" width="100%" height="100%">
</p>

Set the parallelism fields (**BB Number of Threads**, **PARALLEL_MAKE**, etc.) according to the number of cores available, to speed up the build:

<p align="center">
<img title="Configuring the number of parallel threads" alt="Thread configuration fields filled in with the number of cores" src="../images/passo22.png" width="100%" height="100%">
</p>

### 5.2 Kernel boot arguments (generic UIO)

Go to:

**PetaLinux System Configuration → DT Settings → Kernel Bootargs → Add extra boot args**

Add a **Generic UIO (Userspace I/O)** kernel driver to the image, to allow access to the PL peripherals from userspace:

<p align="center">
<img title="Extra boot arguments menu" alt="Kernel Bootargs configuration menu" src="../images/passo23.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Extra boot arguments field" alt="Text field for adding extra boot arguments" src="../images/passo24.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Editing the boot arguments" alt="Editing the boot args field" src="../images/passo25.png" width="100%" height="100%">
</p>

Add the argument:

```
uio_pdrv_genirq.of_id=generic-uio
```

<p align="center">
<img title="uio_pdrv_genirq argument added" alt="Boot args field filled in with uio_pdrv_genirq.of_id=generic-uio" src="../images/passo26.png" width="100%" height="100%">
</p>

### 5.3 Keep the PL in the device tree

Go to **PetaLinux System Configuration → DT Settings** and **untick** the **Remove PL from devicetree** option, so that the programmable logic IPs appear in the final device tree:

<p align="center">
<img title="Remove PL from devicetree option" alt="DT Settings menu with the Remove PL from devicetree option" src="../images/passo27.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Remove PL from devicetree option unticked" alt="Remove PL from devicetree option unticked" src="../images/passo28.png" width="100%" height="100%">
</p>

### 5.4 Image packaging

Go to **PetaLinux System Configuration → Image Packaging Configuration** and **untick** the **Copy final images to tftpboot** option (unless you boot over TFTP):

<p align="center">
<img title="Copy final images to tftpboot option" alt="Image Packaging Configuration menu with the tftpboot option unticked" src="../images/passo30.png" width="100%" height="100%">
</p>

Save the settings and leave the **PetaLinux System Configuration** menu:

<p align="center">
<img title="Saving the menu settings" alt="Confirmation screen for saving the settings" src="../images/passo31.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Leaving the configuration menu" alt="Terminal after leaving the PetaLinux configuration menu" src="../images/passo32.png" width="100%" height="100%">
</p>

### 5.5 Synchronize the PL device tree

From the project directory (`/home/xmen/workspace/xilinx-zcu106-2025.2/`), open the two files below and copy the contents of `pl.dtsi` into `system-user.dtsi`:

```bash
code ../sdt_outdir/pl.dtsi
code project-spec/meta-user/recipes-bsp/device-tree/files/system-user.dtsi
```

> **Note:** details in the Vitis Tutorial [02 Edge AI ZCU104](https://xilinx.github.io/Vitis-Tutorials/2021-1/build/html/docs/Vitis_Platform_Creation/Introduction/02-Edge-AI-ZCU104/step2.html).
> **Note:** details in GPIO PetaLinux [GPIO PetaLinux Part 2](https://www.linkedin.com/pulse/gpio-petalinux-part-2-roy-messinger/).

<p align="center">
<img title="Copying pl.dtsi into system-user.dtsi" alt="VS Code with pl.dtsi and system-user.dtsi side by side" src="../images/passo33.png" width="100%" height="100%">
</p>

On the line corresponding to the `uio` node, change the `compatible` property to:

```
compatible = "generic-uio";
```

Save and close VS Code:

<p align="center">
<img title="Changing the compatible property" alt="compatible = generic-uio line edited in system-user.dtsi" src="../images/passo34.png" width="100%" height="100%">
</p>

<p align="center">
<img title="system-user.dtsi file saved" alt="File saved in VS Code" src="../images/passo36.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Terminal after editing the device tree" alt="Back at the terminal after closing the editor" src="../images/passo38.png" width="100%" height="100%">
</p>

## 6. Configuring the RootFS

```bash
petalinux-config -c rootfs
```

> **Tip:** use `/` to search for the options you want to enable and select the item with the keyboard's space bar.

### Enable the following items in the rootfs

| Item | Category | Priority |
|---|---|---|
| `libstdc++` | libs | Mandatory |
| `kmod` | misc | Mandatory |
| `udev` | base | Mandatory |
| `libgpiod` | libs | Required (GPIO) |
| `gpio-demo` | console → utils | Recommended |
| `ssh-server-dropbear` **or** `ssh-server-openssh` (not both) | console → network | Recommended |
| `python3-numpy` | devel | Optional (data analysis) |
| `packagegroup-core-buildessential` | petalinux | Optional (on-board development, increases image size) |
| `util-linux` | base | Required (boot from USB stick) |
| `e2fsprogs` | console → utils | Required (boot from USB stick) |
| `dosfstools` | console → utils | Required (boot from USB stick) |
| `gdb` | console → utils | Required (debugging — tick it explicitly) |
| `packagegroup-core-tools-debug` | petalinux | Optional (debug symbols) |
| `strace` | console → utils | Optional (debugging) |
| `sudo` | base | Mandatory |

**Note:** under `gpio-demo`, keep the following options enabled:

<p align="center">
<img title="gpio-demo package options" alt="Configuration menu of the gpio-demo package" src="../images/passo39.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Sub-options enabled under gpio-demo" alt="gpio-demo sub-options ticked" src="../images/passo40.png" width="100%" height="100%">
</p>

Save the settings and leave the **Configuration** menu:

<p align="center">
<img title="Confirming the gpio-demo options" alt="Final confirmation screen for the gpio-demo options" src="../images/passo41.png" width="100%" height="100%">
</p>

## 7. Building PetaLinux

The build can take anywhere from a few minutes to a few hours, depending on your computer's hardware and the number of threads configured.

```bash
petalinux-build
```

<p align="center">
<img title="Start of the PetaLinux build" alt="Terminal with the PetaLinux build in progress" src="../images/passo43.png" width="100%" height="100%">
</p>

It is common for the **first run** to produce warnings and errors related to the Yocto/BitBake cache, making the build fail. In that case, simply run the command again — the partially built cache usually resolves the problem on the second attempt. For more details about known build failures, see the PetaLinux/Yocto troubleshooting guide, for example the ["Build failures" section of the RPi Camera FMC documentation](https://rpi.camerafmc.com/en/latest/troubleshooting.html) or AMD/Xilinx's official documentation for your tool version.

<p align="center">
<img title="Cache errors on the first build run" alt="Terminal showing cache errors on the first build attempt" src="../images/passo44.png" width="100%" height="100%">
</p>

Run it again:

```bash
petalinux-build
```

<p align="center">
<img title="Second build run" alt="Terminal with the second petalinux-build run" src="../images/passo45.png" width="100%" height="100%">
</p>

### Successful build

At the end, the boot artifacts (`BOOT.BIN`, `boot.scr`, `image.ub`/`Image`, `system.dtb`, rootfs) will be available under `images/linux/` inside the project, ready to be written to an SD card/USB stick or used over TFTP/JTAG.

<p align="center">
<img title="PetaLinux build completed successfully" alt="Terminal showing the PetaLinux build finishing successfully" src="../images/passo46.png" width="100%" height="100%">
</p>

## 8. Booting via JTAG / USB stick

This method was chosen because of the constraint of doing the whole process remotely, without any physical contact with the FPGA — that is, without touching the switches that other methods would require. There is also a method that uses `petalinux-boot jtag --kernel`, but the resulting system is incomplete and was not explored further in this tutorial.

### Step 1 — Copy the artifacts to the USB stick

Copy the artifacts produced by the build (in this example, from `/home/xmen/workspace/images/linux`) to the USB stick (in this example, `/run/media/xmen/valmir`):

<p align="center">
<img title="PetaLinux build completed successfully" alt="Terminal showing the PetaLinux build finished successfully, artifacts ready to copy" src="../images/passo48.png" width="100%" height="100%">
</p>

Create a folder on your USB stick with whatever name you like.

Use the command below to list the available disks:

```bash
df -h
```

In this tutorial's example, the USB stick is mounted at `/run/media/xmen/valmir`:

```bash
cd /run/media/xmen/valmir
mkdir workspace
```

Copy the `rootfs.cpio.gz.u-boot` and `image.ub` artifacts to the USB stick:

```bash
cd ~/workspace/xilinx-zcu106-2025.2/images/linux
cp rootfs.cpio.gz.u-boot image.ub /run/media/xmen/valmir/workspace
```

<p align="center">
<img title="Artifacts copied to the USB stick" alt="Terminal showing rootfs.cpio.gz.u-boot and image.ub being copied to the USB stick" src="../images/passo49.png" width="100%" height="100%">
</p>

### Step 2 — Plug in the USB stick and open the serial monitor

Once the files are copied, plug the USB stick into the FPGA.

Start Vitis and go to **Vitis → Serial Monitor**. Select the first UART belonging to the ZYNQ:

<p align="center">
<img title="Selecting the UART in Vitis" alt="Vitis Serial Monitor menu with the ZYNQ UART being selected" src="../images/passo50.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Serial monitor connected" alt="Serial terminal connected to the board's UART" src="../images/passo51.png" width="100%" height="100%">
</p>

Load the PetaLinux environment again:

<p align="center">
<img title="Loading the PetaLinux environment again" alt="Terminal running source on the PetaLinux settings.sh" src="../images/passo52.png" width="100%" height="100%">
</p>

And go to the project directory (`/home/xmen/workspace/xilinx-zcu106-2025.2`):

<p align="center">
<img title="Entering the project directory" alt="Terminal in the PetaLinux project directory" src="../images/passo53.png" width="100%" height="100%">
</p>

### Step 3 — Start U-Boot over JTAG

Start U-Boot with a prebuilt image:

```bash
petalinux-boot jtag --prebuilt 2
```

<p align="center">
<img title="Booting over JTAG with a prebuilt image" alt="Terminal running petalinux-boot jtag --prebuilt 2" src="../images/passo54.png" width="100%" height="100%">
</p>

### Step 4 — Load the files into RAM

In the UART terminal:

```
zynq>
zynq> fatload usb 0:1 0x10000000 {directory of the folder on the USB stick}/image.ub
```

> **Note:** the `usb 0:x` device may vary, and the directory depends on the folder you created on the USB stick.

In this example:

```
fatload usb 0:1 0x10000000 workspace/image.ub
fatload usb 0:1 0x20000000 workspace/rootfs.cpio.gz.u-boot
```

<p align="center">
<img title="Loading the artifacts into RAM" alt="Terminal with the fatload commands loading image.ub and the rootfs into RAM" src="../images/passo55.png" width="100%" height="100%">
</p>

### Step 5 — Boot from RAM

```
bootm 0x10000000 0x20000000
```

<p align="center">
<img title="Booting from RAM" alt="Terminal running the bootm command" src="../images/passo56.png" width="100%" height="100%">
</p>

> **Note:** with this method, the boot is expected to hang on this screen — that happens because the bitstream has not been written to the programmable logic yet.

<p align="center">
<img title="Boot stalled for lack of the bitstream" alt="Terminal with the boot stopped, waiting for the programmable logic to be programmed" src="../images/passo57.png" width="100%" height="100%">
</p>

### Step 6 — Write the bitstream to the programmable logic

In Vivado, go to **PROGRAM AND DEBUG → Program Device** and select the board (in this example, `xczu7_0`):

<p align="center">
<img title="Selecting the device in Vivado" alt="Vivado's Program Device menu with the xczu7_0 device selected" src="../images/passo58.png" width="100%" height="100%">
</p>

**PROGRAM AND DEBUG → Program Device → `xczu7_0` → Program**

<p align="center">
<img title="Programming the bitstream" alt="Vivado writing the bitstream to the xczu7_0 device" src="../images/passo59.png" width="100%" height="100%">
</p>

After that, the system will boot:

<p align="center">
<img title="System booted successfully" alt="Terminal showing the embedded Linux booted successfully" src="../images/passo60.png" width="100%" height="100%">
</p>

### Step 7 — First login and SSH access

By default the user is `petalinux`; the password is the one you set on first login.

<p align="center">
<img title="PetaLinux login prompt" alt="Terminal showing the petalinux user's login prompt" src="../images/passo61.png" width="100%" height="100%">
</p>

By now you have probably noticed that, over the UART, commands appear duplicated. For that reason, SSH is recommended. To use it, you need to connect the FPGA to the network and find out the IP address assigned to it:

```bash
ifconfig
```

In this example, the IP obtained was `192.168.130.31`:

<p align="center">
<img title="Checking the board's IP address" alt="Terminal showing the output of ifconfig with the board's IP" src="../images/passo62.png" width="100%" height="100%">
</p>

Open a terminal on the host and connect over SSH:

```bash
ssh petalinux@192.168.130.31
```

<p align="center">
<img title="Connecting over SSH" alt="Host terminal connecting to the board over ssh" src="../images/passo63.png" width="100%" height="100%">
</p>

> **Note:** use the password you set earlier.

<p align="center">
<img title="SSH authentication" alt="Terminal asking for the petalinux user's password over SSH" src="../images/passo64.png" width="100%" height="100%">
</p>

### Step 8 — Check that UIO is recognized

Checking that the Generic Userspace I/O (UIO) is recognized:

```bash
cat /sys/class/uio/uio*/name
```

<p align="center">
<img title="UIO check" alt="Terminal showing the output of cat on /sys/class/uio/uio*/name" src="../images/passo66.png" width="100%" height="100%">
</p>

## 9. Deploying onto the FPGA

With the system up and reachable over SSH, deploying the application follows the steps below:

1. Use `scp` to copy the `.tar` produced in the container to the FPGA, into `/home/petalinux`.
2. Extract the contents of the `.tar` and switch to `sudo`:
   ```bash
   sudo -i
   ```
3. Load the library from `deploy_all_files`:
   ```bash
   export LD_LIBRARY_PATH=./lib
   ```
4. Run the binary:
   ```bash
   ./bin/ExplorationMultipleReactorsCodesign
   ```

The project used as an example is in the `examples` folder and explores the use of multiple reactors through an extension of the subset of the Lingua Franca language aimed at the *Codesign of Reactor-Oriented Hardware and Software for Cyber-Physical Systems*.

<p align="center">
<img title="Deploying the project onto the FPGA" alt="Terminal showing the deployment of the ExplorationMultipleReactorsCodesign project onto the FPGA" src="../images/passo71.png" width="100%" height="100%">
</p>

<p align="center">
<img title="Running the binary on the FPGA" alt="Terminal showing the ExplorationMultipleReactorsCodesign binary running" src="../images/passo67.png" width="100%" height="100%">
</p>

If a **Bus error (core dumped)** occurs, the bitstream has to be programmed again — use the steps described in [section 8](#8-booting-via-jtag--usb-stick).

<p align="center">
<img title="Bus error (core dumped)" alt="Terminal showing the Bus error (core dumped) error while running the binary" src="../images/passo68.png" width="100%" height="100%">
</p>

Run it again:

<p align="center">
<img title="Successful run after reprogramming the bitstream" alt="Terminal showing the binary running successfully after reprogramming the bitstream" src="../images/passo70.png" width="100%" height="100%">
</p>

---

> This tutorial still has room for improvement and optimization — contributions and suggestions are welcome.

## 10. Useful links

- [ZCU104 tutorial](https://www.hackster.io/engrinam0077/zcu104-mpsoc-development-petalinux-2024-2-basic-tutorial-c82b8d)
- ["Build failures" section of the RPi Camera FMC documentation](https://rpi.camerafmc.com/en/latest/troubleshooting.html)
- [TCL File Mode - Xilinx System-Device-Tree-xlnx](https://github.com/Xilinx/system-device-tree-xlnx/blob/master/README.md).
- [AVNET flow](https://gitlab.avnet.com/xilinx/example-board-projects/zcu106/vitis-platforms/avnet-sme-zcu106-vitis-platform-workflow-training/-/tree/main/2021.2/avtsme_zcu106_base)
- [ZCU106 tutorial](https://www.fpgalover.com/index.php/boards/39-zcu106/97-build-petalinux-for-zcu106-ps-and-pl-based-10g-ethernet)
- [PetaLinux documentation](https://docs.amd.com/r/en-US/ug1144-petalinux-tools-reference-guide/Introduction)
- [02 Edge AI ZCU104](https://xilinx.github.io/Vitis-Tutorials/2021-1/build/html/docs/Vitis_Platform_Creation/Introduction/02-Edge-AI-ZCU104/step2.html).
- [GPIO PetaLinux Part 2](https://www.linkedin.com/pulse/gpio-petalinux-part-2-roy-messinger/).
