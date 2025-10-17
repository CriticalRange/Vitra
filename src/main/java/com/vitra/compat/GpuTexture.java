package com.vitra.compat;

/**
 * Compatibility class for GpuTexture which may not exist in all Minecraft versions
 * This provides the correct interface for GpuTexture functionality
 */
public class GpuTexture {
    private long directXHandle;
    private int width;
    private int height;
    private int format;
    private boolean valid = false;
    private String name;

    public GpuTexture() {
        this.directXHandle = 0;
        this.valid = false;
    }

    public GpuTexture(long directXHandle, String name) {
        this.directXHandle = directXHandle;
        this.name = name;
        this.valid = directXHandle != 0;
    }

    public GpuTexture(long directXHandle, int width, int height, int format) {
        this.directXHandle = directXHandle;
        this.width = width;
        this.height = height;
        this.format = format;
        this.valid = directXHandle != 0;
    }

    public long getDirectXHandle() {
        return directXHandle;
    }

    public void setDirectXHandle(long directXHandle) {
        this.directXHandle = directXHandle;
        this.valid = directXHandle != 0;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public boolean isValid() {
        return valid && directXHandle != 0;
    }

    public void invalidate() {
        this.valid = false;
        this.directXHandle = 0;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return String.format("GpuTexture[handle=0x%s, size=%dx%d, format=%d, valid=%s]",
                           Long.toHexString(directXHandle), width, height, format, valid);
    }
}