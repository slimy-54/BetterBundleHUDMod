package betterbundle.shulker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import betterbundle.util.BundleContentsHelper;

/** Performs silent (no visible GUI flash) shulker-box operations by opening the box via
 *  quickshulker, waiting for the container to open, sending click packets against it,
 *  then closing. Panel interactions are locked while {@link #busy}.
 *
 *  Insertion policy (as requested):
 *  - stackable item  -> placed into a bundle inside the box if one can fit it
 *  - non-stackable   -> placed into an empty box slot
 *  - no empty slot   -> falls back to a bundle inside the box
 */
public final class ShulkerBoxOps {

    private static final Logger LOGGER = LoggerFactory.getLogger("betterbundle.ShulkerBoxOps");
    private static final boolean DEBUG = true;

    private ShulkerBoxOps() {}

    public enum Op { NONE, TAKE_BOX, TAKE_INNER, DEPOSIT }

    private static volatile Op pendingOp = Op.NONE;
    private static int invIndex;
    private static int boxSlotIndex;
    private static int innerIndex;
    private static int containerIdWhenOpened = -1;

    /** Whether a shulker-box operation is currently in flight (locks panel interactions). */
    public static boolean isBusy() { return pendingOp != Op.NONE; }

    // ---- requesters (client-thread) ------------------------------------

    /** Take the whole stack at the given box slot out into the player inventory. */
    public static void takeFromBox(int inventoryIndex, int boxSlot) {
        if (!ShulkerSupport.isLoaded() || pendingOp != Op.NONE) return;
        pendingOp = Op.TAKE_BOX;
        invIndex = inventoryIndex;
        boxSlotIndex = boxSlot;
        innerIndex = -1;
        sendOpenAndWait(inventoryIndex);
    }

    /** Take the given inner item out of a bundle located at boxSlot inside the box. */
    public static void takeFromInnerBundle(int inventoryIndex, int boxSlot, int bundleInnerIndex) {
        if (!ShulkerSupport.isLoaded() || pendingOp != Op.NONE) return;
        pendingOp = Op.TAKE_INNER;
        invIndex = inventoryIndex;
        boxSlotIndex = boxSlot;
        innerIndex = bundleInnerIndex;
        sendOpenAndWait(inventoryIndex);
    }

    /** Deposit the cursor stack into the box following the insertion policy. */
    public static void deposit(int inventoryIndex) {
        if (!ShulkerSupport.isLoaded() || pendingOp != Op.NONE) return;
        pendingOp = Op.DEPOSIT;
        invIndex = inventoryIndex;
        boxSlotIndex = -1;
        innerIndex = -1;
        sendOpenAndWait(inventoryIndex);
    }

    private static void sendOpenAndWait(int inventoryIndex) {
        ShulkerSupport.openAtInventorySlot(inventoryIndex);
    }

    // ---- completion hook (client-thread, called by mixin after open) ----

    /** Invoked by the container-open mixin once the box menu is active. */
    public static void onBoxMenuOpened(AbstractContainerMenu menu) {
        if (pendingOp == Op.NONE || !(menu instanceof ShulkerBoxMenu)) return;
        containerIdWhenOpened = menu.containerId;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ClientPacketListener conn = mc.getConnection();
        if (player == null || conn == null) {
            abort();
            return;
        }
        if (DEBUG) LOGGER.info("[betterbundle] onBoxMenuOpened op={} containerId={} stateId={} ({} slots)",
                pendingOp, containerIdWhenOpened, menu.getStateId(), menu.slots.size());
        try {
            switch (pendingOp) {
                case TAKE_BOX -> runTakeBox(player, conn);
                case TAKE_INNER -> runTakeInner(player, conn);
                case DEPOSIT -> runDeposit(player, conn);
                default -> abort();
            }
        } catch (Throwable t) {
            if (DEBUG) LOGGER.error("[betterbundle] op {} threw", pendingOp, t);
            abort();
        } finally {
            if (pendingOp != Op.NONE) {
                conn.send(new ServerboundContainerClosePacket(containerIdWhenOpened));
            }
            pendingOp = Op.NONE;
        }
    }

