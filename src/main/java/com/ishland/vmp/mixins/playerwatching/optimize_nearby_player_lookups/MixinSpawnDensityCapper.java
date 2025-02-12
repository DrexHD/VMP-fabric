package com.ishland.vmp.mixins.playerwatching.optimize_nearby_player_lookups;

import com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager;
import com.ishland.vmp.common.playerwatching.TACSExtension;
import com.ishland.vmp.mixins.access.IThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.SpawnDensityCapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;

@Mixin(value = SpawnDensityCapper.class, priority = 950)
public abstract class MixinSpawnDensityCapper {

    @Shadow
    @Final
    private ServerChunkLoadingManager chunkLoadingManager;

    @Mutable
    @Shadow @Final private Map<ServerPlayerEntity, SpawnDensityCapper.DensityCap> playersToDensityCap;
    private static final Function<ServerPlayerEntity, SpawnDensityCapper.DensityCap> newDensityCap = ignored -> new SpawnDensityCapper.DensityCap();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.playersToDensityCap = new Reference2ReferenceOpenHashMap<>();
    }

    @Unique
    private Object[] getMobSpawnablePlayersArray(ChunkPos chunkPos) {
        final AreaPlayerChunkWatchingManager manager = ((TACSExtension) this.chunkLoadingManager).getAreaPlayerChunkWatchingManager();
        return manager.getPlayersInGeneralAreaMap(chunkPos.toLong());
    }

    private static double sqrDistance(double x1, double y1, double x2, double y2) {
        final double dx = x1 - x2;
        final double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * @author ishland
     * @reason optimize & reduce allocations
     */
    @Overwrite
    public void increaseDensity(ChunkPos chunkPos, SpawnGroup spawnGroup) {
        final double centerX = chunkPos.getCenterX();
        final double centerZ = chunkPos.getCenterZ();
        for(Object _player : this.getMobSpawnablePlayersArray(chunkPos)) {
            if (_player instanceof ServerPlayerEntity player && !player.isSpectator() && sqrDistance(centerX, centerZ, player.getX(), player.getZ()) <= 16384.0D) {
                this.playersToDensityCap.computeIfAbsent(player, newDensityCap).increaseDensity(spawnGroup);
            }
        }
    }

    /**
     * @author ishland
     * @reason optimize & reduce allocations
     */
    @Overwrite
    public boolean canSpawn(SpawnGroup spawnGroup, ChunkPos chunkPos) {
        final double centerX = chunkPos.getCenterX();
        final double centerZ = chunkPos.getCenterZ();
        for(Object _player : this.getMobSpawnablePlayersArray(chunkPos)) {
            if (_player instanceof ServerPlayerEntity player && !player.isSpectator() && sqrDistance(centerX, centerZ, player.getX(), player.getZ()) <= 16384.0D) {
                SpawnDensityCapper.DensityCap densityCap = this.playersToDensityCap.get(player);
                if (densityCap == null || densityCap.canSpawn(spawnGroup)) {
                    return true;
                }
            }
        }

        return false;
    }

}
