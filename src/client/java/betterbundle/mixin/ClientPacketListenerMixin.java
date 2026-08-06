package betterbundle.mixin;

import betterbundle.shulker.ShulkerBoxOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Waits for the shulker box menu to open after a silent operation request,
 *  performs the clicks, and immediately returns the client to the previous screen
 *  so the user never sees the box GUI.
 *  handleOpenScreen runs on the network thread, so the actual work is dispatched
 *  to the render (client) thread via Minecraft.execute. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    private static Screen previousScreen;
    private static boolean opRequested = false;

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void onContainerOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (!opRequested || !ShulkerBoxOps.isBusy()) {
            opRequested = false;
            return;
        }
        opRequested = false;
        Minecraft mc = Minecraft.getInstance();
        Screen prev = previousScreen;
        previousScreen = null;
        mc.execute(() -> {
            try {
                ShulkerBoxOps.onBoxMenuOpened(mc.player.containerMenu);
            } catch (Throwable t) {
                // abort already handled inside ShulkerBoxOps
            }
            // restore previous screen so the box GUI never flashes
            if (prev != null && mc.screen != prev) {
                mc.setScreen(prev);
            } else {
                mc.setScreen(new InventoryScreen(mc.player));
            }
        });
    }

    /** Capture the current screen before open is processed, keyed to a pending silent op. */
    @Inject(method = "handleOpenScreen", at = @At("HEAD"))
    private void onContainerOpenHead(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (ShulkerBoxOps.isBusy() && !opRequested) {
            previousScreen = Minecraft.getInstance().screen;
            opRequested = true;
        }
    }
}