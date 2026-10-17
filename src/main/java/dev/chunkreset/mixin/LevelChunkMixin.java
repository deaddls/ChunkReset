package dev.chunkreset.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.chunkreset.tracking.ChangeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block modification of a loaded chunk goes through {@code LevelChunk.setBlockState}; wrapping it lets
 * us record the previous block (and its block entity data) right before it is replaced. The chunk's block
 * entity registration/clearing hooks keep the block entity snapshots in sync with chunk loading.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@WrapMethod(method = "setBlockState")
	private @Nullable BlockState chunkreset$setBlockState(BlockPos pos, BlockState state, int flags, Operation<BlockState> original) {
		LevelChunk self = (LevelChunk) (Object) this;
		ChangeTracker.Frame frame = ChangeTracker.isActive() ? ChangeTracker.beginBlockChange(self, pos, state) : null;
		BlockState result = original.call(pos, state, flags);
		if (frame != null) {
			ChangeTracker.endBlockChange(self, pos, state, result, frame);
		}
		return result;
	}

	@Inject(method = "registerAllBlockEntitiesAfterLevelLoad", at = @At("TAIL"))
	private void chunkreset$afterBlockEntitiesRegistered(CallbackInfo ci) {
		ChangeTracker.onChunkBlockEntitiesRegistered((LevelChunk) (Object) this);
	}

	@Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
	private void chunkreset$beforeBlockEntitiesCleared(CallbackInfo ci) {
		ChangeTracker.onChunkBlockEntitiesCleared((LevelChunk) (Object) this);
	}
}
