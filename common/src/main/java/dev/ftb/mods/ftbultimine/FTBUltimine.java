package dev.ftb.mods.ftbultimine;

import dev.ftb.mods.ftbultimine.client.FTBUltimineClient;
import dev.ftb.mods.ftbultimine.config.FTBUltimineCommonConfig;
import dev.ftb.mods.ftbultimine.config.FTBUltimineServerConfig;
import dev.ftb.mods.ftbultimine.integration.FTBUltiminePlugins;
import dev.ftb.mods.ftbultimine.net.FTBUltimineNet;
import dev.ftb.mods.ftbultimine.net.SendShapePacket;
import dev.ftb.mods.ftbultimine.shape.BlockMatcher;
import dev.ftb.mods.ftbultimine.shape.EscapeTunnelShape;
import dev.ftb.mods.ftbultimine.shape.MiningTunnelShape;
import dev.ftb.mods.ftbultimine.shape.Shape;
import dev.ftb.mods.ftbultimine.shape.ShapeContext;
import dev.ftb.mods.ftbultimine.shape.ShapelessShape;
import dev.ftb.mods.ftbultimine.shape.SmallSquareShape;
import dev.ftb.mods.ftbultimine.shape.SmallTunnelShape;
import me.shedaniel.architectury.event.events.BlockEvent;
import me.shedaniel.architectury.event.events.EntityEvent;
import me.shedaniel.architectury.event.events.InteractionEvent;
import me.shedaniel.architectury.event.events.LifecycleEvent;
import me.shedaniel.architectury.event.events.TickEvent;
import me.shedaniel.architectury.hooks.PlayerHooks;
import me.shedaniel.architectury.hooks.TagHooks;
import me.shedaniel.architectury.utils.EnvExecutor;
import me.shedaniel.architectury.utils.IntValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * @author LatvianModder
 */
public class FTBUltimine {
	public static FTBUltimine instance;

	public static final String MOD_ID = "ftbultimine";
	public static final Logger LOGGER = LogManager.getLogger();

	public final FTBUltimineCommon proxy;

	private Map<UUID, FTBUltiminePlayerData> cachedDataMap;
	private boolean isBreakingBlock;
	private int tempBlockDroppedXp;
	private ItemCollection tempBlockDropsList;

	public static final Tag.Named<Item> DENY_TAG = TagHooks.getItemOptional(new ResourceLocation(MOD_ID, "excluded_tools"));
	public static final Tag.Named<Item> STRICT_DENY_TAG = TagHooks.getItemOptional(new ResourceLocation(MOD_ID, "excluded_tools/strict"));
	public static final Tag.Named<Item> ALLOW_TAG = TagHooks.getItemOptional(new ResourceLocation(MOD_ID, "included_tools"));
	public static final Tag.Named<Block> EXCLUDED_BLOCKS = TagHooks.getBlockOptional(new ResourceLocation(MOD_ID, "excluded_blocks"));

	private static Predicate<Player> permissionOverride = player -> true;

	public static void setPermissionOverride(Predicate<Player> p) {
		permissionOverride = p;
	}

	public FTBUltimine() {
		instance = this;
		FTBUltimineNet.init();

		proxy = EnvExecutor.getEnvSpecific(() -> FTBUltimineClient::new, () -> FTBUltimineCommon::new);

		FTBUltimineCommonConfig.load();
		FTBUltiminePlugins.init();

		Shape.register(new ShapelessShape());
		Shape.register(new SmallTunnelShape());
		Shape.register(new SmallSquareShape());
		Shape.register(new MiningTunnelShape());
		Shape.register(new EscapeTunnelShape());

		Shape.postinit();

		LifecycleEvent.SERVER_BEFORE_START.register(this::serverStarting);
		BlockEvent.BREAK.register(this::blockBroken);
		InteractionEvent.RIGHT_CLICK_BLOCK.register(this::blockRightClick);
		TickEvent.PLAYER_PRE.register(this::playerTick);
		EntityEvent.ADD.register(this::entityJoinedWorld);
	}

	public FTBUltiminePlayerData get(Player player) {
		return cachedDataMap.computeIfAbsent(player.getUUID(), FTBUltiminePlayerData::new);
	}

