package betterbundle.shulker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import betterbundle.util.BundleContentsHelper;

/** Performs silent (no visible GUI flash) shulker-box operations by opening boxes via
 *  quickshulker, waiting for each container to open, sending click packets against it,
 *  then closing. Panel interactions are locked while {@link #isBusy()}.
 *
 *  Supports a {@code COMPOSE} chain that takes items across several boxes (and, for
 *  player-inventory bags, across bags) into a single targeted player inventory slot,
 *  so a full stack can be auto-composed from many sources. Boxes are opened one after
 *  another; the chain continues until the target count is met or sources are exhausted.
 */
public final class ShulkerBoxOps {

    private static final Logger LOGGER = LoggerFactory.getLogger("betterbundle.ShulkerBoxOps");
    private static final boolean DEBUG = true;

    private ShulkerBoxOps() {}

    public enum Op { NONE, COMPOSE, DEPOSIT }

    /** One planned take command for a source inside a box. */
    public record ComposeCommand(int shulkerInvIndex, int boxSlot, int innerIndex, boolean isInner, int count) {}

    private static volatile Op pendingOp = Op.NONE;
    private static int destInventoryIndex = -1;      // targeted player inventory index (0-35)
    private static int composeLeft = 0;              // items still needed to reach the target
    private static final ArrayDeque<ComposeJob> composeJobs = new ArrayDeque<>();
    private static int nextInvToOpen = -1;           // -1 = chain finished
    private static int containerIdWhenOpened = -1;

    /** When >= 0, after the compose chain finishes a "grab to cursor" click is pending on
     *  this player inventory index (arms the final pickup once back on the player screen). */
    private static volatile int pendingPickupInvIndex = -1;

    private record ComposeJob(int shulkerInvIndex, List<ComposeCommand> takes) {}

    /** Whether a shulker-box operation is currently in flight (locks panel interactions). */
    public static boolean isBusy() { return pendingOp != Op.NONE; }

    /** Whether a final cursor-grab is armed (take flow only). */
    public static boolean hasPendingPickup() { return pendingPickupInvIndex >= 0; }

    /** Perform the armed grab (move the composed stack from {@code pendingPickupInvIndex}
     *  onto the cursor) exactly once. Returns true if it grabbed. Safe to call anywhere. */
    public static boolean doPendingPickup(Player player) {
        if (!hasPendingPickup() || isBusy() || player == null || player.containerMenu == null) return false;
        int invIndex = pendingPickupInvIndex;
        int slot = resolveInventoryIndexToMenuSlot(player, invIndex);
        if (slot < 0) { pendingPickupInvIndex = -1; return false; }
        if (DEBUG) LOGGER.info("[betterbundle] pickup cache slotInventoryIdx={} menuSlot={}", invIndex, slot);
        Minecraft.getInstance().gameMode.handleContainerInput(
                player.containerMenu.containerId, slot, (byte) 0, ContainerInput.PICKUP, player);
        pendingPickupInvIndex = -1;
        return true;
    }

    private static void armPendingPickup() {
        if (destInventoryIndex >= 0) pendingPickupInvIndex = destInventoryIndex;
    }

    // ---- requester (client-thread) ------------------------------------

    /** Start auto-composing {@code targetCount} items from the given sources into the
     *  player inventory slot {@code destInventoryIndex}. Player-bundle sources must be
     *  consumed BEFORE calling this (they use the open-inventory menu); box sources are
     *  opened sequentially here. */
    public static void startCompose(List<ComposeCommand> commands, int targetCount, int destInventoryIndex) {
        if (!ShulkerSupport.isLoaded() || pendingOp != Op.NONE) return;
        if (commands.isEmpty() || targetCount <= 0 || destInventoryIndex < 0) return;

        pendingOp = Op.COMPOSE;
        destInventoryIndex = destInventoryIndex;
        composeLeft = targetCount;
        composeJobs.clear();
        nextInvToOpen = -1;

        // group by box (preserve first-seen order) so each box is opened only once
        Map<Integer, List<ComposeCommand>> byBox = new LinkedHashMap<>();
        for (ComposeCommand c : commands) {
            byBox.computeIfAbsent(c.shulkerInvIndex(), k -> new ArrayList<>()).add(c);
        }
        byBox.forEach((inv, takes) -> composeJobs.add(new ComposeJob(inv, takes)));

        if (!composeJobs.isEmpty()) {
            openComposeNext();
        } else {
            pendingOp = Op.NONE;
        }
    }

    private static void openComposeNext() {
        ShulkerSupport.openAtInventorySlot(composeJobs.peekFirst().shulkerInvIndex());
    }

    /** Deposit the cursor stack into the box following the insertion policy. */
    public static void deposit(int inventoryIndex) {
        if (!ShulkerSupport.isLoaded() || pendingOp != Op.NONE) return;
        pendingOp = Op.DEPOSIT;
        destInventoryIndex = inventoryIndex;
        ShulkerSupport.openAtInventorySlot(inventoryIndex);
    }

    // ---- completion hook (client-thread, called by mixin after open) ----

