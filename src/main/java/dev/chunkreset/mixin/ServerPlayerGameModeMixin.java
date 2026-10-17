package dev.chunkreset.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.chunkreset.tracking.ChangeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Attributes block changes made while a player breaks a block or uses an item (placing blocks, buckets, ...)
 * to that player, so {@code /rollback lookup} can show who did what.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@WrapMethod(method = "destroyBlock")
	private boolean chunkreset$destroyBlock(BlockPos pos, Operation<Boolean> original) {
		ChangeTracker.pushCause(player.getScoreboardName());
		try {
			return original.call(pos);
		} finally {
			ChangeTracker.popCause();
		}
	}

	@WrapMethod(method = "useItemOn")
	private InteractionResult chunkreset$useItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
		ChangeTracker.pushCause(player.getScoreboardName());
		try {
			return original.call(player, level, stack, hand, hit);
		} finally {
			ChangeTracker.popCause();
		}
	}

	@WrapMethod(method = "useItem")
	private InteractionResult chunkreset$useItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, Operation<InteractionResult> original) {
		ChangeTracker.pushCause(player.getScoreboardName());
		try {
			return original.call(player, level, stack, hand);
		} finally {
			ChangeTracker.popCause();
		}
	}
}
