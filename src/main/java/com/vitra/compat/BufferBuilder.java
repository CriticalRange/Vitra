package com.vitra.compat;

import org.jetbrains.annotations.Nullable;

/**
 * Compatibility class for BufferBuilder inner classes that may not exist in all Minecraft versions
 * This provides the correct interface for BufferBuilder functionality
 */
public class BufferBuilder {

    /**
     * Compatibility class for DrawState
     */
    public static class DrawState {
        private final int mode;
        private final int vertexCount;
        private final int indexCount;

        public DrawState(int mode, int vertexCount, int indexCount) {
            this.mode = mode;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
        }

        public int mode() {
            return mode;
        }

        public int vertexCount() {
            return vertexCount;
        }

        public int indexCount() {
            return indexCount;
        }

        @Override
        public String toString() {
            return String.format("DrawState[mode=%d, vertexCount=%d, indexCount=%d]",
                               mode, vertexCount, indexCount);
        }
    }

    /**
     * Compatibility class for RenderedBuffer
     */
    public static class RenderedBuffer {
        private final DrawState drawState;
        private final int vertexCount;
        @Nullable private final Object result; // Could be ByteBuffer or other data

        public RenderedBuffer(DrawState drawState, int vertexCount, @Nullable Object result) {
            this.drawState = drawState;
            this.vertexCount = vertexCount;
            this.result = result;
        }

        public DrawState drawState() {
            return drawState;
        }

        public int vertexCount() {
            return vertexCount;
        }

        @Nullable
        public Object result() {
            return result;
        }

        @Override
        public String toString() {
            return String.format("RenderedBuffer[vertexCount=%d, drawState=%s]",
                               vertexCount, drawState);
        }
    }

    /**
     * VertexFormat compatibility
     */
    public static class VertexFormat {
        public enum Mode {
            QUADS(0),
            TRIANGLES(1),
            TRIANGLE_STRIP(2),
            TRIANGLE_FAN(3),
            LINES(4),
            LINE_STRIP(5);

            private final int value;

            Mode(int value) {
                this.value = value;
            }

            public int value() {
                return value;
            }
        }

        private final String name;

        public VertexFormat(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return String.format("VertexFormat[%s]", name);
        }
    }
}