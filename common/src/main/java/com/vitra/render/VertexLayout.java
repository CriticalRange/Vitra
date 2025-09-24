package com.vitra.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the layout of vertex data in a vertex buffer
 * Used to tell the renderer how to interpret vertex data
 */
public class VertexLayout {

    private final List<VertexAttribute> attributes;
    private int stride;

    public VertexLayout() {
        this.attributes = new ArrayList<>();
        this.stride = 0;
    }

    /**
     * Add a vertex attribute to the layout
     * @param type Type of the attribute (position, normal, etc.)
     * @param format Data format of the attribute
     * @param count Number of components (1-4)
     * @return This layout for chaining
     */
    public VertexLayout add(VertexAttributeType type, VertexFormat format, int count) {
        attributes.add(new VertexAttribute(type, format, count, stride));
        stride += format.getSizeInBytes() * count;
        return this;
    }

    public List<VertexAttribute> getAttributes() {
        return attributes;
    }

    public int getStride() {
        return stride;
    }

    /**
     * Create a standard position + color layout
     */
    public static VertexLayout createPositionColor() {
        return new VertexLayout()
                .add(VertexAttributeType.POSITION, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.COLOR, VertexFormat.FLOAT, 4);
    }

    /**
     * Create a standard position + texture coordinate layout
     */
    public static VertexLayout createPositionTexture() {
        return new VertexLayout()
                .add(VertexAttributeType.POSITION, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.TEXCOORD0, VertexFormat.FLOAT, 2);
    }

    /**
     * Create a standard position + normal + texture coordinate layout
     */
    public static VertexLayout createPositionNormalTexture() {
        return new VertexLayout()
                .add(VertexAttributeType.POSITION, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.NORMAL, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.TEXCOORD0, VertexFormat.FLOAT, 2);
    }

    /**
     * Create a full-featured vertex layout for complex meshes
     */
    public static VertexLayout createFull() {
        return new VertexLayout()
                .add(VertexAttributeType.POSITION, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.NORMAL, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.TANGENT, VertexFormat.FLOAT, 3)
                .add(VertexAttributeType.TEXCOORD0, VertexFormat.FLOAT, 2)
                .add(VertexAttributeType.TEXCOORD1, VertexFormat.FLOAT, 2)
                .add(VertexAttributeType.COLOR, VertexFormat.FLOAT, 4);
    }

    public static class VertexAttribute {
        private final VertexAttributeType type;
        private final VertexFormat format;
        private final int count;
        private final int offset;

        public VertexAttribute(VertexAttributeType type, VertexFormat format, int count, int offset) {
            this.type = type;
            this.format = format;
            this.count = count;
            this.offset = offset;
        }

        public VertexAttributeType getType() { return type; }
        public VertexFormat getFormat() { return format; }
        public int getCount() { return count; }
        public int getOffset() { return offset; }
    }

    public enum VertexAttributeType {
        POSITION,
        NORMAL,
        TANGENT,
        BITANGENT,
        TEXCOORD0,
        TEXCOORD1,
        TEXCOORD2,
        TEXCOORD3,
        COLOR,
        BLENDWEIGHT,
        BLENDINDICES
    }

    public enum VertexFormat {
        FLOAT(4),
        INT(4),
        SHORT(2),
        BYTE(1);

        private final int sizeInBytes;

        VertexFormat(int sizeInBytes) {
            this.sizeInBytes = sizeInBytes;
        }

        public int getSizeInBytes() {
            return sizeInBytes;
        }
    }
}