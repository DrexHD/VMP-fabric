package com.ishland.vmp.mixins.chunk.loading.portals;

import com.ibm.asyncutil.iteration.AsyncIterator;
import com.ishland.vmp.common.chunk.loading.IEntityPortalInterface;
import com.ishland.vmp.common.chunk.loading.IPOIAsyncPreload;
import com.ishland.vmp.common.chunk.loading.async_chunks_on_player_login.AsyncChunkLoadUtil;
import com.ishland.vmp.common.config.Config;
import com.ishland.vmp.mixins.access.INetherPortalBlock;
import com.ishland.vmp.mixins.access.IPortalManager;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockLocating;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.PortalManager;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@Mixin(Entity.class)
public abstract class MixinEntity implements IEntityPortalInterface {

    @Unique
    private static final CompletableFuture<TeleportTarget> TARGET_COMPLETED_FUTURE = CompletableFuture.completedFuture(null);

    @Shadow
    public World world;

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getY();

    @Shadow
    public abstract double getZ();

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    protected abstract Vec3d positionInPortal(Direction.Axis portalAxis, BlockLocating.Rectangle portalRect);

    @Shadow @Nullable public PortalManager portalManager;

    @Shadow public abstract World getWorld();

    @Unique
    private CompletableFuture<TeleportTarget> vmp$locatePortalFuture;

    @Unique
    private CompletableFuture<TeleportTarget> vmp$lastLocateFuture = TARGET_COMPLETED_FUTURE;

    @Unique
    private long vmp$locateIndex = 0;

    @Inject(method = "tickPortalTeleportation", at = @At("HEAD"))
    private void onTickPortal(CallbackInfo ci) {
        if (this.world.isClient) return;
        //noinspection ConstantConditions
        if ((Object) this instanceof ServerPlayerEntity player) {
            if (this.portalManager != null && this.portalManager.isInPortal() && ((IPortalManager)this.portalManager).getPortal() instanceof NetherPortalBlock && this.portalManager.getTicksInPortal() >= ((IPortalManager)this.portalManager).getPortal().getPortalDelay(player.getServerWorld(), player) - 50) {
                if (vmp$locatePortalFuture == null && vmp$lastLocateFuture.isDone()) {
                    MinecraftServer minecraftServer = this.world.getServer();
                    RegistryKey<World> registryKey = this.world.getRegistryKey() == World.NETHER ? World.OVERWORLD : World.NETHER;
                    ServerWorld destination = minecraftServer.getWorld(registryKey);
                    long currentLocateIndex = ++vmp$locateIndex;
                    long startTime = System.nanoTime();
                    if (Config.SHOW_ASYNC_LOADING_MESSAGES) {
                        player.sendMessage(Text.literal("Locating portal destination..."), true);
                    }
                    vmp$lastLocateFuture = vmp$locatePortalFuture =
                            createTeleportTargetNetherPortalBlockAsync(destination)
                                    .thenComposeAsync(target -> {
                                        if (target != null) {
                                            return AsyncChunkLoadUtil.scheduleChunkLoad(destination, new ChunkPos(BlockPos.ofFloored(target.pos().x, target.pos().y, target.pos().z)))
                                                    .thenApplyAsync(unused -> {
                                                        final BlockPos blockPos = BlockPos.ofFloored(target.pos().x, target.pos().y, target.pos().z);
                                                        destination.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(blockPos), 3, blockPos); // for vanilla behavior and faster teleports
                                                        return target;
                                                    }, destination.getServer());
                                        } else {
                                            return CompletableFuture.completedFuture(null);
                                        }
                                    }, destination.getServer())
                                    .whenCompleteAsync((target, throwable) -> {
                                        if (currentLocateIndex != vmp$locateIndex) return;
                                        if (throwable != null) {
                                            LOGGER.error("Error occurred for entity {} while locating portal", this, throwable);
                                            player.sendMessage(Text.literal("Error occurred while locating portal"), true);
                                        } else if (target != null) {
                                            if (Config.SHOW_ASYNC_LOADING_MESSAGES) {
                                                LOGGER.info("Portal located for entity {} at {}", this, target);
                                                final BlockPos blockPos = BlockPos.ofFloored(target.pos().x, target.pos().y, target.pos().z);
                                                player.sendMessage(Text.literal("Portal located after %.1fms, waiting for portal teleportation...".formatted((System.nanoTime() - startTime) / 1_000_000.0)), true);
                                            }
                                        } else {
                                            if (Config.SHOW_ASYNC_LOADING_MESSAGES) {
                                                LOGGER.info("Portal not located for entity {} at {}", this, target);
                                                player.sendMessage(Text.literal("Portal not located, will spawn a new portal later"), true);
                                            }
                                        }
                                    }, destination.getServer())
                                    .toCompletableFuture();
                }
            } else {
                if (vmp$locatePortalFuture != null) {
                    final boolean done = vmp$locatePortalFuture.isDone();
                    vmp$locatePortalFuture.cancel(false);
                    vmp$locatePortalFuture = null;
                    vmp$locateIndex++;
                    if (!done) {
                        if (Config.SHOW_ASYNC_LOADING_MESSAGES) {
                            player.sendMessage(Text.literal("Portal location cancelled"), true);
                        }
                    }
                }
            }
        }
    }

