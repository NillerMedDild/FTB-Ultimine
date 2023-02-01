package dev.ftb.mods.ftbultimine;

import dev.ftb.mods.ftbultimine.net.SendShapePacket;
import dev.ftb.mods.ftbultimine.shape.BlockMatcher;
import dev.ftb.mods.ftbultimine.shape.Shape;
import dev.ftb.mods.ftbultimine.shape.ShapeContext;
import dev.ftb.mods.ftbultimine.utils.PlatformMethods;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * @author LatvianModder
 */
public class FTBUltiminePlayerData {
	private final UUID id;
	public boolean pressed = false;
	public Shape shape = Shape.get("");

	public BlockPos cachedPos;
	public Direction cachedDirection;
	public List<BlockPos> cachedBlocks;

	public FTBUltiminePlayerData(UUID i) {
		id = i;
	}

	public void clearCache() {
		cachedPos = null;
		cachedDirection = null;
		cachedBlocks = null;
	}

	public static HitResult rayTrace(ServerPlayer player) {
		double distance = PlatformMethods.reach(player);
		return player.pick(player.isCreative() ? distance + 0.5D : distance, 1F, false);
	}

	public void checkBlocks(ServerPlayer player, boolean sendUpdate, int maxBlocks) {
		if (!pressed) {
			return;
		}

		HitResult result = rayTrace(player);

		if (!(result instanceof BlockHitResult hitResult) || result.getType() != HitResult.Type.BLOCK) {
			if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
				clearCache();

				if (sendUpdate) {
					new SendShapePacket(shape, Collections.emptyList()).sendTo(player);
				}
			}

			return;
		}

		if (cachedDirection != hitResult.getDirection() || cachedPos == null || !cachedPos.equals(hitResult.getBlockPos())) {
			updateBlocks(player, hitResult.getBlockPos(), hitResult.getDirection(), sendUpdate, maxBlocks);
		}
	}

	@Nullable
	public ShapeContext updateBlocks(ServerPlayer player, BlockPos pos, Direction dir, boolean sendUpdate, int maxBlocks) {
		ShapeContext context = null;
		cachedPos = pos.immutable();
		cachedDirection = dir;

		if (maxBlocks <= 0) {
			cachedBlocks = Collections.emptyList();
		} else {
			BlockState origState = player.level.getBlockState(cachedPos);
			BlockMatcher matcher;
			if (shape.getTagMatcher().actualCheck(origState, origState)) {
				matcher = shape.getTagMatcher();
			} else if (BlockMatcher.BUSH.actualCheck(origState, origState)) {
				matcher = BlockMatcher.BUSH;
			} else {
				matcher = BlockMatcher.MATCH;
			}
			context = new ShapeContext(player, cachedPos, cachedDirection, player.level.getBlockState(cachedPos), matcher, maxBlocks);
			cachedBlocks = shape.getBlocks(context);
		}

		if (sendUpdate) {
			new SendShapePacket(shape, cachedBlocks).sendTo(player);
		}

		return context;
	}
}