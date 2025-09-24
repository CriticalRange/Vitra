package com.vitra.core.optimization.lod;

/**
 * Level of Detail enumeration
 * Defines different quality levels for rendering based on distance
 */
public enum LODLevel {
    /**
     * Highest detail level - full geometry, all textures, all effects
     * Used for objects close to the camera
     */
    HIGH(1.0f, "High"),

    /**
     * Medium detail level - reduced geometry, compressed textures, some effects disabled
     * Used for objects at medium distance
     */
    MEDIUM(0.6f, "Medium"),

    /**
     * Low detail level - heavily simplified geometry, low-res textures, minimal effects
     * Used for objects far from the camera
     */
    LOW(0.3f, "Low"),

    /**
     * No rendering - object is too far to be visible or relevant
     * Used for very distant objects that would not contribute to the final image
     */
    NONE(0.0f, "None");

    private final float qualityMultiplier;
    private final String displayName;

    LODLevel(float qualityMultiplier, String displayName) {
        this.qualityMultiplier = qualityMultiplier;
        this.displayName = displayName;
    }

    /**
     * Get the quality multiplier for this LOD level
     * Can be used to scale various rendering parameters
     * @return Quality multiplier (0.0 to 1.0)
     */
    public float getQualityMultiplier() {
        return qualityMultiplier;
    }

    /**
     * Get the display name for this LOD level
     * @return Human-readable name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if this LOD level should be rendered
     * @return true if the object should be rendered
     */
    public boolean shouldRender() {
        return this != NONE;
    }

    /**
     * Get the texture resolution scale for this LOD level
     * @param baseResolution Base texture resolution
     * @return Scaled resolution
     */
    public int getTextureResolution(int baseResolution) {
        if (this == NONE) return 0;
        return Math.max(1, (int)(baseResolution * qualityMultiplier));
    }

    /**
     * Get the geometry complexity scale for this LOD level
     * @param baseVertexCount Base vertex count
     * @return Scaled vertex count
     */
    public int getVertexCount(int baseVertexCount) {
        if (this == NONE) return 0;
        return Math.max(1, (int)(baseVertexCount * qualityMultiplier));
    }

    /**
     * Check if shadows should be rendered for this LOD level
     * @return true if shadows should be rendered
     */
    public boolean shouldRenderShadows() {
        return this == HIGH || this == MEDIUM;
    }

    /**
     * Check if reflections should be rendered for this LOD level
     * @return true if reflections should be rendered
     */
    public boolean shouldRenderReflections() {
        return this == HIGH;
    }

    /**
     * Check if particle effects should be rendered for this LOD level
     * @return true if particle effects should be rendered
     */
    public boolean shouldRenderParticles() {
        return this == HIGH || this == MEDIUM;
    }

    /**
     * Get the animation update frequency for this LOD level
     * @param baseFrequency Base animation frequency (updates per second)
     * @return Scaled animation frequency
     */
    public float getAnimationFrequency(float baseFrequency) {
        if (this == NONE) return 0.0f;
        return baseFrequency * qualityMultiplier;
    }
}