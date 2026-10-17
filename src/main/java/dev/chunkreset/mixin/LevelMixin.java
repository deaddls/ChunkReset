package dev.chunkreset.mixin;

import dev.chunkreset.tracking.ChangeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Level.blockEntityChanged} is what {@code BlockEntity.setChanged()} calls whenever a block entity's data
 * changed (items moved, sign edited, ...). We only note the position here; the data is compared at the end of the tick.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "blockEntityChanged", at = @At("HEAD"))
	private void chunkreset$onBlockEntityChanged(BlockPos pos, CallbackInfo ci) {
		ChangeTracker.markBlockEntityDirty((Level) (Object) this, pos);
	}
}