//    @Redirect(method = "tickPortal", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getMaxNetherPortalTime()I"))
//    private int redirectMaxPortalTime(Entity instance) {
//        if (instance instanceof ServerPlayerEntity) {
//            return (vmp$locatePortalFuture != null && vmp$locatePortalFuture.isDone()) ? instance.getMaxNetherPortalTime() : Integer.MAX_VALUE;
//        }
//        return instance.getMaxNetherPortalTime();
//    }

//    @Inject(method = "getTeleportTarget", at = @At("HEAD"), cancellable = true)
//    private void beforeGetTeleportTarget(ServerWorld destination, CallbackInfoReturnable<TeleportTarget> cir) {
//        if (this.vmp$locatePortalFuture != null && this.vmp$locatePortalFuture.isDone() && !this.vmp$locatePortalFuture.isCompletedExceptionally()) {
//            final TeleportTarget value = this.vmp$locatePortalFuture.join();
//            if (value != null) cir.setReturnValue(value);
//        }
//    }

    @Unique
    public CompletionStage<TeleportTarget> createTeleportTargetNetherPortalBlockAsync(ServerWorld destination) {
        // TODO [VanillaCopy]
        RegistryKey<World> registryKey = world.getRegistryKey() == World.NETHER ? World.OVERWORLD : World.NETHER;
        ServerWorld serverWorld = world.getServer().getWorld(registryKey);
        boolean inNether = serverWorld.getRegistryKey() == World.NETHER;
        WorldBorder worldBorder = serverWorld.getWorldBorder();
        double d = DimensionType.getCoordinateScaleFactor(world.getDimension(), serverWorld.getDimension());
        BlockPos destPos = worldBorder.clamp(this.getX() * d, this.getY(), this.getZ() * d);
        if (this.world.getRegistryKey() != World.NETHER && !inNether) {
            return CompletableFuture.completedFuture(null);
        } else {
            return this.getPortalRectAtAsync(destination, destPos, inNether, worldBorder)
                    .thenComposeAsync(optional -> optional.map(rect ->
                                    AsyncChunkLoadUtil.scheduleChunkLoad(destination, new ChunkPos(this.portalManager.getPortalPos()))
                                            .thenComposeAsync(unused -> {
                                                BlockState blockState = this.world.getBlockState(this.portalManager.getPortalPos());
                                                Direction.Axis axis;
                                                Vec3d vec3d;
                                                if (blockState.contains(Properties.HORIZONTAL_AXIS)) {
                                                    axis = blockState.get(Properties.HORIZONTAL_AXIS);
                                                    BlockLocating.Rectangle rectangle = BlockLocating.getLargestRectangle(
                                                        this.portalManager.getPortalPos(), axis, 21, Direction.Axis.Y, 21, pos -> this.world.getBlockState(pos) == blockState
                                                    );
                                                    vec3d = this.positionInPortal(axis, rectangle);
                                                } else {
                                                    axis = Direction.Axis.X;
                                                    vec3d = new Vec3d(0.5, 0.0, 0.0);
                                                }

                                                return AsyncChunkLoadUtil.scheduleChunkLoad(destination, new ChunkPos(rect.lowerLeft))
                                                        .thenApplyAsync(unused1 -> INetherPortalBlock.invokeGetExitPortalTarget(
                                                                (Entity) (Object) this, this.portalManager.getPortalPos(), rect, destination, TeleportTarget.field_52246.then(TeleportTarget.field_52247)),
                                                                destination.getServer());
                                            }, destination.getServer()))
                            .orElse(CompletableFuture.completedFuture(null)), destination.getServer());
        }
    }

    @Unique
    public CompletionStage<Optional<BlockLocating.Rectangle>> getPortalRectAtAsync(ServerWorld destination, BlockPos destPos, boolean destIsNether, WorldBorder worldBorder) {
        PointOfInterestStorage pointOfInterestStorage = destination.getPointOfInterestStorage();
        int i = destIsNether ? 16 : 128;
        return ((CompletionStage<Void>) ((IPOIAsyncPreload) pointOfInterestStorage).preloadChunksAtAsync(destination, destPos, i))
                .thenComposeAsync(unused -> {
                    final Iterator<PointOfInterest> iterator = pointOfInterestStorage.getInSquare(
                                    registryEntry -> registryEntry.matchesKey(PointOfInterestTypes.NETHER_PORTAL),
                                    destPos, i, PointOfInterestStorage.OccupationStatus.ANY
                            )
                            .filter(poi -> worldBorder.contains(poi.getPos()))
                            .sorted(Comparator.comparingDouble((PointOfInterest poi) -> poi.getPos().getSquaredDistance(destPos)).thenComparingInt(poi -> poi.getPos().getY()))
                            .toList().iterator();
                    return AsyncIterator
                            .fromIterator(iterator)
                            .filterCompose(poi -> AsyncChunkLoadUtil.scheduleChunkLoadWithRadius(destination, new ChunkPos(poi.getPos()), 0)
                                    .thenApplyAsync(either -> either.orElseThrow(RuntimeException::new)
                                                    .getBlockState(poi.getPos()).contains(Properties.HORIZONTAL_AXIS) ? Optional.of(poi) : Optional.empty(),
                                            destination.getServer())
                            )
                            .take(1)
                            .thenComposeAsync(poi -> {
                                BlockPos blockPos = poi.getPos();
                                return AsyncChunkLoadUtil.scheduleChunkLoad(destination, new ChunkPos(blockPos))
                                        .thenApplyAsync(unused1 -> {
                                            destination.getChunkManager().addTicket(ChunkTicketType.PORTAL, new ChunkPos(blockPos), 3, blockPos); // for vanilla behavior
                                            BlockState blockState = destination.getBlockState(blockPos);
                                            return BlockLocating.getLargestRectangle(
                                                    blockPos, blockState.get(Properties.HORIZONTAL_AXIS), 21, Direction.Axis.Y, 21, posx -> destination.getBlockState(posx) == blockState
                                            );
                                        }, destination.getServer());
                            }, destination.getServer())
                            .collect(Collectors.toCollection(() -> new ReferenceArrayList<>(1)))
                            .thenApply(list -> list.isEmpty() ? Optional.empty() : Optional.of(list.get(0)));
                }, destination.getServer());
    }

}