    /** Invoked by the container-open mixin once the box menu is active. */
    public static void onBoxMenuOpened(AbstractContainerMenu menu) {
        if (pendingOp == Op.NONE || !(menu instanceof ShulkerBoxMenu)) return;
        containerIdWhenOpened = menu.containerId;
        boolean wasCompose = pendingOp == Op.COMPOSE;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ClientPacketListener conn = mc.getConnection();
        if (player == null || conn == null) {
            pendingOp = Op.NONE;
            return;
        }
        if (DEBUG) LOGGER.info("[betterbundle] onBoxMenuOpened op={} containerId={} stateId={} ({} slots)",
                pendingOp, containerIdWhenOpened, menu.getStateId(), menu.slots.size());
        try {
            if (wasCompose) {
                runComposeBox(player, conn);
            } else {
                runDeposit(player, conn);
            }
        } catch (Throwable t) {
            if (DEBUG) LOGGER.error("[betterbundle] op {} threw", pendingOp, t);
            pendingOp = Op.NONE;
        } finally {
            // Properly close the box on the client side: resets player.containerMenu to the
            // player inventory menu and sends the close packet. Sending a raw close packet
            // alone leaves the client stuck in the (invisible) box menu -> freeze + the
            // cursor-grab would target a stale container.
            closeBoxContainer(player, conn);
            boolean more = wasCompose && nextInvToOpen >= 0;
            if (!more) {
                pendingOp = Op.NONE;
                if (wasCompose && composeJobs.isEmpty()) armPendingPickup();
            } else {
                // keep busy; open the next box after this one is closed
                int next = nextInvToOpen;
                nextInvToOpen = -1;
                ShulkerSupport.openAtInventorySlot(next);
            }
        }
    }

    private static void closeBoxContainer(Player player, ClientPacketListener conn) {
        try {
            // Reset client menu to the player inventory menu, then send the close packet.
            // Player.closeContainer() is protected, but in Mojmap it is simply
            // `containerMenu = inventoryMenu` (both public fields), so we assign directly.
            // The raw packet alone leaves the client stuck in the (invisible) box menu
            // -> freeze + stale cursor-grab.
            if (player != null) player.containerMenu = player.inventoryMenu;
        } catch (Throwable t) {
            if (DEBUG) LOGGER.warn("[betterbundle] resetting client menu failed", t);
        }
        if (conn != null) {
            conn.send(new ServerboundContainerClosePacket(containerIdWhenOpened));
        }
    }

    /** Run one step of the compose chain against the currently-open box. */
    private static void runComposeBox(Player player, ClientPacketListener conn) {
        ComposeJob job = composeJobs.pollFirst();
        if (job == null) return;
        int containerId = containerIdWhenOpened;
        int destSlot = resolveInventoryIndexToMenuSlot(player, destInventoryIndex);
        for (ComposeCommand t : job.takes()) {
            if (composeLeft <= 0) break;
            int take = Math.min(t.count(), composeLeft);
            if (take <= 0) continue;
            if (t.isInner()) {
                // bundle inside box: pull one item onto the cursor, then drop it into the
                // destination slot (one at a time so the dest stack accumulates safely)
                for (int i = 0; i < take; i++) {
                    conn.send(new ServerboundSelectBundleItemPacket(t.boxSlot(), t.innerIndex()));
                    sendPick(containerId, t.boxSlot(), (byte) 1, player);
                    if (destSlot >= 0) sendPick(containerId, destSlot, (byte) 1, player);
                }
            } else {
                // direct box slot: pick whole slot, drop `take` into dest, put remainder back
                sendPick(containerId, t.boxSlot(), (byte) 0, player);
                if (destSlot >= 0) {
                    for (int i = 0; i < take; i++) {
                        sendPick(containerId, destSlot, (byte) 1, player);
                    }
                }
                sendPick(containerId, t.boxSlot(), (byte) 0, player);
            }
            composeLeft -= take;
        }
        nextInvToOpen = !composeJobs.isEmpty() && composeLeft > 0
                ? composeJobs.peekFirst().shulkerInvIndex() : -1;
    }

    private static void runDeposit(Player player, ClientPacketListener conn) {
        int containerId = containerIdWhenOpened;
        net.minecraft.world.item.ItemStack cursor = player.containerMenu.getCarried();
        if (cursor == null || cursor.isEmpty()) { pendingOp = Op.NONE; return; }

        // Always prefer depositing into a bundle inside the box first, then an empty slot.
        int targetSlot = findBundleInBox(player, cursor);
        if (targetSlot < 0) targetSlot = findEmptyBoxSlot(player);
        if (targetSlot < 0) targetSlot = findBundleInBox(player, cursor);
        if (targetSlot >= 0) {
            sendPick(containerId, targetSlot, (byte) 0, player);
        } else {
            pendingOp = Op.NONE;
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
            net.minecraft.world.inventory.Slot slot = player.containerMenu.getSlot(i);
            if (slot != null && !slot.hasItem()) return i;
        }
        return -1;
    }

    private static int findBundleInBox(Player player, net.minecraft.world.item.ItemStack toInsert) {
        for (int i = 0; i < 27; i++) {
            net.minecraft.world.inventory.Slot slot = player.containerMenu.getSlot(i);
            if (slot != null && slot.hasItem()) {
                net.minecraft.world.item.ItemStack stack = slot.getItem();
                if (BundleContentsHelper.isBundle(stack)
                        && BundleContentsHelper.canFitItem(stack, toInsert)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Map a player inventory index (0-35) to its slot index in the CURRENT open menu. */
    private static int resolveInventoryIndexToMenuSlot(Player player, int inventoryIndex) {
        for (net.minecraft.world.inventory.Slot s : player.containerMenu.slots) {
            if (s.container == player.getInventory() && s.getContainerSlot() == inventoryIndex) {
                return s.index;
            }
        }
        return -1;
    }
}