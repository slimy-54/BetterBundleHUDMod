package betterbundle.mixin;

import betterbundle.shulker.ShulkerBoxOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Waits for the shulker box menu to open after a silent operation request and hands it
 *  to {@link ShulkerBoxOps}, which performs the clicks a few ticks later (once the box
 *  menu has been synced by the server) and then returns the client to the previous
 *  screen so the user never sees the box GUI.
 *  handleOpenScreen runs on the network thread, so the actual work is dispatched to the
 *  render (client) thread via Minecraft.execute. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    private static boolean opRequested = false;

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void onContainerOpen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (!opRequested || !ShulkerBoxOps.isBusy()) {
            opRequested = false;
            return;
        }
        opRequested = false;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                ShulkerBoxOps.onBoxMenuOpened(mc.player.containerMenu);
            } catch (Throwable t) {
                // abort already handled inside ShulkerBoxOps
            }
        });
    }

    /** Capture the current screen before open is processed, keyed to a pending silent op. */
    @Inject(method = "handleOpenScreen", at = @At("HEAD"))
    private void onContainerOpenHead(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (ShulkerBoxOps.isBusy() && !opRequested) {
            ShulkerBoxOps.setPreviousScreen(Minecraft.getInstance().screen);
            opRequested = true;
        }
    }
}