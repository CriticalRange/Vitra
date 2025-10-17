package com.vitra.mixin.chunk;

import com.vitra.render.chunk.ChunkStatusMap;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public class ClientChunkCacheM {

    @Inject(method = "replaceWithPacketData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V"))
    private void setChunkStatus(int x, int z, FriendlyByteBuf friendlyByteBuf, CompoundTag compoundTag, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer, CallbackInfoReturnable<@Nullable LevelChunk> cir) {
        // Integration with DirectX 11 chunk status tracking
        ChunkStatusMap.setChunkStatusStatic(x, z, ChunkStatusMap.DATA_READY);
    }

    @Inject(method = "drop", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;replace(ILnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private void resetChunkStatus(ChunkPos chunkPos, CallbackInfo ci) {
        // Reset DirectX 11 chunk status when chunk is dropped
        ChunkStatusMap.resetChunkStatusStatic(chunkPos.x, chunkPos.z, ChunkStatusMap.DATA_READY);
    }
}