# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a multi-platform Minecraft mod project called "Vitra" built using Architectury API. The project supports both Fabric and NeoForge mod loaders for Minecraft 1.21.8, using Java 21.

## Architecture

The project follows Architectury's multi-platform structure:

- **common/**: Shared code between platforms containing the main mod logic (`com.vitra.ExampleMod`)
- **fabric/**: Fabric-specific implementation and client code
- **neoforge/**: NeoForge-specific implementation

The common module contains platform-agnostic code that gets compiled into both platform-specific builds. Each platform module depends on the common module and provides platform-specific entry points.

## Build System

Uses Gradle with Architectury Loom for Minecraft mod development:

- **Root project**: Manages shared configuration and properties
- **Subprojects**: Each platform (common, fabric, neoforge) has its own build.gradle
- **Shadow plugin**: Used for bundling dependencies into the final mod jars

## Development Commands

### Building
```bash
# Build all platforms
./gradlew build

# Build specific platform
./gradlew fabric:build
./gradlew neoforge:build
```

### Development Environment
```bash
# Run Minecraft client for testing (Fabric)
./gradlew fabric:runClient

# Run Minecraft client for testing (NeoForge)
./gradlew neoforge:runClient

# Generate IDE files
./gradlew genEclipseRuns
./gradlew genIntellijRuns
```

### Publishing
```bash
# Publish to local repository
./gradlew publishToMavenLocal

# Create distribution jars
./gradlew remapJar
```

## Key Configuration Files

- **gradle.properties**: Mod version, Minecraft version, and dependency versions
- **fabric/src/main/resources/fabric.mod.json**: Fabric mod metadata and entry points
- **neoforge/src/main/resources/META-INF/neoforge.mods.toml**: NeoForge mod metadata
- **common/src/main/resources/vitra.mixins.json**: Mixin configuration for both platforms

## Dependencies

- **Minecraft**: 1.21.8
- **Architectury API**: 17.0.8 (cross-platform compatibility layer)
- **Fabric Loader**: 0.17.2 (Fabric platform)
- **Fabric API**: 0.133.4+1.21.8 (Fabric platform)
- **NeoForge**: 21.8.33 (NeoForge platform)

## Important Notes

- Always test changes on both platforms before committing
- Common code should be platform-agnostic - use Architectury annotations for platform-specific behavior
- Entry points are defined in platform-specific metadata files, not in Java code directly
- The mod ID is "vitra" across all platforms