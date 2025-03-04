package me.jellysquid.mods.sodium.mixin.features.texture_tracking;

import me.jellysquid.mods.sodium.client.render.texture.SpriteUtil;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureMap.class)
public class MixinSpriteAtlasTexture {
    @Inject(method = "getAtlasSprite", at = @At("RETURN"))
    private void preReturnSprite(CallbackInfoReturnable<TextureAtlasSprite> cir) {
        TextureAtlasSprite sprite = cir.getReturnValue();

        if (sprite != null) {
            SpriteUtil.markSpriteActive(sprite);
        }
    }
}
