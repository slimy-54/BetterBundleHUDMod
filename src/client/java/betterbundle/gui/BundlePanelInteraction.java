package betterbundle.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import betterbundle.shulker.ShulkerBoxOps;
import betterbundle.shulker.ShulkerSupport;
import betterbundle.util.BundleContentsHelper;

public final class BundlePanelInteraction {

    private static final int GLFW_MOD_SHIFT = 0x1;

    private BundlePanelInteraction() {}

    private static int gridX(int leftPos) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        return panelX + BundlePanelRenderer.PADDING
                + BundlePanelRenderer.CAT_BAR_WIDTH + 2
                + BundlePanelRenderer.SCROLL_BAR_WIDTH + 2;
    }

    private static int gridY(int topPos) {
        return topPos + BundlePanelRenderer.SEARCH_BAR_HEIGHT + 3 + BundlePanelRenderer.PADDING;
    }

    private static BundlePanelRenderer.FlatItem getClickedItem(double mouseX, double mouseY,
                                                                int leftPos, int topPos) {
        List<BundlePanelRenderer.BundleSlotEntry> bundles = BundlePanelRenderer.getBundles();
        if (bundles.isEmpty() && !BundlePanelRenderer.hasAnyContent()) return null;

        List<BundlePanelRenderer.FlatItem> allItems = BundlePanelRenderer.buildFlatItemList(bundles);
        if (allItems.isEmpty()) return null;

        // Use filtered items to match rendered panel
        List<BundlePanelRenderer.FlatItem> items = BundlePanelRenderer.filterItems(allItems, BundlePanelRenderer.searchQuery);
        if (items.isEmpty()) return null;

        int gx = gridX(leftPos);
        int gy = gridY(topPos);

        int relX = (int) mouseX - gx;
        int relY = (int) mouseY - gy;

        int col = relX / (BundlePanelRenderer.SLOT_SIZE + BundlePanelRenderer.SLOT_SPACING);
        int row = relY / (BundlePanelRenderer.SLOT_SIZE + BundlePanelRenderer.SLOT_SPACING);

        if (col < 0 || col >= BundlePanelRenderer.COLUMNS) return null;
        if (row < 0 || row >= BundlePanelRenderer.VISIBLE_ROWS) return null;

        int flatIndex = (BundlePanelRenderer.getScrollOffset() + row) * BundlePanelRenderer.COLUMNS + col;
        if (flatIndex >= items.size()) return null;
        return items.get(flatIndex);
    }

    public static boolean handlePanelClick(double mouseX, double mouseY, int button, int modifiers,
                                            int leftPos, int topPos,
                                            net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        BundlePanelRenderer.FlatItem clicked = getClickedItem(mouseX, mouseY, leftPos, topPos);
        if (clicked == null) return false;

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return false;
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        // Take up to one full stack (the item's real max size) or exactly 1.
        // We never exceed the item's max stack size: any overflow stays in the bags/boxes.
        int targetCount = (button == 0) ? clicked.stack().getMaxStackSize() : 1;
        if (targetCount <= 0) return true;

        // Everything is routed through a single empty player-inventory "cache" slot so the
        // composed stack is finally grabbed back onto the cursor once we are on a normal screen.
        int destInvIndex = findEmptyPlayerInvIndex(player);
        if (destInvIndex < 0) return true;          // no room to compose
        int containerId = player.containerMenu.containerId;
        int destMenuSlot = resolveInvIndexToMenuSlot(player, destInvIndex);
        int remaining = targetCount;

        // Player-inventory bag sources: take from each bag into the cache slot.
        // One item at a time (select the bundle slot, pull it onto the cursor, drop into cache)
        // so the cache stack accumulates safely without exceeding the item's max stack size.
        for (BundlePanelRenderer.Source s : clicked.sources()) {
            if (s.type() != BundlePanelRenderer.PanelItemSource.PLAYER_BUNDLE) continue;
            if (remaining <= 0) break;
            int take = Math.min(remaining, s.count());
            for (int i = 0; i < take; i++) {
                // Record the selected inner index on the menu, then pull the exact item.
                player.containerMenu.setSelectedBundleItemIndex(s.bundleSlot(), s.itemIndex());
                connection.send(new ServerboundSelectBundleItemPacket(s.bundleSlot(), s.itemIndex()));
                pick(player, containerId, s.bundleSlot(), (byte) 1);
                if (destMenuSlot >= 0) pick(player, containerId, destMenuSlot, (byte) 0);
            }
            remaining -= take;
        }

        if (remaining > 0) {
            // Box-inner bundle sources: build commands and let ShulkerBoxOps open each box
            // silently, taking the remaining count into the same cache slot (then grabbed to
            // cursor once back on the player screen).
            List<ShulkerBoxOps.ComposeCommand> commands = new java.util.ArrayList<>();
            for (BundlePanelRenderer.Source s : clicked.sources()) {
                if (remaining <= 0) break;
                if (s.type() == BundlePanelRenderer.PanelItemSource.SHULKER_INNER_BUNDLE) {
                    commands.add(new ShulkerBoxOps.ComposeCommand(
                            s.shulkerInvIndex(), s.boxSlot(), s.itemIndex(), true, s.count()));
                }
            }
            if (!commands.isEmpty()) {
                ShulkerBoxOps.startCompose(commands, remaining, destInvIndex);
                return true;
            }
        }

        // Pure player-bag take (or a partial take with no box sources left): the composed
        // stack sits in the cache slot - arm the grab so it lands on the cursor on return.
        ShulkerBoxOps.armPickupFor(destInvIndex);
        return true;
    }

    public static boolean handleSpaceClick(Slot hoveredSlot) {
        if (hoveredSlot == null || !hoveredSlot.hasItem()) return false;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getWindow() == null) return false;

        long window = client.getWindow().handle();
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) != GLFW.GLFW_PRESS) return false;

        Player player = client.player;
        ItemStack stack = hoveredSlot.getItem();
        if (stack.isEmpty() || BundleContentsHelper.isNonEmptyBundle(stack)) return false;

        int containerId = player.containerMenu.containerId;
        int itemSlot = hoveredSlot.index;
        List<BundlePanelRenderer.BundleSlotEntry> bundles = BundlePanelRenderer.getAllBundles();

        // 1) Prefer a bundle INSIDE a shulker box. Empty box slots do NOT count.
        for (BundlePanelRenderer.ShulkerEntry sh : BundlePanelRenderer.findShulkers()) {
            if (BundlePanelRenderer.boxHasBundleRoom(sh.boxStack(), stack)) {
                // Lift the item onto the cursor first, then let the silent box deposit put
                // it into the inner bundle (runDeposit clicks the pre-resolved target slot).
                int targetSlot = ShulkerSupport.findBundleSlotFor(sh.boxStack(), stack);
                if (targetSlot < 0) continue;
                pick(player, containerId, itemSlot, (byte) 0);
                ShulkerBoxOps.deposit(sh.invIndex(), targetSlot);
                return true;
            }
        }

        // 2) Otherwise store into a player-inventory bundle.
        int targetBundleSlot = -1;
        for (BundlePanelRenderer.BundleSlotEntry entry : bundles) {
            if (BundleContentsHelper.canFitItem(entry.bundleStack(), stack)) {
                targetBundleSlot = entry.bundleSlot();
                break;
            }
        }
        if (targetBundleSlot < 0) return false;

        pick(player, containerId, itemSlot, (byte) 0);
        pick(player, containerId, targetBundleSlot, (byte) 0);

        return true;
    }

    /** Issue a click through the canonical client path so the local menu (slots, carried,
     *  stateId) stays in sync and the panel can refresh in real time. */
    private static void pick(Player player, int containerId, int slot, byte button) {
        Minecraft.getInstance().gameMode.handleContainerInput(
                containerId, slot, button, ContainerInput.PICKUP, player);
    }

    /** Find an empty player inventory index (0-35); hotbar first (so the composed take
     *  lands in the quick bar / 物品栏 rather than the main backpack area), then main. */
    private static int findEmptyPlayerInvIndex(Player player) {
        for (int pass = 0; pass < 2; pass++) {
            int min = (pass == 0) ? 0 : 9;
            int max = (pass == 0) ? 9 : 36;
            for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
                if (slot.container == player.getInventory() && !slot.hasItem()) {
                    int idx = slot.getContainerSlot();
                    if (idx >= min && idx < max) return idx;
                }
            }
        }
        return -1;
    }

    /** Map a player inventory index (0-35) to its slot index in the CURRENT open menu. */
    private static int resolveInvIndexToMenuSlot(Player player, int inventoryIndex) {
        for (net.minecraft.world.inventory.Slot s : player.containerMenu.slots) {
            if (s.container == player.getInventory() && s.getContainerSlot() == inventoryIndex) {
                return s.index;
            }
        }
        return -1;
    }

    private static long bulkInsertStart = 0;
    private static final long BULK_INSERT_DELAY = 50; // 0.05s

    /** Start the bulk-insert timer (called on space+left-click inside panel with empty cursor). */
    public static void startBulkInsert() {
        bulkInsertStart = System.currentTimeMillis();
    }

    /** Whether the bulk-insert state is active (left button held > 0.05s). */
    public static boolean isBulkInsertActive() {
        return bulkInsertStart > 0 && (System.currentTimeMillis() - bulkInsertStart) >= BULK_INSERT_DELAY;
    }

    /** Exit bulk-insert state. */
    public static void stopBulkInsert() {
        bulkInsertStart = 0;
    }

    /** Put cursor item into any available bundle.
     *  button 0 = left (insert all), 1 = right (insert one). */
    public static boolean handlePanelInsert(int button) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return false;

        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.isEmpty()) return false;

        // 1) Prefer depositing into a bundle INSIDE a shulker box (the user's expected
        //    default). Empty box slots do NOT count. Target slot is resolved from the
        //    box contents BEFORE opening, so no un-synced menu is read.
        for (BundlePanelRenderer.ShulkerEntry sh : BundlePanelRenderer.findShulkers()) {
            int targetSlot = ShulkerSupport.findBundleSlotFor(sh.boxStack(), cursor);
            if (targetSlot >= 0) {
                ShulkerBoxOps.deposit(sh.invIndex(), targetSlot);
                return true;
            }
        }

        // 2) Otherwise a fitting player-inventory bundle.
        int containerId = player.containerMenu.containerId;
        for (BundlePanelRenderer.BundleSlotEntry entry : BundlePanelRenderer.getAllBundles()) {
            if (BundleContentsHelper.canFitItem(entry.bundleStack(), cursor)) {
                pick(player, containerId, entry.bundleSlot(), (byte) button);
                return true;
            }
        }

        return false;
    }

    public static boolean handleScroll(double mouseX, double mouseY, double scrollDelta,
                                        int leftPos, int topPos, int imageHeight) {
        if (!BundlePanelRenderer.isEffectivelyVisible()) return false;
        if (!isInsidePanel(mouseX, mouseY, leftPos, topPos, imageHeight)) return false;
        BundlePanelRenderer.scrollBy(scrollDelta > 0 ? -1 : 1);
        return true;
    }

    public static boolean isInsidePanel(double mouseX, double mouseY,
                                         int leftPos, int topPos, int imageHeight) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        int gx = gridX(leftPos);
        if (mouseX < gx || mouseX > panelX + pw - BundlePanelRenderer.PADDING) return false;
        int pTop = gridY(topPos);
        int pH = BundlePanelRenderer.VISIBLE_ROWS * BundlePanelRenderer.SLOT_SIZE
                + (BundlePanelRenderer.VISIBLE_ROWS - 1) * BundlePanelRenderer.SLOT_SPACING;
        if (mouseY < pTop || mouseY > pTop + pH) return false;
        return true;
    }
}
