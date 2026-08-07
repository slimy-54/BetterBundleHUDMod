package betterbundle;

import betterbundle.gui.BundleCategory;
import betterbundle.shulker.ShulkerBoxOps;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BetterBundleMod implements ClientModInitializer {
    public static final String MOD_ID = "better-bundle";

    @Override
    public void onInitializeClient() {
        BundleCategory.registerCategoryItems();
        // Drives the deferred (tick-synced) silent shulker-box operations.
        ClientTickEvents.END_CLIENT_TICK.register(client -> ShulkerBoxOps.tick());
    }
}
