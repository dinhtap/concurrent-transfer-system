package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.base.StorageSystem;
import cp2023.exceptions.*;

import java.util.*;
import java.util.concurrent.Semaphore;

public class StorageSystemImp implements StorageSystem {
    private final Map<DeviceId, Integer> deviceTotalSlots;
    // Max capacity of devices
    private final Map<DeviceId, LinkedList<WaitForSlot>> queueToDevices;
    // Queue do devices
    private final Map<DeviceId, Map<ComponentId, SlotStatus>> deviceSlots;
    // Taken slots in devices, free slots are not in this map
    private final Map<ComponentId, DeviceId> componentPlacement;
    // Where components are located on
    private final Set<ComponentId> beingOperatedOn;
    // Set of components being operated on
    private final Semaphore mutex;

    public StorageSystemImp (
            Map<DeviceId, Integer> deviceTotalSlots,
            Map<ComponentId, DeviceId> componentPlacement) {

        if (deviceTotalSlots == null || componentPlacement == null) {
            throw new IllegalArgumentException("One of given parameters is null");
        }

        Set<DeviceId> allDevices = deviceTotalSlots.keySet();
        Set<ComponentId> allComponents = componentPlacement.keySet();

        Map<DeviceId, Integer> componentCounter = new HashMap<>();
        deviceSlots = new HashMap<>();
        this.deviceTotalSlots = new HashMap<>();
        this.componentPlacement = new HashMap<>();
        queueToDevices = new HashMap<>();
        beingOperatedOn = new HashSet<>();
        mutex = new Semaphore(1, true);
        
        if (deviceTotalSlots.size() == 0) {
            throw new IllegalArgumentException("pusty system");
        }

        for (DeviceId device : allDevices) {
            if (device == null || deviceTotalSlots.get(device) == null) {
                throw new IllegalArgumentException("null device");
            }
            deviceSlots.put(device, new HashMap<>());
            componentCounter.put(device, 0);

            if (deviceTotalSlots.get(device) <= 0) {
                throw new IllegalArgumentException("device with 0 or less capacity");
            }
            this.deviceTotalSlots.put(device, deviceTotalSlots.get(device));
            queueToDevices.put(device, new LinkedList<>());
        }

        for (ComponentId component : allComponents) {
            if (component == null) {
                throw new IllegalArgumentException("null component");
            }
            DeviceId device = componentPlacement.get(component);
            if (device == null) {
                throw new IllegalArgumentException("Component assigned to a device with unknown capacity");
            }
            if (!allDevices.contains(device)) {
                throw new IllegalArgumentException("Component assigned to a device with unknown capacity");
            }

            Integer counter = componentCounter.get(device);
            counter++;
            if (counter > deviceTotalSlots.get(device)) {
                throw new IllegalArgumentException("Exceeded capacity of a device");
            }

            componentCounter.put(device, counter);
            deviceSlots.get(device).put(component, new SlotStatus(component));

            this.componentPlacement.put(component, componentPlacement.get(component));
        }
    }

