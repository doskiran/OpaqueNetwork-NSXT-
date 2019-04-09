package vmMigration;

import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import com.vmware.vim25.Description;
import com.vmware.vim25.HostOpaqueNetworkInfo;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.TaskInfo;
import com.vmware.vim25.TaskInfoState;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualEthernetCard;
import com.vmware.vim25.VirtualEthernetCardOpaqueNetworkBackingInfo;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualPCNet32;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

public class ReconfigVMToOpaquenetwork {

	public boolean reconfigVMToOpaquenetwork(ServiceInstance si, String vmName,
			String destLSNetwork) {
		boolean status = false;
		ManagedObjectReference hostMor = null;
		HostOpaqueNetworkInfo opaqueNetworkInfo = null;
		try {
			Folder rootFolder = si.getRootFolder();
			VirtualMachine vm = (VirtualMachine) new InventoryNavigator(
					rootFolder).searchManagedEntity("VirtualMachine", vmName);
			if (vm == null) {
				System.out.println("No VM " + vmName + " found");
				si.getServerConnection().logout();
				return false;
			}
			hostMor = vm.getRuntime().getHost();
			HostSystem hs = new HostSystem(si.getServerConnection(), hostMor);

			HostOpaqueNetworkInfo[] hopArr = hs.getConfig().getNetwork()
					.getOpaqueNetwork();
			for (HostOpaqueNetworkInfo hop : hopArr) {
				if (hop.getOpaqueNetworkName().equals(destLSNetwork)) {
					opaqueNetworkInfo = hop;
				}
			}
			VirtualMachineConfigSpec spec = new VirtualMachineConfigSpec();
			spec.setDeviceChange(reconfigVMNicConnection(vm, opaqueNetworkInfo));
			System.out.println("VM reconfiguration started...");
			Task task = vm.reconfigVM_Task(spec);
			TaskInfo ti = waitFor(task);
			if (ti.getState() == TaskInfoState.error) {
				System.out.println("VM reconfiguration failed" + vmName);
				return false;
			}
			System.out.println("VM reconfiguration success");
			si.getServerConnection().logout();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return status;
	}

	public static TaskInfo waitFor(Task task) throws RemoteException,
			InterruptedException {
		while (true) {
			TaskInfo ti = task.getTaskInfo();
			TaskInfoState state = ti.getState();
			if (state == TaskInfoState.success || state == TaskInfoState.error) {
				if (ti.getError() != null) {
					System.out.println("Error::"
							+ ti.getError().getLocalizedMessage());
				}
				return ti;
			}
			Thread.sleep(1000);
		}
	}

	public static VirtualDeviceConfigSpec[] reconfigVMNicConnection(
			VirtualMachine vm, HostOpaqueNetworkInfo opaqueNetworkInfo) {
		System.out.println("VM Name::" + vm.getName());
		try {
			VirtualDevice[] vdeviceArray = (VirtualDevice[]) vm
					.getPropertyByPath("config.hardware.device");
			List<VirtualDeviceConfigSpec> nicSpecAL = new ArrayList<VirtualDeviceConfigSpec>();
			for (int k = 0; k < vdeviceArray.length; k++) {
				Description vDetails = vdeviceArray[k].getDeviceInfo();
				if (vDetails.getLabel().contains("Network adapter")) {
					VirtualDeviceConfigSpec nicSpec = new VirtualDeviceConfigSpec();
					nicSpec.setOperation(VirtualDeviceConfigSpecOperation.edit);
					// Creating new Ethernet card
					VirtualEthernetCard nic = new VirtualPCNet32();
					nic = (VirtualEthernetCard) vdeviceArray[k];
					VirtualEthernetCardOpaqueNetworkBackingInfo opaqueNetworkBacking = new VirtualEthernetCardOpaqueNetworkBackingInfo();
					opaqueNetworkBacking.setOpaqueNetworkId(opaqueNetworkInfo
							.getOpaqueNetworkId());
					opaqueNetworkBacking.setOpaqueNetworkType(opaqueNetworkInfo
							.getOpaqueNetworkType());
					nic.setBacking(opaqueNetworkBacking);
					nicSpec.setDevice(nic);
					nicSpecAL.add(nicSpec);
				}
			}
			VirtualDeviceConfigSpec[] nicSpecArray = nicSpecAL
					.toArray(new VirtualDeviceConfigSpec[nicSpecAL.size()]);
			return nicSpecArray;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static void main(String[] args) throws Exception {
		String vmName = "VM1"; // SourceVC VM name
		String IPAddress = "10.10.10.10"; // vCenter IP/hostname
		String userName = "Administrator@vsphere.local";
		String passwd = "XXXXXXX";
		String destLSNetwork = "LogicalSwitch1"; // NSXT logicalswitch name(i.e,opaquenetwork name)
		try {
			ServiceInstance si = new ServiceInstance(new URL("https://"
					+ IPAddress + "/sdk"), userName, passwd, true);
			ReconfigVMToOpaquenetwork vmReconfig = new ReconfigVMToOpaquenetwork();
			vmReconfig.reconfigVMToOpaquenetwork(si, vmName, destLSNetwork);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
