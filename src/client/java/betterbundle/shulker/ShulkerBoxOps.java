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

    // Deferred (tick-based) box execution. A box menu arrives from the server with a
    // zero stateId and empty slots; clicking it immediately races the server's content
    // sync (the click gets rejected/desynced). We wait a few ticks until it is synced,
    // then perform the clicks, close the box, and move on.
    private static AbstractContainerMenu pendingBoxMenu = null;
    private static int deferTicks = 0;
    private static final int SYNC_DELAY_TICKS = 2;
    private static net.minecraft.client.gui.screens.Screen previousScreen = null;

    /** When >= 0, after the compose chain finishes a "grab to cursor" click is pending on
     *  this player inventory index (arms the final pickup once back on the player screen). */
    private static volatile int pendingPickupInvIndex = -1;

    private record ComposeJob(int shulkerInvIndex, List<ComposeCommand> takes) {}

    /** Whether a shulker-box operation is currently in flight (locks panel interactions). */
    public static boolean isBusy() { return pendingOp != Op.NONE; }

    /** Whether a final cursor-grab is armed (take flow only). */
    public static boolean hasPendingPickup() { return pendingPickupInvIndex >= 0; }

    private static int pendingPickupMisses = 0;

    /** Perform the armed grab (move the composed stack from {@code pendingPickupInvIndex}
     *  onto the cursor) exactly once. Returns true if it grabbed. Safe to call anywhere.
     *  If the cache slot is not filled yet (the server has not applied the compose clicks),
     *  keeps itself armed so the next render can retry instead of dropping the item into
     *  the backpack forever. */
    public static boolean doPendingPickup(Player player) {
        if (!hasPendingPickup() || isBusy() || player == null || player.containerMenu == null) return false;
        int invIndex = pendingPickupInvIndex;
        int slot = resolveInventoryIndexToMenuSlot(player, invIndex);
        if (slot < 0) { pendingPickupInvIndex = -1; return false; }
        net.minecraft.world.inventory.Slot target = player.containerMenu.getSlot(slot);
        if (target == null || target.getItem() == null || target.getItem().isEmpty()) {
            if (++pendingPickupMisses > 20) pendingPickupInvIndex = -1;
            return false;
        }
        pendingPickupMisses = 0;
        if (DEBUG) LOGGER.info("[betterbundle] pickup cache slotInventoryIdx={} menuSlot={}", invIndex, slot);
        Minecraft.getInstance().gameMode.handleContainerInput(
                player.containerMenu.containerId, slot, (byte) 0, ContainerInput.PICKUP, player);
        pendingPickupInvIndex = -1;
        return true;
    }

    private static void armPendingPickup() {
        if (destInventoryIndex >= 0) pendingPickupInvIndex = destInventoryIndex;
    }

    /** Arm a cursor-grab for the given player inventory index once back on a normal screen.
     *  Used by the pure player-bag take path (no silent box involved). */
    public static void armPickupFor(int inventoryIndex) {
        if (inventoryIndex < 0) return;
        pendingPickupInvIndex = inventoryIndex;
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

    /** Remember the screen we are taking over from, so we can restore it afterwards. */
    public static void setPreviousScreen(net.minecraft.client.gui.screens.Screen screen) {
        previousScreen = screen;
    }

    /** Invoked by the container-open mixin once the box menu is active. We do NOT click
     *  immediately: the box open event arrives before the server has synced the menu's
     *  slot contents / stateId (it is still 0 here). We remember the menu and let
     *  {@link #tick()} run the operation once the box has stabilised. */
    public static void onBoxMenuOpened(AbstractContainerMenu menu) {
        if (pendingOp == Op.NONE || !(menu instanceof ShulkerBoxMenu)) return;
        containerIdWhenOpened = menu.containerId;
        pendingBoxMenu = menu;
        deferTicks = SYNC_DELAY_TICKS;
        if (DEBUG) LOGGER.info("[betterbundle] box open queued op={} containerId={} stateId={} ({} slots)",
                pendingOp, containerIdWhenOpened, menu.getStateId(), menu.slots.size());
    }

    /** End-of-client-tick driver. Once a queued box menu has had time to sync, run its
     *  click operation, close the box, then restore the screen / continue the chain. */
    public static void tick() {
        if (pendingOp == Op.NONE || pendingBoxMenu == null) return;
        if (deferTicks > 0) { deferTicks--; return; }
        AbstractContainerMenu menu = pendingBoxMenu;
        pendingBoxMenu = null;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        ClientPacketListener conn = mc.getConnection();
        if (player == null || conn == null || player.containerMenu != menu) {
            pendingOp = Op.NONE;
            return;
        }
        boolean wasCompose = pendingOp == Op.COMPOSE;
        try {
            if (wasCompose) {
                runComposeBox(player, conn);
            } else {
                runDeposit(player, conn);
            }
        } catch (Throwable t) {
            if (DEBUG) LOGGER.error("[betterbundle] op {} threw while ticking", pendingOp, t);
        } finally {
            closeBoxContainer(player, conn);
            boolean more = wasCompose && nextInvToOpen >= 0;
            if (more) {
                int next = nextInvToOpen;
                nextInvToOpen = -1;
                ShulkerSupport.openAtInventorySlot(next);
            } else {
                pendingOp = Op.NONE;
                if (wasCompose) armPendingPickup();
                restoreScreenAfterOp(mc, player);
            }
        }
    }

    private static void restoreScreenAfterOp(Minecraft mc, Player player) {
        if (mc == null) return;
        // A finished take-compose returns to the player inventory screen so the pending
        // cursor-grab (InventoryScreenMixin) can immediately collect the cache slot.
        mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(player));
        previousScreen = null;
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
                // bundle inside box: pull one selected item onto the cursor, then drop it
                // into the destination slot. The cursor MUST be empty when we right-click
                // the bundle: with a carried stack, the bundle's own override is skipped
                // and a plain swap would pick up the whole bundle. Left-button deposit
                // (button 0) empties the cursor fully, so it stays clean across iterations.
                for (int i = 0; i < take; i++) {
                    if (!player.containerMenu.getCarried().isEmpty() && destSlot >= 0) {
                        sendPick(containerId, destSlot, (byte) 0, player);
                    }
                    if (!player.containerMenu.getCarried().isEmpty()) break;
                    player.containerMenu.setSelectedBundleItemIndex(t.boxSlot(), t.innerIndex());
                    conn.send(new ServerboundSelectBundleItemPacket(t.boxSlot(), t.innerIndex()));
                    sendPick(containerId, t.boxSlot(), (byte) 1, player);
                    if (destSlot >= 0 && !player.containerMenu.getCarried().isEmpty()) {
                        sendPick(containerId, destSlot, (byte) 0, player);
                    }
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

        // Only deposit into a bundle inside the box - empty box slots do NOT count.
        int targetSlot = findBundleInBox(player, cursor);
        if (targetSlot < 0) { pendingOp = Op.NONE; return; }
        sendPick(containerId, targetSlot, (byte) 0, player);
    }

    /** Send a vanilla PICKUP click through the canonical client path so the client
     *  menu (slots/carried/changedSlots/stateId) stays in sync with the server. */
    private static void sendPick(int containerId, int slot, byte button, Player player) {
        if (DEBUG) LOGGER.info("[betterbundle] click containerId={} slot={} button={} (stateId {})",
                containerId, slot, button, player.containerMenu.getStateId());
        Minecraft.getInstance().gameMode.handleContainerInput(
                containerId, slot, button, ContainerInput.PICKUP, player);
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