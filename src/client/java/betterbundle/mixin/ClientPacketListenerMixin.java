package betterbundle.mixin;

import betterbundle.shulker.ShulkerBoxOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerOpenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Waits for the shulker box menu to open after a silent operation request,
 *  performs the clicks, and immediately returns the client to the previous screen
 *  so the user never sees the box GUI. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    private static Screen previousScreen;
    private static boolean opRequested = false;

    @Inject(method = "handleContainerOpen", at = @At("HEAD"))
    private void onContainerOpenHead(ClientboundContainerOpenPacket packet, CallbackInfo ci) {
        if (ShulkerBoxOps.isBusy() && !opRequested) {
            previousScreen = Minecraft.getInstance().screen;
            opRequested = true;
        }
    }

    @Inject(method = "handleContainerOpen", at = @At("TAIL"))
    private void onContainerOpenTail(ClientboundContainerOpenPacket packet, CallbackInfo ci) {
        if (!opRequested || !ShulkerBoxOps.isBusy()) {
            opRequested = false;
            return;
        }
        opRequested = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            ShulkerBoxOps.onBoxMenuOpened(mc.player.containerMenu);
            // restore previous screen so the box GUI never flashes
            if (previousScreen != null) {
                mc.setScreen(previousScreen);
                previousScreen = null;
            } else {
                mc.setScreen(new InventoryScreen(mc.player));
            }
        } catch (Throwable t) {
            previousScreen = null;
        }
    }
}
