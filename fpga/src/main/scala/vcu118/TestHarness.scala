package chipyard.fpga.vcu118

import chisel3._
import chisel3.experimental.{IO}

import freechips.rocketchip.diplomacy.{LazyModule, LazyRawModuleImp, BundleBridgeSource}
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.tilelink.{TLClientNode}

import sifive.fpgashells.shell.xilinx.{VCU118ShellBasicOverlays, UARTVCU118ShellPlacer, SDIOVCU118ShellPlacer, JTAGDebugBScanVCU118ShellPlacer, JTAGDebugVCU118ShellPlacer, cJTAGDebugVCU118ShellPlacer, PCIeVCU118FMCShellPlacer, PCIeVCU118EdgeShellPlacer, VCU118ShellPMOD, ChipLinkVCU118PlacedOverlay}
import sifive.fpgashells.ip.xilinx.{IBUF, PowerOnResetFPGAOnly}
import sifive.fpgashells.shell.{ClockInputOverlayKey, ClockInputDesignInput, ClockInputShellInput, UARTOverlayKey, UARTDesignInput, UARTShellInput, SPIOverlayKey, SPIDesignInput, SPIShellInput, JTAGDebugOverlayKey, JTAGDebugShellInput, JTAGDebugBScanOverlayKey, JTAGDebugBScanShellInput, cJTAGDebugOverlayKey, cJTAGDebugShellInput, PCIeOverlayKey, PCIeDesignInput, PCIeShellInput, DDROverlayKey, DDRDesignInput, DDRShellInput}
import sifive.fpgashells.clocks.{ClockGroup, ClockSinkNode, PLLFactoryKey, ResetWrangler}

import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTPortIO}
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIPortIO}

import chipyard.{HasHarnessSignalReferences, BuildTop, ChipTop, ExtTLMem, CanHaveMasterTLMemPort, DefaultClockFrequencyKey}
import chipyard.iobinders.{HasIOBinders}
import chipyard.harness.{ApplyHarnessBinders}

class VCU118FPGATestHarness(override implicit val p: Parameters) extends VCU118ShellBasicOverlays {

  def dp = designParameters

  val pmod_is_sdio  = p(VCU118ShellPMOD) == "SDIO"
  val jtag_location = Some(if (pmod_is_sdio) "FMC_J2" else "PMOD_J52")

  // Order matters; ddr depends on sys_clock
  val uart      = Overlay(UARTOverlayKey, new UARTVCU118ShellPlacer(this, UARTShellInput()))
  val sdio      = if (pmod_is_sdio) Some(Overlay(SPIOverlayKey, new SDIOVCU118ShellPlacer(this, SPIShellInput()))) else None
  val jtag      = Overlay(JTAGDebugOverlayKey, new JTAGDebugVCU118ShellPlacer(this, JTAGDebugShellInput(location = jtag_location)))
  val cjtag     = Overlay(cJTAGDebugOverlayKey, new cJTAGDebugVCU118ShellPlacer(this, cJTAGDebugShellInput()))
  val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanVCU118ShellPlacer(this, JTAGDebugBScanShellInput()))
  val fmc       = Overlay(PCIeOverlayKey, new PCIeVCU118FMCShellPlacer(this, PCIeShellInput()))
  val edge      = Overlay(PCIeOverlayKey, new PCIeVCU118EdgeShellPlacer(this, PCIeShellInput()))
  val sys_clock2 = Overlay(ClockInputOverlayKey, new SysClock2VCU118ShellPlacer(this, ClockInputShellInput()))
  val ddr2       = Overlay(DDROverlayKey, new DDR2VCU118ShellPlacer(this, DDRShellInput()))

  val topDesign = LazyModule(p(BuildTop)(dp)).suggestName("chiptop")

// DOC include start: ClockOverlay
  // place all clocks in the shell
  require(dp(ClockInputOverlayKey).size >= 1)
  val sysClkNode = dp(ClockInputOverlayKey)(0).place(ClockInputDesignInput()).overlayOutput.node

  /*** Connect/Generate clocks ***/

  // connect to the PLL that will generate multiple clocks
  val harnessSysPLL = dp(PLLFactoryKey)()
  harnessSysPLL := sysClkNode

  // create and connect to the dutClock
  println(s"VCU118 FPGA Base Clock Freq: ${dp(DefaultClockFrequencyKey)} MHz")
  val dutClock = ClockSinkNode(freqMHz = dp(DefaultClockFrequencyKey))
  val dutWrangler = LazyModule(new ResetWrangler)
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLL
// DOC include end: ClockOverlay

  /*** UART ***/

// DOC include start: UartOverlay
  // 1st UART goes to the VCU118 dedicated UART

  val io_uart_bb = BundleBridgeSource(() => (new UARTPortIO(dp(PeripheryUARTKey).head)))
  dp(UARTOverlayKey).head.place(UARTDesignInput(io_uart_bb))
// DOC include end: UartOverlay

  /*** SPI ***/

  // 1st SPI goes to the VCU118 SDIO port

  val io_spi_bb = BundleBridgeSource(() => (new SPIPortIO(dp(PeripherySPIKey).head)))
  dp(SPIOverlayKey).head.place(SPIDesignInput(dp(PeripherySPIKey).head, io_spi_bb))

  /*** DDR ***/

  val ddrNode = dp(DDROverlayKey).head.place(DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLL)).overlayOutput.ddr

  // connect 1 mem. channel to the FPGA DDR
  val inParams = topDesign match { case td: ChipTop =>
    td.lazySystem match { case lsys: CanHaveMasterTLMemPort =>
      lsys.memTLNode.edges.in(0)
    }
  }
  val ddrClient = TLClientNode(Seq(inParams.master))
  ddrNode := ddrClient

  // module implementation
  override lazy val module = new LazyRawModuleImp(this) with HasHarnessSignalReferences {
    val reset = IO(Input(Bool()))
    xdc.addPackagePin(reset, "L19")
    xdc.addIOStandard(reset, "LVCMOS12")

    val resetIBUF = Module(new IBUF)
    resetIBUF.io.I := reset

    val sysclk: Clock = sysClkNode.out.head._1.clock

    val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
    sdc.addAsyncPath(Seq(powerOnReset))

    val ereset: Bool = chiplink.get() match {
      case Some(x: ChipLinkVCU118PlacedOverlay) => !x.ereset_n
      case _ => false.B
    }

    pllReset := (resetIBUF.io.O || powerOnReset || ereset)

    // reset setup
    val hReset = Wire(Reset())
    hReset := dutClock.in.head._1.reset

    val buildtopClock = dutClock.in.head._1.clock
    val buildtopReset = WireInit(hReset)
    val dutReset = hReset.asAsyncReset
    val success = false.B

    childClock := buildtopClock
    childReset := buildtopReset

    // harness binders are non-lazy
    topDesign match { case d: HasIOBinders =>
      ApplyHarnessBinders(this, d.lazySystem, d.portMap)
    }

    // check the top-level reference clock is equal to the default
    // non-exhaustive since you need all ChipTop clocks to equal the default
    require(getRefClockFreq == p(DefaultClockFrequencyKey))
  }
}