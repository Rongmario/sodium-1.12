/*package me.jellysquid.mods.sodium.mixin.features.options;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;

import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GameSettings.class)
public class MixinGameOptions {
    @Shadow
    public int viewDistance;

    @Shadow
    public GraphicsMode graphicsMode;

    @Overwrite
    public CloudRenderMode getCloudRenderMode() {
        SodiumGameOptions options = SodiumClientMod.options();

        if (this.viewDistance < 4 || !options.quality.enableClouds) {
            return CloudRenderMode.OFF;
        }

        return options.quality.cloudQuality.isFancy(this.graphicsMode) ? CloudRenderMode.FANCY : CloudRenderMode.FAST;
    }
}*/
