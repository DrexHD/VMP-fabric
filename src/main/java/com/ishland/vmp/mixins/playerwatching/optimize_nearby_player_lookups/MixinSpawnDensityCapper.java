package com.ishland.vmp.mixins.playerwatching.optimize_nearby_player_lookups;

import com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager;
import com.ishland.vmp.mixins.access.IThreadedAnvilChunkStorage;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.PlayerChunkWatchingManager;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.SpawnDensityCapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.function.Function;

@Mixin(SpawnDensityCapper.class)
public class MixinSpawnDensityCapper {

    @Shadow @Final private Map<ServerPlayerEntity, SpawnDensityCapper.DensityCap> playersToDensityCap;
    @Shadow @Final private ThreadedAnvilChunkStorage threadedAnvilChunkStorage;
    private static final Function<ServerPlayerEntity, SpawnDensityCapper.DensityCap> newDensityCap = ignored -> new SpawnDensityCapper.DensityCap();

    /**
     * @author ishland
     * @reason optimize & reduce allocations
     */
    @Overwrite
    public void increaseDensity(ChunkPos chunkPos, SpawnGroup spawnGroup) {
        final AreaPlayerChunkWatchingManager watchingManager = (AreaPlayerChunkWatchingManager) ((IThreadedAnvilChunkStorage) this.threadedAnvilChunkStorage).getPlayerChunkWatchingManager();

        for (Object _player : watchingManager.getPlayersWatchingChunkArray(chunkPos.toLong())) {
            if (_player instanceof ServerPlayerEntity serverPlayerEntity) {
                this.playersToDensityCap.computeIfAbsent(serverPlayerEntity, newDensityCap).increaseDensity(spawnGroup);
            }
        }
    }

    /**
     * @author ishland
     * @reason optimize & reduce allocations
     */
    @Overwrite
    public boolean canSpawn(SpawnGroup spawnGroup, ChunkPos chunkPos) {
        final AreaPlayerChunkWatchingManager watchingManager = (AreaPlayerChunkWatchingManager) ((IThreadedAnvilChunkStorage) this.threadedAnvilChunkStorage).getPlayerChunkWatchingManager();

        for (Object _player : watchingManager.getPlayersWatchingChunkArray(chunkPos.toLong())) {
            if (_player instanceof ServerPlayerEntity serverPlayerEntity) {
                SpawnDensityCapper.DensityCap densityCap = this.playersToDensityCap.get(serverPlayerEntity);
                if (densityCap == null || densityCap.canSpawn(spawnGroup)) {
                    return true;
                }
            }
        }

        return false;
    }

}
