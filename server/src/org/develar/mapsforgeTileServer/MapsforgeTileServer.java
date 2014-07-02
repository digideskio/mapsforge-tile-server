package org.develar.mapsforgeTileServer;

import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import org.develar.mapsforgeTileServer.pixi.PixiGraphicFactory;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.rendertheme.renderinstruction.RenderInstruction;
import org.mapsforge.map.rendertheme.rule.MatchingCacheKey;
import org.mapsforge.map.rendertheme.rule.RenderTheme;
import org.mapsforge.map.rendertheme.rule.RenderThemeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MapsforgeTileServer {
  static final Logger LOG = LoggerFactory.getLogger(MapsforgeTileServer.class);
  static final GraphicFactory AWT_GRAPHIC_FACTORY = new MyAwtGraphicFactory();

  private static final RenderThemeHandler.RenderThemeFactory RENDER_THEME_FACTORY = renderThemeBuilder -> {
    CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.<MatchingCacheKey, List<RenderInstruction>>newBuilder().maximumSize(1024);
    return new RenderTheme(renderThemeBuilder,
      cacheBuilder.<MatchingCacheKey, List<RenderInstruction>>build().asMap(),
      cacheBuilder.<MatchingCacheKey, List<RenderInstruction>>build().asMap());
  };

  final List<File> maps;
  final Map<String, RenderThemeItem> renderThemes;
  final DisplayModel displayModel;
  final RenderThemeItem defaultRenderTheme;

  public MapsforgeTileServer(@NotNull List<File> maps, @NotNull Path[] renderThemeFiles) throws IOException {
    this.maps = maps;

    displayModel = new DisplayModel();

    renderThemes = new LinkedHashMap<>();
    processPaths(renderThemeFiles, ".xml", 2, path -> {
      try {
        addRenderTheme(path, displayModel);
      }
      catch (IOException | XmlPullParserException e) {
        LOG.error(e.getMessage(), e);
      }
    });

    if (renderThemes.isEmpty()) {
      throw new IllegalStateException("No render theme specified");
    }

    String themeName = "elevate";
    RenderThemeItem defaultRenderTheme = renderThemes.get(themeName);
    if (defaultRenderTheme == null) {
      themeName = renderThemes.keySet().iterator().next();
      defaultRenderTheme = renderThemes.get(themeName);
    }

    LOG.info("Use " + themeName + " as default theme");

    this.defaultRenderTheme = defaultRenderTheme;
  }

  public static void main(String[] args) throws IOException {
    Options options = new Options();
    //printUsage(options);
    try {
      new CmdLineParser(options).parseArgument(args);
    }
    catch (CmdLineException e) {
      System.err.print(e.getMessage());
      System.exit(64);
    }

    List<File> maps = new ArrayList<>(options.maps.length);
    processPaths(options.maps, ".map", Integer.MAX_VALUE, path -> maps.add(path.toFile()));

    if (maps.isEmpty()) {
      LOG.error("No map specified");
      return;
    }

    MapsforgeTileServer mapsforgeTileServer;
    try {
      mapsforgeTileServer = new MapsforgeTileServer(maps, options.themes);
    }
    catch (IllegalStateException e) {
      LOG.error(e.getMessage());
      return;
    }

    mapsforgeTileServer.startServer(options);
  }

  private static void processPaths(@NotNull Path[] paths, @NotNull String ext, int maxDepth, @NotNull Consumer<Path> action) throws IOException {
    for (Path specifiedPath : paths) {
      if (!Files.exists(specifiedPath)) {
        throw new IllegalArgumentException("File does not exist: " + specifiedPath);
      }
      else if (!Files.isReadable(specifiedPath)) {
        throw new IllegalArgumentException("Cannot read file: " + specifiedPath);
      }
      else if (Files.isDirectory(specifiedPath)) {
        Files.walk(specifiedPath, maxDepth).filter(path -> !Files.isDirectory(path) && path.getFileName().toString().endsWith(ext)).forEachOrdered(action);
      }
      else {
        action.accept(specifiedPath);
      }
    }
  }

  @SuppressWarnings("UnusedDeclaration")
  private static void printUsage(@NotNull Options options) {
    new CmdLineParser(options).printUsage(new OutputStreamWriter(System.out), new ResourceBundle() {
      private final ImmutableMap<String, String> data = new ImmutableMap.Builder<String, String>()
        .put("FILE", "<path>")
        .put("PATH", "<path>")
        .put("VAL", "<string>")
        .put("N", " <int>")
        .build();

      @Override
      protected Object handleGetObject(@NotNull String key) {
        String value = data.get(key);
        return value == null ? key : value;
      }

      @NotNull
      @Override
      public Enumeration<String> getKeys() {
        return Iterators.asEnumeration(data.keySet().iterator());
      }
    });
  }

  private void addRenderTheme(@NotNull Path path, @NotNull DisplayModel displayModel) throws IOException, XmlPullParserException {
    String fileName = path.getFileName().toString();
    String name = fileName.substring(0, fileName.length() - ".xml".length()).toLowerCase(Locale.ENGLISH);
    ExternalRenderTheme xmlRenderTheme = new ExternalRenderTheme(path.toFile());
    String etag = name + "@" + Long.toUnsignedString(Files.getLastModifiedTime(path).toMillis(), 32);

    RenderTheme vectorRenderTheme = createRenderTheme(PixiGraphicFactory.INSTANCE, displayModel, xmlRenderTheme);
    // scale depends on zoom, but we cannot set it on each "render tile" invocation - render theme must be immutable,
    // it is client reponsibility to do scaling
    vectorRenderTheme.scaleStrokeWidth(1);

    renderThemes.put(name, new RenderThemeItem(createRenderTheme(AWT_GRAPHIC_FACTORY, displayModel, xmlRenderTheme), vectorRenderTheme, etag));
  }

  @NotNull
  private static RenderTheme createRenderTheme(@NotNull GraphicFactory graphicFactory, @NotNull DisplayModel displayModel, @NotNull ExternalRenderTheme xmlRenderTheme)
    throws IOException, XmlPullParserException {
    RenderTheme renderTheme = RenderThemeHandler.getRenderTheme(graphicFactory, displayModel, xmlRenderTheme, RENDER_THEME_FACTORY);
    renderTheme.scaleTextSize(1);
    return renderTheme;
  }

  private static long getAvailableMemory() {
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory(); // current heap allocated to the VM process
    long freeMemory = runtime.freeMemory(); // out of the current heap, how much is free
    long maxMemory = runtime.maxMemory(); // Max heap VM can use e.g. Xmx setting
    long usedMemory = totalMemory - freeMemory; // how much of the current heap the VM is using
    // available memory i.e. Maximum heap size minus the current amount used
    return maxMemory - usedMemory;
  }

  private void startServer(@NotNull Options options) throws IOException {
    Runtime runtime = Runtime.getRuntime();
    long freeMemory = runtime.freeMemory();
    long maxMemoryCacheSize = getAvailableMemory() - (64 * 1024 * 1024) /* leave 64MB for another stuff */;
    if (maxMemoryCacheSize <= 0) {
      LOG.error("Memory not enough, current free memory " + freeMemory + ", total memory " + runtime.totalMemory() + ", max memory " + runtime.maxMemory());
      return;
    }

    boolean isLinux = System.getProperty("os.name").toLowerCase(Locale.US).startsWith("linux");
    final EventLoopGroup eventGroup = isLinux ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    final ChannelRegistrar channelRegistrar = new ChannelRegistrar();

    final AtomicReference<Future<?>> eventGroupShutdownFeature = new AtomicReference<>();
    List<Runnable> shutdownHooks = new ArrayList<>(4);
    shutdownHooks.add(() -> {
      LOG.info("Shutdown server");
      try {
        channelRegistrar.closeAndSyncUninterruptibly();
      }
      finally {
        if (!eventGroupShutdownFeature.compareAndSet(null, eventGroup.shutdownGracefully())) {
          LOG.error("ereventGroupShutdownFeature was already set");
        }
      }
    });

    int executorCount = ((MultithreadEventExecutorGroup)eventGroup).executorCount();
    FileCacheManager fileCacheManager = options.maxFileCacheSize == 0 ? null : new FileCacheManager(options, executorCount, shutdownHooks);
    final TileHttpRequestHandler tileHttpRequestHandler = new TileHttpRequestHandler(this, fileCacheManager, executorCount, maxMemoryCacheSize, shutdownHooks);

    // task "sync eventGroupShutdownFeature only" must be last
    shutdownHooks.add(() -> eventGroupShutdownFeature.getAndSet(null).syncUninterruptibly());

    ServerBootstrap serverBootstrap = new ServerBootstrap();
    serverBootstrap.group(eventGroup)
      .channel(isLinux ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
      .childHandler(new ChannelInitializer<Channel>() {
        @Override
        public void initChannel(Channel channel) throws Exception {
          channel.pipeline().addLast(channelRegistrar);
          channel.pipeline().addLast(new HttpRequestDecoder(), new HttpObjectAggregator(1048576 * 10), new HttpResponseEncoder());
          channel.pipeline().addLast(tileHttpRequestHandler);
        }
      })
      .childOption(ChannelOption.SO_KEEPALIVE, true)
      .childOption(ChannelOption.TCP_NODELAY, true);

    InetSocketAddress address = options.host == null || options.host.isEmpty() ? new InetSocketAddress(InetAddress.getLoopbackAddress(), options.port) : new InetSocketAddress(options.host, options.port);
    Channel serverChannel = serverBootstrap.bind(address).syncUninterruptibly().channel();
    channelRegistrar.addServerChannel(serverChannel);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      for (Runnable shutdownHook : shutdownHooks) {
        try {
          shutdownHook.run();
        }
        catch (Throwable e) {
          LOG.error(e.getMessage(), e);
        }
      }
    }));

    LOG.info("Listening " + address.getHostName() + ":" + address.getPort());
    serverChannel.closeFuture().syncUninterruptibly();
  }
}