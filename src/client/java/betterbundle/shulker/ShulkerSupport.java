package betterbundle.shulker;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import betterbundle.util.BundleContentsHelper;

/** Client-side integration with the optional quickshulker(-multi) mod.
 *  All quickshulker classes are accessed via reflection so that this class is safe
 *  to load even when quickshulker is not installed (runtime degradation). */
public final class ShulkerSupport {

    public static final String QUICKSHULKER_MOD_ID = "quickshulker";
    private static final String OPEN_SHULKER_PACKET_CLASS = "net.kyrptonaught.quickshulker.network.OpenShulkerPacket";

    private ShulkerSupport() {}

    /** quickshulker is loaded at runtime (mod jar present). */
    public static boolean isLoaded() {
        try {
            return FabricLoader.getInstance().isModLoaded(QUICKSHULKER_MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if this stack is a shulker box item. */
    public static boolean isShulker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        return bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Read the container contents component (may be null). */
    public static ItemContainerContents getContents(ItemStack stack) {
        if (!isShulker(stack)) return null;
        return stack.get(DataComponents.CONTAINER);
    }

    /** Client-side check: can the given item be deposited into this shulker box,
     *  either into an empty slot or into an inner bundle (insertion policy). */
    public static boolean hasRoom(ItemStack box, ItemStack toInsert) {
        ItemContainerContents c = getContents(box);
        if (c == null || toInsert == null || toInsert.isEmpty()) return false;
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        c.copyInto(items);
        boolean hasEmpty = false;
        for (ItemStack it : items) {
            if (it.isEmpty()) { hasEmpty = true; continue; }
            if (BundleContentsHelper.isBundle(it) && BundleContentsHelper.canFitItem(it, toInsert)) {
                return true;
            }
        }
        return hasEmpty;
    }

    /** Find the first bundle slot (0..26) inside this box that can fit {@code toInsert}.
     *  Uses the box's own contents component (available before the box is opened),
     *  so we never have to read an un-synced open menu. Returns -1 if none fits. */
    public static int findBundleSlotFor(ItemStack box, ItemStack toInsert) {
        if (!isShulker(box) || toInsert == null || toInsert.isEmpty()) return -1;
        ItemContainerContents c = getContents(box);
        if (c == null) return -1;
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        c.copyInto(items);
        for (int slot = 0; slot < 27; slot++) {
            ItemStack it = items.get(slot);
            if (BundleContentsHelper.isBundle(it) && BundleContentsHelper.canFitItem(it, toInsert)) {
                return slot;
            }
        }
        return -1;
    }

    /** Map player-inventory index -> container menu slot index (for click packets / opening). */
    public static int findContainerSlot(Player player, int inventoryIndex) {
        for (Slot slot : player.containerMenu.slots) {
            if (slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return inventoryIndex;
    }

    /** Open a shulker box at the given player-inventory index via quickshulker.
     *  Only meaningful when {@link #isLoaded()} is true. Uses reflection to stay
     *  compatible when the mod is absent. */
    public static boolean openAtInventorySlot(int inventoryIndex) {
        if (!isLoaded()) return false;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return false;
        int containerSlot = findContainerSlot(player, inventoryIndex);
        if (containerSlot < 0) return false;
        return invokeSendOpenPacket(containerSlot);
    }

    private static boolean invokeSendOpenPacket(int containerSlot) {
        try {
            Class<?> cls = Class.forName(OPEN_SHULKER_PACKET_CLASS);
            cls.getMethod("sendOpenPacket", int.class).invoke(null, containerSlot);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
