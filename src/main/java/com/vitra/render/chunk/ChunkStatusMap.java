package com.vitra.render.chunk;

public class ChunkStatusMap {
    public static final ChunkStatusMap INSTANCE = new ChunkStatusMap();
    public static final int DATA_READY = 1;

    public void setChunkStatus(int x, int z, int status) {
        // DirectX chunk status tracking implementation
    }

    public void resetChunkStatus(int x, int z, int status) {
        // DirectX chunk status reset implementation
    }

    // Static convenience methods
    public static void setChunkStatusStatic(int x, int z, int status) {
        INSTANCE.setChunkStatus(x, z, status);
    }

    public static void resetChunkStatusStatic(int x, int z, int status) {
        INSTANCE.resetChunkStatus(x, z, status);
    }
}