    private static void runTakeBox(Player player, ClientPacketListener conn) {
        int containerId = containerIdWhenOpened;
        // pick up the whole stack from the box slot into cursor
        sendPick(containerId, boxSlotIndex, (byte) 0, player);
        // drop cursor into the first free player slot of the opened menu
        int emptySlot = findEmptyPlayerSlot(player);
        if (emptySlot >= 0) {
            sendPick(containerId, emptySlot, (byte) 0, player);
        }
    }

    private static void runTakeInner(Player player, ClientPacketListener conn) {
        int containerId = containerIdWhenOpened;
        // select the inner bundle item, then pull one into cursor
        conn.send(new ServerboundSelectBundleItemPacket(boxSlotIndex, innerIndex));
        sendPick(containerId, boxSlotIndex, (byte) 1, player);
        int emptySlot = findEmptyPlayerSlot(player);
        if (emptySlot >= 0) {
            sendPick(containerId, emptySlot, (byte) 0, player);
        }
    }

    private static void runDeposit(Player player, ClientPacketListener conn) {
        int containerId = containerIdWhenOpened;
        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor == null || cursor.isEmpty()) { abort(); return; }

        boolean stackable = cursor.getMaxStackSize() > 1;
        if (stackable) {
            // stackable -> try a bundle inside the box first
            int bundleSlot = findBundleInBox(player, cursor);
            if (bundleSlot >= 0) {
                sendPick(containerId, bundleSlot, (byte) 0, player);
                return;
            }
        }
        // non-stackable (or no fitting bundle) -> empty box slot
        int emptyBoxSlot = findEmptyBoxSlot(player);
        if (emptyBoxSlot >= 0) {
            sendPick(containerId, emptyBoxSlot, (byte) 0, player);
            return;
        }
        // box full -> fall back to a bundle inside the box
        int bundleSlot = findBundleInBox(player, cursor);
        if (bundleSlot >= 0) {
            sendPick(containerId, bundleSlot, (byte) 0, player);
        } else {
            abort();
        }
    }

    /** Send a vanilla PICKUP click through the canonical client path so the client
     *  menu (slots/carried/changedSlots/stateId) stays in sync with the server. */
    private static void sendPick(int containerId, int slot, byte button, Player player) {
        if (DEBUG) LOGGER.info("[betterbundle] click containerId={} slot={} button={} (stateId {})",
                containerId, slot, button, player.containerMenu.getStateId());
        Minecraft.getInstance().gameMode.handleContainerInput(
                containerId, slot, button, ContainerInput.PICKUP, player);
    }

    private static int findEmptyBoxSlot(Player player) {
        for (int i = 0; i < 27; i++) {
            Slot slot = player.containerMenu.getSlot(i);
            if (slot != null && !slot.hasItem()) return i;
        }
        return -1;
    }

    private static int findBundleInBox(Player player, ItemStack toInsert) {
        for (int i = 0; i < 27; i++) {
            Slot slot = player.containerMenu.getSlot(i);
            if (slot != null && slot.hasItem()) {
                ItemStack stack = slot.getItem();
                if (BundleContentsHelper.isBundle(stack)
                        && BundleContentsHelper.canFitItem(stack, toInsert)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findEmptyPlayerSlot(Player player) {
        for (int pass = 0; pass < 2; pass++) {
            int min = (pass == 0) ? 9 : 0;
            int max = (pass == 0) ? 36 : 9;
            for (Slot slot : player.containerMenu.slots) {
                if (slot.container == player.getInventory() && !slot.hasItem()) {
                    int idx = slot.getContainerSlot();
                    if (idx >= min && idx < max) return slot.index;
                }
            }
        }
        return -1;
    }

    private static void abort() {
        pendingOp = Op.NONE;
    }
}
