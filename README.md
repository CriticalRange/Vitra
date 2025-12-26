# Vitra - Multi-Backend Minecraft Optimization Mod

Vitra is a high-performance optimization and multi-backend rendering mod for Minecraft Java Edition. It provides significant performance improvements through advanced rendering techniques and supports multiple graphics APIs including OpenGL, DirectX 12, and Vulkan.

## Features

### Multi-Backend Rendering
- **OpenGL** - Maximum compatibility, default renderer
- **DirectX 12** - Windows-only, modern features and performance
- **Vulkan** - Cross-platform, maximum performance and efficiency
- **Software** - CPU-based fallback renderer

### Performance Optimizations
- **BGFX Rendering Backend** - High-performance multi-API rendering system
- **Optimized Buffer Management** - Efficient GPU buffer allocation and caching
- **Advanced Shader System** - Modern shader compilation and management
- **Draw Call Optimization** - Batched rendering for improved performance

### Advanced Features
- Runtime backend switching
- Comprehensive configuration system
- Debug information and statistics
- Shader hot-reloading support
- Compatible with Fabric

## Installation

1. Download the mod file: `vitra-<version>.jar`

2. Place the mod file in your `mods` directory

3. Launch Minecraft - Vitra will automatically initialize with optimal settings

## Configuration

Vitra creates a configuration file at `config/vitra.properties` with the following options:

```properties
# Rendering Configuration
renderer.type=DIRECTX12  # Options: OPENGL, DIRECTX12, VULKAN, SOFTWARE
renderer.vsync=true
renderer.maxFPS=144
renderer.debug=false

# BGFX Configuration
bgfx.resetFlags=VSYNC
bgfx.debugFlags=TEXT
```

## Development

### Building from Source

```bash
# Clone the repository
git clone <repository-url>
cd vitra

# Build the mod
./gradlew build
```

### Testing

```bash
# Run Minecraft client
./gradlew runClient
```

## Architecture

### Core Components

1. **VitraMod** - Main mod entry point and lifecycle management
2. **VitraCore** - Core optimization and management systems
3. **VitraRenderer** - Multi-backend rendering system
4. **RenderContext** - Backend-agnostic rendering interface

### Module Structure

```
src/main/java/com/vitra/
├── config/              # Configuration management
├── core/                # Core optimization systems
├── fabric/              # Fabric-specific entry points
│   ├── client/          # Client initialization
│   └── VitraModFabric.java
├── mixin/               # Mixin transformations
├── render/
│   ├── backend/         # BGFX backend implementations
│   ├── bgfx/            # BGFX rendering system
│   └── VitraRenderer.java
└── VitraMod.java        # Main mod class
```

### Key Rendering Components

- **BgfxCommandEncoder** - Command buffer management and encoding
- **BgfxShaderManager** - Shader compilation and pipeline management
- **BgfxTextureManager** - Texture creation and binding
- **BgfxBufferCache** - Efficient vertex/index buffer pooling

## Compatibility

### Minecraft Versions
- Minecraft 1.21.8+
- Java 21+

### Mod Loaders
- Fabric 0.17.2+

### Graphics APIs
- OpenGL 3.3+ (all platforms)
- DirectX 12 (Windows 10+)
- Vulkan 1.0+ (with compatible drivers)

## Performance Tips

1. **Choose the Right Backend**
   - Vulkan: Best performance on modern systems
   - DirectX 12: Good performance on Windows
   - OpenGL: Maximum compatibility

2. **Optimize Settings**
   - Configure BGFX debug flags for performance monitoring
   - Enable VSync for smoother frame pacing
   - Adjust max FPS based on your display

3. **Monitor Performance**
   - Enable debug mode to see optimization statistics
   - Use the built-in performance overlay
   - Check logs for performance warnings

## Troubleshooting

### Common Issues

**Vitra fails to initialize**
- Ensure you have Java 21+
- Check that LWJGL natives are properly loaded
- Verify your graphics drivers are up to date

**Poor performance**
- Try different backend types
- Adjust optimization settings in config
- Check system resource usage

**Rendering issues**
- Disable shader packs temporarily
- Try software renderer as fallback
- Check graphics driver compatibility

### Debug Information

Enable debug mode in configuration to get detailed information:
```properties
renderer.debug=true
```

This will show:
- Active renderer backend
- Optimization statistics
- Memory usage information
- Performance metrics

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with appropriate tests
4. Submit a pull request

### Development Guidelines

- Follow existing code style and conventions
- Add comprehensive comments for complex systems
- Include unit tests for new functionality
- Update documentation for user-facing changes

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Credits

- Built with [LWJGL](https://lwjgl.org/) for graphics API access
- Uses [BGFX](https://github.com/bkaradzic/bgfx) for multi-backend rendering
- Built on [Fabric](https://fabricmc.net/) mod loader

## Support

- Report issues on [GitHub Issues](https://github.com/your-repo/vitra/issues)
- Join our [Discord](https://discord.gg/your-discord) for community support
- Check the [Wiki](https://github.com/your-repo/vitra/wiki) for detailed documentation