package chipyard.fpga.vc707

import chisel3._

import sifive.fpgashells.shell.{PCIeShellInput, IOPlacedOverlay}
import sifive.fpgashells.shell.xilinx.{VC707Shell}
import sifive.fpgashells.devices.xilinx.xilinxvc707pciex1.{XilinxVC707PCIeX1IO, XilinxVC707PCIeX1Pads}
import sifive.fpgashells.clocks._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy.{BundleBridgeSource, AddressSet, InModuleBody}
import sifive.fpgashells.shell.DesignPlacer
import freechips.rocketchip.diplomacy.ValName
import sifive.fpgashells.shell.ShellPlacer
import sifive.fpgashells.shell.PlacedOverlay


case object VC707PCIeOverlayKey extends Field[Seq[DesignPlacer[VC707PCIeDesignInput, PCIeShellInput, VC707PCIeOverlayOutput]]](Nil)

case class VC707PCIeDesignInput(
    wrangler: ClockAdapterNode,
    bars: Seq[AddressSet] = Seq(AddressSet(0x40000000L, 0x1fffffffL)),
    ecam: BigInt = 0x2000000000L,
    bases: Seq[BigInt] = Nil,
    corePLL: PLLNode,
    io_pcie_bb: BundleBridgeSource[XilinxVC707PCIeX1IO]
) (implicit val p: Parameters)

case class VC707PCIeOverlayOutput( pcie_bb: BundleBridgeSource[XilinxVC707PCIeX1IO] )

abstract class VC707PCIePlacedOverlay[IO <: Data](val name: String, val di: VC707PCIeDesignInput, val si: PCIeShellInput)
    extends IOPlacedOverlay[IO, VC707PCIeDesignInput, PCIeShellInput, VC707PCIeOverlayOutput]
{
    implicit val p = di.p
}

class PCIeVC707ShellPlacer_placeholder(val shell: VC707Shell, val shellInput: PCIeShellInput)(implicit val valName: ValName) 
    extends ShellPlacer[VC707PCIeDesignInput, PCIeShellInput, VC707PCIeOverlayOutput]
{
    def place(di: VC707PCIeDesignInput) = new PCIeVC707PlacedOverlay(shell, valName.name, di, shellInput)
}

class PCIeVC707PlacedOverlay(val shell: VC707Shell, name: String, val designInput: VC707PCIeDesignInput, val shellInput: PCIeShellInput)
    extends VC707PCIePlacedOverlay[XilinxVC707PCIeX1Pads](name, designInput, shellInput) 
{
    val topIONode = shell { designInput.io_pcie_bb.makeSink() }
    val axiClk    = shell { ClockSourceNode(freqMHz = 125) }
    val areset    = shell { ClockSinkNode(Seq(ClockSinkParameters())) }
    areset := designInput.wrangler := axiClk

    def overlayOutput = VC707PCIeOverlayOutput(designInput.io_pcie_bb)
    def ioFactory     = new XilinxVC707PCIeX1Pads

    shell { InModuleBody {
        val (axi, _) = axiClk.out(0)
        val (ar, _) = areset.in(0)
        val port = topIONode.bundle
        io <> port
        axi.clock := port.axi_aclk_out
        axi.reset := !port.mmcm_lock
        port.axi_aresetn := !ar.reset
        port.axi_ctl_aresetn := !ar.reset

        shell.xdc.addPackagePin(io.REFCLK_rxp, "A10")
        shell.xdc.addPackagePin(io.REFCLK_rxn, "A9")
        shell.xdc.addPackagePin(io.pci_exp_txp, "H4")
        shell.xdc.addPackagePin(io.pci_exp_txn, "H3")
        shell.xdc.addPackagePin(io.pci_exp_rxp, "G6")
        shell.xdc.addPackagePin(io.pci_exp_rxn, "G5")

        shell.sdc.addClock(s"${name}_ref_clk", io.REFCLK_rxp, 100)
  } }

  shell.sdc.addGroup(clocks = Seq("txoutclk", "userclk1"))
}
