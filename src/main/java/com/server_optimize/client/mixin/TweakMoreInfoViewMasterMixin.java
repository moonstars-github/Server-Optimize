package com.server_optimize.client.mixin;

import com.server_optimize.config.ModConfig;
import me.fallenbreath.tweakermore.config.TweakerMoreConfigs;
import me.fallenbreath.tweakermore.config.options.TweakerMoreConfigBooleanHotkeyed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * TweakMore renders every info view only when its global InfoView switch is on.
 * The user-facing hopper switch can be on while the global switch is off, which
 * makes HopperCooldownRenderer never reach the cooldown accessor. Let the
 * hopper-specific switch bypass the global switch while keeping our cooldown
 * compatibility enabled.
 */
@Pseudo
@Mixin(targets = "me.fallenbreath.tweakermore.impl.features.infoView.InfoViewRenderer")
public class TweakMoreInfoViewMasterMixin {

    @Redirect(
        method = "onRenderWorldLast",
        at = @At(
            value = "INVOKE",
            target = "Lme/fallenbreath/tweakermore/config/options/TweakerMoreConfigBooleanHotkeyed;getBooleanValue()Z",
            remap = false
        )
    )
    private boolean serverOptimize$allowHopperInfoView(TweakerMoreConfigBooleanHotkeyed config) {
        if (ModConfig.INSTANCE != null
                && ModConfig.INSTANCE.hopper.enabled
                && ModConfig.INSTANCE.hopper.tweakmoreCompat
                && TweakerMoreConfigs.INFO_VIEW_HOPPER.getBooleanValue()) {
            return true;
        }
        return config.getBooleanValue();
    }
}
