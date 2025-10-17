package com.vitra.compat;

/**
 * Compatibility class for GpuTextureView which may not exist in all Minecraft versions
 * This provides the correct interface for GpuTextureView functionality
 */
public class GpuTextureView {
    private GpuTexture texture;
    private int baseMipLevel;
    private int mipLevelCount;
    private boolean valid = false;

    public GpuTextureView() {
        this.valid = false;
    }

    public GpuTextureView(GpuTexture texture, int baseMipLevel, int mipLevelCount) {
        this.texture = texture;
        this.baseMipLevel = baseMipLevel;
        this.mipLevelCount = mipLevelCount;
        this.valid = texture != null && texture.isValid();
    }

    public GpuTextureView(GpuTexture texture) {
        this(texture, 0, 1);
    }

    public GpuTexture getTexture() {
        return texture;
    }

    public void setTexture(GpuTexture texture) {
        this.texture = texture;
        this.valid = texture != null && texture.isValid();
    }

    public int getBaseMipLevel() {
        return baseMipLevel;
    }

    public void setBaseMipLevel(int baseMipLevel) {
        this.baseMipLevel = baseMipLevel;
    }

    public int getMipLevelCount() {
        return mipLevelCount;
    }

    public void setMipLevelCount(int mipLevelCount) {
        this.mipLevelCount = mipLevelCount;
    }

    public boolean isValid() {
        return valid && texture != null && texture.isValid();
    }

    public void invalidate() {
        this.valid = false;
    }

    public long getDirectXHandle() {
        return texture != null ? texture.getDirectXHandle() : 0;
    }

    @Override
    public String toString() {
        return String.format("GpuTextureView[texture=%s, baseMip=%d, mipCount=%d, valid=%s]",
                           texture, baseMipLevel, mipLevelCount, valid);
    }
}