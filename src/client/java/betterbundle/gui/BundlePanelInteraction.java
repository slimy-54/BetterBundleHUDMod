package betterbundle.gui;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
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

        BundlePanelRenderer.PanelItemSource source = clicked.source();
        if (source == BundlePanelRenderer.PanelItemSource.SHULKER_BOX) {
            // whole stack on left, exactly 1 on right
            int count = (button == 0) ? -1 : 1; // -1 = whole source stack
            ShulkerBoxOps.takeFromBox(clicked.shulkerInvIndex(), clicked.boxSlot(), count);
            return true;
        }
        if (source == BundlePanelRenderer.PanelItemSource.SHULKER_INNER_BUNDLE) {
            int count = (button == 0) ? -1 : 1;
            ShulkerBoxOps.takeFromInnerBundle(clicked.shulkerInvIndex(), clicked.boxSlot(), clicked.itemIndex(), count);
            return true;
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        int bundleSlot = clicked.bundleSlot();
        int containerId = player.containerMenu.containerId;
        boolean shiftDown = (modifiers & GLFW_MOD_SHIFT) != 0;

        if (shiftDown) {
            boolean inContainer = !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen);
            int emptySlot;
            if (button == 0 && inContainer) {
                emptySlot = findEmptyContainerSlot(player) >= 0
                        ? findEmptyContainerSlot(player) : findEmptyPlayerSlot(player);
            } else {
                emptySlot = findEmptyPlayerSlot(player);
            }
            if (emptySlot < 0) return true;

            int count = clicked.stack().getCount();
            for (int i = 0; i < count; i++) {
                connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, clicked.itemIndex()));
                connection.send(makeClickPacket(containerId, bundleSlot, (byte) 1));
            }
            connection.send(makeClickPacket(containerId, emptySlot, (byte) 0));
        } else if (button == 0) {
            // left-click: take the whole inner stack group into a free inventory slot
            int emptySlot = findEmptyPlayerSlot(player);
            if (emptySlot < 0) return true;
            int count = clicked.stack().getCount();
            for (int i = 0; i < count; i++) {
                connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, clicked.itemIndex()));
                connection.send(makeClickPacket(containerId, bundleSlot, (byte) 1));
            }
            connection.send(makeClickPacket(containerId, emptySlot, (byte) 0));
        } else {
            // right-click: take exactly 1 into the cursor
            connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, clicked.itemIndex()));
            connection.send(makeClickPacket(containerId, bundleSlot, (byte) 1));
        }

        return true;
    }

    /** Find an empty slot in the open container (not the player inventory). */
    private static int findEmptyContainerSlot(Player player) {
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (!slot.hasItem() && slot.container != player.getInventory()) {
                return slot.index;
            }
        }
        return -1;
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

        List<BundlePanelRenderer.BundleSlotEntry> bundles = BundlePanelRenderer.getAllBundles();
        int targetBundleSlot = -1;
        for (BundlePanelRenderer.BundleSlotEntry entry : bundles) {
            if (BundleContentsHelper.canFitItem(entry.bundleStack(), stack)) {
                targetBundleSlot = entry.bundleSlot();
                break;
            }
        }
        if (targetBundleSlot < 0) {
            // No fitting player bundle -> try depositing into a shulker box
            for (BundlePanelRenderer.ShulkerEntry sh : BundlePanelRenderer.findShulkers()) {
                if (ShulkerSupport.hasRoom(sh.boxStack(), stack)) {
                    ShulkerBoxOps.deposit(sh.invIndex());
                    return true;
                }
            }
            return false;
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        int containerId = player.containerMenu.containerId;
        int itemSlot = hoveredSlot.index;

        connection.send(makeClickPacket(containerId, itemSlot, (byte) 0));
        connection.send(makeClickPacket(containerId, targetBundleSlot, (byte) 0));

        return true;
    }

    private static ServerboundContainerClickPacket makeClickPacket(int containerId, int slot, byte button) {
        return new ServerboundContainerClickPacket(
                containerId, -1, (short) slot, button,
                ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
    }

    private static int findEmptyPlayerSlot(Player player) {
        // search main inventory (getSlotIndex 9-35) then hotbar (getSlotIndex 0-8)
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

        List<BundlePanelRenderer.BundleSlotEntry> bundles = BundlePanelRenderer.getAllBundles();
        int targetBundleSlot = -1;
        for (BundlePanelRenderer.BundleSlotEntry entry : bundles) {
            if (BundleContentsHelper.canFitItem(entry.bundleStack(), cursor)) {
                targetBundleSlot = entry.bundleSlot();
                break;
            }
        }
        if (targetBundleSlot < 0) {
            // No fitting player bundle -> try depositing the cursor stack into a shulker box
            for (BundlePanelRenderer.ShulkerEntry sh : BundlePanelRenderer.findShulkers()) {
                if (ShulkerSupport.hasRoom(sh.boxStack(), cursor)) {
                    ShulkerBoxOps.deposit(sh.invIndex());
                    return true;
                }
            }
            return false;
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;
        int containerId = player.containerMenu.containerId;
        connection.send(makeClickPacket(containerId, targetBundleSlot, (byte) button));
        return true;
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
