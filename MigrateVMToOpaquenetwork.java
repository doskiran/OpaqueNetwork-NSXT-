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
import com.vmware.vim25.VirtualMachineRelocateSpec;
import com.vmware.vim25.VirtualPCNet32;
import com.vmware.vim25.mo.ComputeResource;
import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.HostSystem;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;

public class MigrateVMToOpaquenetwork {

	public boolean relocateVMToOpaquenetwork(ServiceInstance si, String vmName,
			String destHost, String destLSNetwork) {
		boolean status = false;
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
			HostSystem hs = (HostSystem) new InventoryNavigator(rootFolder)
					.searchManagedEntity("HostSystem", destHost);
			ComputeResource cr = (ComputeResource) hs.getParent();			
			HostOpaqueNetworkInfo[] hopArr = hs.getConfig().getNetwork()
					.getOpaqueNetwork();
			for (HostOpaqueNetworkInfo hop : hopArr) {
				if (hop.getOpaqueNetworkName().equals(destLSNetwork)) {
					opaqueNetworkInfo = hop;
				}
			}
			VirtualMachineRelocateSpec vmRelSpec = new VirtualMachineRelocateSpec();
			ManagedObjectReference mor_host = new ManagedObjectReference();
			mor_host.setType("HostSystem");
			mor_host.setVal(hs.getMOR().getVal());
			vmRelSpec.setHost(mor_host);
			vmRelSpec.setDeviceChange(reconfigVMNicConnection(vm,
					opaqueNetworkInfo));
			ManagedObjectReference mor_pool = new ManagedObjectReference();
			mor_pool.setType("ResourcePool");
			mor_pool.setVal(cr.getResourcePool().getMOR().getVal());
			vmRelSpec.setPool(mor_pool);
			System.out.println("VM migration started...");
			Task task = vm.relocateVM_Task(vmRelSpec);
			TaskInfo ti = waitFor(task);
			if (ti.getState() == TaskInfoState.error) {
				System.out.println("VM migration failed" + vmName);
				return false;
			}
			System.out.println("VM migration success");
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
		String destHost = "10.10.10.20"; // Destination ESXi hostname to migrate VM
		String destLSNetwork = "LogicalSwitch1"; // NSXT logicalswitch name(i.e,opaquenetwork name)
		try {
			ServiceInstance si = new ServiceInstance(new URL("https://"
					+ IPAddress + "/sdk"), userName, passwd, true);
			MigrateVMToOpaquenetwork vmMigrate = new MigrateVMToOpaquenetwork();
			vmMigrate.relocateVMToOpaquenetwork(si, vmName, destHost,
					destLSNetwork);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