    @Override
    public void execute(ComponentTransfer transfer) throws TransferException {
        if (transfer != null) {
            try {
                DeviceId sourceDevice = transfer.getSourceDeviceId();
                DeviceId destDevice = transfer.getDestinationDeviceId();
                ComponentId component = transfer.getComponentId();

                mutex.acquire();
                validateTransfer(transfer);
                beingOperatedOn.add(component);

                if (sourceDevice == null) {// add component
                    if (!transferToFreeSpace(transfer)) {
                        waitForSlot(transfer);
                    }
                } else if (destDevice == null) {// remove component
                    prepareTransfer(transfer);
                    performTransfer(transfer, null);
                } else {
                    if (!transferToFreeSpace(transfer)) {// move component
                        LinkedList<WaitForSlot> cycle = new LinkedList<>();
                        if (findCycle(sourceDevice, destDevice, cycle, new HashSet<>())) {
                            initiateCycle(cycle, transfer);
                        } else {
                            waitForSlot(transfer);
                        }
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            }
        }
    }

    private boolean transferToFreeSpace (ComponentTransfer transfer) throws InterruptedException {
        DeviceId destDevice = transfer.getDestinationDeviceId();
        ComponentId component = transfer.getComponentId();
        Map<ComponentId, SlotStatus> destinationSlots = deviceSlots.get(destDevice);
        boolean gotASlot = false;

        // If there is a free slot
        if (destinationSlots.size() < deviceTotalSlots.get(destDevice)) {
            SlotStatus thisSlot = new SlotStatus(component);
            destinationSlots.put(component, thisSlot);
            prepareTransfer(transfer);
            performTransfer(transfer, thisSlot);
            gotASlot = true;
        }
        else { // Finding a slot that is being free
            for (ComponentId componentId : destinationSlots.keySet()) {
                SlotStatus currentSlot = destinationSlots.get(componentId);
                if (currentSlot.beingFree) {
                    currentSlot.beingFree = false;
                    currentSlot.waiting = true;
                    gotASlot = true;

                    prepareTransfer(transfer);
                    currentSlot.sem.acquire();
                    performTransfer(transfer, currentSlot);

                    break;
                }
            }
        }
        return gotASlot;
    }

    boolean findCycle (DeviceId source, DeviceId finalDestination, LinkedList<WaitForSlot> cycle, Set<DeviceId> visitedDevices) {
        LinkedList<WaitForSlot> sourceDeviceQueue = queueToDevices.get(source);

        for (ListIterator<WaitForSlot> it = sourceDeviceQueue.listIterator(); it.hasNext();) {
            WaitForSlot currentTransferInQueue = it.next();

            if (currentTransferInQueue.source != null) {
                cycle.add(currentTransferInQueue);
                DeviceId sourceDevice = currentTransferInQueue.source;

                if (sourceDevice.equals(finalDestination)) {
                    it.remove();// remove this transfer from queue
                    return true;
                }
                else {
                    if (!visitedDevices.contains(sourceDevice) && findCycle(sourceDevice, finalDestination, cycle, visitedDevices)) {
                        it.remove();// remove this transfer from queue
                        return true;
                    }
                    else {
                        cycle.removeLast();
                        visitedDevices.add(sourceDevice);
                    }
                }
            }
        }
        return false;
    }

    void initiateCycle(LinkedList<WaitForSlot> cycle, ComponentTransfer closingTransfer) throws InterruptedException {
        DeviceId currentDevice = closingTransfer.getSourceDeviceId();
        ComponentId currentComp = closingTransfer.getComponentId();

        // Assign slots fot all transfers and start
        for (WaitForSlot currentTransferInCycle : cycle) {
            currentTransferInCycle.slot = deviceSlots.get(currentDevice).get(currentComp);
            currentTransferInCycle.slot.waiting = true;
            currentTransferInCycle.slot.beingFree = false;
            currentDevice = currentTransferInCycle.source;
            currentComp = currentTransferInCycle.component;
        }

        // Assign slot for the transfer closing cycle and start
        WaitForSlot lastTransfer = cycle.peekLast();
        SlotStatus mySlot = deviceSlots.get(lastTransfer.source).get(lastTransfer.component);
        mySlot.waiting = true;
        mySlot.beingFree = false;

        for (WaitForSlot transferInCycle : cycle) {
            transferInCycle.sem.release();
        }

        mutex.release();

        prepareTransfer(closingTransfer);
        mySlot.sem.acquire();
        performTransfer(closingTransfer, mySlot);
    }

    void waitForSlot (ComponentTransfer transfer) throws InterruptedException {
        DeviceId sourceDevice = transfer.getSourceDeviceId();
        DeviceId destDevice = transfer.getDestinationDeviceId();
        ComponentId component = transfer.getComponentId();

        WaitForSlot waiting = new WaitForSlot(sourceDevice, component);
        Queue<WaitForSlot> queue = queueToDevices.get(destDevice);
        queue.add(waiting);
        mutex.release();

        waiting.sem.acquire();
        prepareTransfer(transfer);
        waiting.slot.sem.acquire();
        performTransfer(transfer, waiting.slot);
    }

    void prepareTransfer(ComponentTransfer transfer) throws InterruptedException{
        DeviceId sourceDevice = transfer.getSourceDeviceId();
        ComponentId component = transfer.getComponentId();

        // Freeing current slot, make it available for other transfers
        if (sourceDevice != null) {
            SlotStatus slot = deviceSlots.get(sourceDevice).get(component);
            Queue<WaitForSlot> queue = queueToDevices.get(sourceDevice);
            if (!queue.isEmpty() && !slot.waiting) {
                WaitForSlot waiting = queue.remove();
                slot.waiting = true;
                waiting.slot = slot;
                waiting.sem.release();// Inheriting critical section
            }
            else if (!slot.waiting) {
                slot.beingFree = true;
                mutex.release();
            }
        }
        else {
            mutex.release();
        }

        transfer.prepare();

        // Done preparing, awake the transfer waiting to perform on this slot
        if (sourceDevice != null) {
            mutex.acquire();
            SlotStatus slot = deviceSlots.get(sourceDevice).get(component);
            if (slot.waiting) {
                slot.sem.release();
            }
            else {
                deviceSlots.get(sourceDevice).remove(component);
            }
            mutex.release();
        }
    }
    void performTransfer(ComponentTransfer transfer, SlotStatus slot) {
        DeviceId destDevice = transfer.getDestinationDeviceId();
        ComponentId component = transfer.getComponentId();
        try {
            if (slot != null) {// Acquire the slot for this transfer
                mutex.acquire();
                deviceSlots.get(destDevice).remove(slot.component);
                slot.waiting = false;
                slot.beingFree = false;
                slot.component = component;
                deviceSlots.get(destDevice).put(component, slot);
                mutex.release();
            }

            transfer.perform();

            // Register changes
            mutex.acquire();
            if (destDevice == null) {
                componentPlacement.remove(component);
            }
            else {
                componentPlacement.put(component, destDevice);
            }
            beingOperatedOn.remove(component);
            mutex.release();
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    private void validateTransfer (ComponentTransfer transfer) throws TransferException {
        DeviceId sourceDevice = transfer.getSourceDeviceId();
        DeviceId destDevice = transfer.getDestinationDeviceId();
        ComponentId component = transfer.getComponentId();

        if (sourceDevice == null && destDevice == null) {
            mutex.release();
            throw new IllegalTransferType(component);
        }

        if (sourceDevice != null) {
            if (!deviceTotalSlots.containsKey(sourceDevice)) {
                mutex.release();
                throw new DeviceDoesNotExist(sourceDevice);
            }

            if (!componentPlacement.containsKey(component) || !componentPlacement.get(component).equals(sourceDevice)) {
                mutex.release();
                throw new ComponentDoesNotExist(component, sourceDevice);
            }
        }
        else {
            if (componentPlacement.containsKey(component)) {
                mutex.release();
                if (destDevice.equals(componentPlacement.get(component))) {
                    throw new ComponentAlreadyExists(component, destDevice);
                }
                throw new ComponentAlreadyExists(component);
            }
        }

        if (destDevice != null) {
            if (!deviceTotalSlots.containsKey(destDevice)) {
                mutex.release();
                throw new DeviceDoesNotExist(destDevice);
            }

            if (componentPlacement.containsKey(component) &&
                    componentPlacement.get(component).equals(destDevice)) {
                mutex.release();
                throw new ComponentDoesNotNeedTransfer(component, destDevice);
            }
        }

        if (beingOperatedOn.contains(component)) {
            mutex.release();
            throw new ComponentIsBeingOperatedOn(component);
        }
    }

    private static class SlotStatus {
        private ComponentId component;
        private boolean beingFree;
        private boolean waiting;
        private final Semaphore sem;// Semaphore waiting to perform on this slot

        public SlotStatus(ComponentId component) {
            this.component = component;
            beingFree = false;
            waiting = false;
            sem = new Semaphore(0);
        }

    }

    private static class WaitForSlot {
        private final Semaphore sem;// Semaphore waiting to start transfer
        private SlotStatus slot;// Slot given to start tranfer
        private final DeviceId source;
        private final ComponentId component;

        public WaitForSlot(DeviceId source, ComponentId component) {
            this.source = source;
            this.component = component;
            sem = new Semaphore(0);
            slot = null;
        }
    }

}
