package dev.chunkreset.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.chunkreset.tracking.ChangeTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Attributes blocks destroyed by an explosion to {@code #explosion}, with the responsible entity when known
 * (the player who lit the TNT, the creeper, ...).
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@WrapMethod(method = "explode")
	private int chunkreset$explode(Operation<Integer> original) {
		ServerExplosion self = (ServerExplosion) (Object) this;
		Entity responsible = self.getIndirectSourceEntity();
		if (responsible == null) {
			responsible = self.getDirectSourceEntity();
		}
		String cause = "#explosion";
		if (responsible instanceof Player player) {
			cause += ":" + player.getScoreboardName();
		} else if (responsible != null) {
			cause += ":" + EntityType.getKey(responsible.getType());
		}
		ChangeTracker.pushCause(cause);
		try {
			return original.call();
		} finally {
			ChangeTracker.popCause();
		}
	}
}
