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
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        // Split the clicked cell's sources into player-inventory bags vs boxes.
        boolean hasPlayerBundles = false;
        boolean hasBoxes = false;
        for (BundlePanelRenderer.Source s : clicked.sources()) {
            if (s.type() == BundlePanelRenderer.PanelItemSource.PLAYER_BUNDLE) hasPlayerBundles = true;
            else hasBoxes = true;
        }

        if (!hasBoxes) {
            // Player-bag-only cell: take to the cursor (left = one full stack composed
            // across contributing bags, right = exactly 1).
            int containerId = player.containerMenu.containerId;
            int targetCount = (button == 0) ? clicked.stack().getMaxStackSize() : 1;
            int remaining = targetCount;
            for (BundlePanelRenderer.Source s : clicked.sources()) {
                if (s.type() != BundlePanelRenderer.PanelItemSource.PLAYER_BUNDLE) continue;
                if (remaining <= 0) break;
                int take = Math.min(remaining, s.count());
                for (int i = 0; i < take; i++) {
                    connection.send(new ServerboundSelectBundleItemPacket(s.bundleSlot(), s.itemIndex()));
                    connection.send(makeClickPacket(containerId, s.bundleSlot(), (byte) 1));
                }
                remaining -= take;
            }
            return true;
        }

        // Cell involves box(es): auto-compose one stack into a single player inventory
        // slot across bags first, then boxes (opened sequentially). Right-click = 1.
        int targetCount = (button == 0) ? clicked.stack().getMaxStackSize() : 1;
        int destInvIndex = findEmptyPlayerInvIndex(player);
        if (destInvIndex < 0) return true;          // no room
        int remaining = targetCount;

        if (hasPlayerBundles) {
            // consume player-bag sources first into the destination slot (open-inventory menu)
            int containerId = player.containerMenu.containerId;
            int destMenu = resolveInvIndexToMenuSlot(player, destInvIndex);
            for (BundlePanelRenderer.Source s : clicked.sources()) {
                if (s.type() != BundlePanelRenderer.PanelItemSource.PLAYER_BUNDLE) continue;
                if (remaining <= 0) break;
                int take = Math.min(remaining, s.count());
                for (int i = 0; i < take; i++) {
                    connection.send(new ServerboundSelectBundleItemPacket(s.bundleSlot(), s.itemIndex()));
                    connection.send(makeClickPacket(containerId, s.bundleSlot(), (byte) 1));
                }
                if (destMenu >= 0) connection.send(makeClickPacket(containerId, destMenu, (byte) 0));
                remaining -= take;
            }
        }

        // box sources: build commands and let ShulkerBoxOps open the box(es) sequentially,
        // taking the remaining count into the cache slot (then grabbed to the cursor on return).
        if (remaining > 0) {
            List<ShulkerBoxOps.ComposeCommand> commands = new java.util.ArrayList<>();
            for (BundlePanelRenderer.Source s : clicked.sources()) {
                if (remaining <= 0) break;
                if (s.type() == BundlePanelRenderer.PanelItemSource.SHULKER_INNER_BUNDLE) {
                    commands.add(new ShulkerBoxOps.ComposeCommand(
                            s.shulkerInvIndex(), s.boxSlot(), s.itemIndex(), true, s.count()));
                }
            }
            ShulkerBoxOps.startCompose(commands, remaining, destInvIndex);
        }
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

    /** Find an empty player inventory index (0-35); main inventory, then hotbar. */
    private static int findEmptyPlayerInvIndex(Player player) {
        for (int pass = 0; pass < 2; pass++) {
            int min = (pass == 0) ? 9 : 0;
            int max = (pass == 0) ? 36 : 9;
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