	private void serverStarting(MinecraftServer server) {
		cachedDataMap = new HashMap<>();
		FTBUltimineServerConfig.load(server);
	}

	public void setKeyPressed(ServerPlayer player, boolean pressed) {
		FTBUltiminePlayerData data = get(player);
		data.pressed = pressed;
		data.clearCache();

		if (!data.pressed) {
			FTBUltimineNet.MAIN.sendToPlayer(player, new SendShapePacket(data.shape, Collections.emptyList()));
		}
	}

	public void modeChanged(ServerPlayer player, boolean next) {
		FTBUltiminePlayerData data = get(player);
		data.shape = next ? data.shape.next : data.shape.prev;
		data.clearCache();
		FTBUltimineNet.MAIN.sendToPlayer(player, new SendShapePacket(data.shape, Collections.emptyList()));
	}

	private int getMaxBlocks(Player player) {
		return FTBUltimineServerConfig.MAX_BLOCKS.get();
	}

	public boolean canUltimine(Player player) {
		if (PlayerHooks.isFake(player) || player.getUUID() == null) {
			return false;
		}

		if (player.getFoodData().getFoodLevel() <= 0 && !player.isCreative()) {
			return false;
		}

		if (!permissionOverride.test(player)) {
			return false;
		}

		Item mainHand = player.getMainHandItem().getItem();
		Item offHand = player.getOffhandItem().getItem();

		if (mainHand.is(STRICT_DENY_TAG) || offHand.is(STRICT_DENY_TAG)) {
			return false;
		}

		if (mainHand.is(DENY_TAG)) {
			return false;
		}

		List<Item> allowedTools = ALLOW_TAG.getValues();

		if (!allowedTools.isEmpty() && !allowedTools.contains(mainHand)) {
			return false;
		}

		return FTBUltiminePlugins.canUltimine(player);
	}

	public InteractionResult blockBroken(Level world, BlockPos pos, BlockState state, ServerPlayer player, @Nullable IntValue xp) {
		if (isBreakingBlock || !canUltimine(player)) {
			return InteractionResult.PASS;
		}

		FTBUltiminePlayerData data = get(player);

		if (!data.pressed) {
			return InteractionResult.PASS;
		}

		HitResult result = FTBUltiminePlayerData.rayTrace(player);

		if (!(result instanceof BlockHitResult) || result.getType() != HitResult.Type.BLOCK) {
			return InteractionResult.PASS;
		}

		data.clearCache();
		data.updateBlocks(player, pos, ((BlockHitResult) result).getDirection(), false, getMaxBlocks(player));

		if (data.cachedBlocks == null || data.cachedBlocks.isEmpty()) {
			return InteractionResult.PASS;
		}

		isBreakingBlock = true;
		tempBlockDropsList = new ItemCollection();
		tempBlockDroppedXp = 0;
		boolean hadItem = !player.getMainHandItem().isEmpty();

		for (BlockPos p : data.cachedBlocks) {
			if (!player.gameMode.destroyBlock(p) && FTBUltimineCommonConfig.CANCEL_ON_BLOCK_BREAK_FAIL.get()) {
				break;
			}

			if (!player.isCreative()) {
				player.causeFoodExhaustion((float) (FTBUltimineServerConfig.EXHAUSTION_PER_BLOCK.get() * 0.005D));

				if (player.getFoodData().getFoodLevel() <= 0) {
					break;
				}
			}

			ItemStack stack = player.getMainHandItem();

			if (hadItem && stack.isEmpty()) {
				break;
			} else if (hadItem && stack.hasTag() && stack.getTag().getBoolean("tic_broken")) {
				break;
			} else if (hadItem && FTBUltimineCommonConfig.PREVENT_TOOL_BREAK.get() > 0 && stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - FTBUltimineCommonConfig.PREVENT_TOOL_BREAK.get()) {
				break;
			}
		}

		isBreakingBlock = false;

		tempBlockDropsList.drop(player.level, pos);

		if (tempBlockDroppedXp > 0) {
			player.level.addFreshEntity(new ExperienceOrb(player.level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, tempBlockDroppedXp));
		}

		data.clearCache();
		FTBUltimineNet.MAIN.sendToPlayer(player, new SendShapePacket(data.shape, Collections.emptyList()));

		return InteractionResult.FAIL;
	}

	public InteractionResult blockRightClick(Player player, InteractionHand hand, BlockPos clickPos, Direction face) {
		if (!(player instanceof ServerPlayer) || PlayerHooks.isFake(player) || player.getUUID() == null) {
			return InteractionResult.PASS;
		}

		ServerPlayer serverPlayer = (ServerPlayer) player;

		if (player.getFoodData().getFoodLevel() <= 0 && !player.isCreative()) {
			return InteractionResult.PASS;
		}

		HitResult result = FTBUltiminePlayerData.rayTrace(serverPlayer);

		if (!(result instanceof BlockHitResult) || result.getType() != HitResult.Type.BLOCK) {
			return InteractionResult.PASS;
		}

		FTBUltiminePlayerData data = get(player);
		data.clearCache();
		ShapeContext shapeContext = data.updateBlocks(serverPlayer, clickPos, ((BlockHitResult) result).getDirection(), false, getMaxBlocks(player));

		if (shapeContext == null || !data.pressed || data.cachedBlocks == null || data.cachedBlocks.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (player.getItemInHand(hand).getItem() instanceof HoeItem) {
			ResourceLocation dirtTag = new ResourceLocation("ftbultimine", "farmland_tillable");

			if (!player.level.isClientSide()) {
				boolean playSound = false;
				BrokenItemHandler brokenItemHandler = new BrokenItemHandler();

				for (int i = 0; i < Math.min(data.cachedBlocks.size(), FTBUltimineServerConfig.MAX_BLOCKS.get()); i++) {
					BlockPos p = data.cachedBlocks.get(i);
					BlockState state = player.level.getBlockState(p);

					if (!BlockTags.getAllTags().getTagOrEmpty(dirtTag).contains(state.getBlock())) {
						continue;
					}

					player.level.setBlock(p, Blocks.FARMLAND.defaultBlockState(), 11);
					playSound = true;

					if (!player.isCreative()) {
						player.getMainHandItem().hurtAndBreak(1, serverPlayer, brokenItemHandler);
						player.causeFoodExhaustion((float) (FTBUltimineServerConfig.EXHAUSTION_PER_BLOCK.get() * 0.005D));

						if (brokenItemHandler.isBroken || player.getFoodData().getFoodLevel() <= 0) {
							break;
						}
					}
				}

				if (playSound) {
					player.level.playSound(player, clickPos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1F, 1F);
				}
			}

			player.swing(hand);
			return InteractionResult.FAIL;
		} else if (shapeContext.matcher == BlockMatcher.BUSH) {
			ItemCollection itemCollection = new ItemCollection();

			for (BlockPos pos : data.cachedBlocks) {
				BlockState state = player.level.getBlockState(pos);

				if (!(state.getBlock() instanceof CropBlock)) {
					continue;
				}

				CropBlock c = (CropBlock) state.getBlock();

				if (!c.isMaxAge(state)) {
					continue;
				}

				if (player.level.isClientSide()) {
					player.swing(hand);
					continue;
				}

				List<ItemStack> drops = Block.getDrops(state, (ServerLevel) player.level, pos, state.getBlock().isEntityBlock() ? player.level.getBlockEntity(pos) : null, player, ItemStack.EMPTY);

				for (ItemStack stack : drops) {
					// should work for most if not all modded crop blocks, hopefully
					if (Block.byItem(stack.getItem()) == c) {
						stack.shrink(1);
					}

					itemCollection.add(stack);
				}

				player.level.setBlock(pos, c.getStateForAge(0), 3);
			}

			itemCollection.drop(player.level, face == null ? clickPos : clickPos.relative(face));
			player.swing(hand);
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	public void playerTick(Player player) {
		if (!player.level.isClientSide()) {
			FTBUltiminePlayerData data = get(player);
			data.checkBlocks((ServerPlayer) player, true, getMaxBlocks(player));
		}
	}

	public InteractionResult entityJoinedWorld(Entity entity, Level level) {
		if (isBreakingBlock && entity instanceof ItemEntity) {
			tempBlockDropsList.add(((ItemEntity) entity).getItem());
			return InteractionResult.FAIL;
		} else if (isBreakingBlock && entity instanceof ExperienceOrb) {
			tempBlockDroppedXp += ((ExperienceOrb) entity).getValue();
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}