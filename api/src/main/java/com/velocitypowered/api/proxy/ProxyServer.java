/*
 * Copyright (C) 2018-2022 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.proxy;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.util.ProxyVersion;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;

/**
 * Provides an interface to a Minecraft server proxy.
 */
public interface ProxyServer extends Audience {

  /**
   * Shuts down the proxy, kicking players with the specified {@code reason}.
   *
   * @param reason message to kick online players with
   */
  void shutdown(net.kyori.adventure.text.Component reason);

  /**
   * Shuts down the proxy, kicking players with the default reason.
   */
  void shutdown();

  /**
   * Closes all listening endpoints for this server.
   * This includes the main minecraft listener and query channel.
   */
  void closeListeners();

  /**
   * Retrieves the player currently connected to this proxy by their Minecraft username. The search
   * is case-insensitive.
   *
   * @param username the username to search for
   * @return an {@link Optional} with the player, which may be empty
   */
  Optional<Player> player(String username);

  /**
   * Retrieves the player currently connected to this proxy by their Minecraft UUID.
   *
   * @param uuid the UUID
   * @return an {@link Optional} with the player, which may be empty
   */
  Optional<Player> player(UUID uuid);

  /**
   * Retrieves all players currently connected to this proxy. This call may or may not be a snapshot
   * of all players online.
   *
   * @return the players online on this proxy
   */
  Collection<Player> onlinePlayers();

  /**
   * Returns the number of players currently connected to this proxy.
   *
   * @return the players on this proxy
   */
  int onlinePlayerCount();

  /**
   * Retrieves a registered {@link RegisteredServer} instance by its name. The search is
   * case-insensitive.
   *
   * @param name the name of the server
   * @return the registered server, which may be empty
   */
  Optional<RegisteredServer> server(String name);

  /**
   * Retrieves all {@link RegisteredServer}s registered with this proxy.
   *
   * @return the servers registered with this proxy
   */
  Collection<RegisteredServer> registeredServers();

  /**
   * Matches all {@link Player}s whose names start with the provided partial name.
   *
   * @param partialName the partial name to check for
   * @return a collection of mathed {@link Player}s
   */
  Collection<Player> matchPlayer(String partialName);

  /**
   * Matches all {@link RegisteredServer}s whose names start with the provided partial name.
   *
   * @param partialName the partial name to check for
   * @return a collection of mathed {@link RegisteredServer}s
   */
  Collection<RegisteredServer> matchServer(String partialName);

  /**
   * Creates a raw {@link RegisteredServer} without tying it into the internal server map.
   *
   * @param server the server to register
   * @return the {@link RegisteredServer} implementation created by the provided {@link ServerInfo}.
   */
  RegisteredServer createRawRegisteredServer(ServerInfo server);

  /**
   * Registers a server with this proxy. A server with this name should not already exist.
   *
   * @param server the server to register
   * @return the newly registered server
   */
  RegisteredServer registerServer(ServerInfo server);

  /**
   * Unregisters this server from the proxy.
   *
   * @param server the server to unregister
   */
  void unregisterServer(ServerInfo server);

  /**
   * Returns an instance of {@link CommandSource} that can be used to determine if the command is
   * being invoked by the console or a console-like executor. Plugins that execute commands are
   * strongly urged to implement their own {@link CommandSource} instead of using the console
   * invoker.
   *
   * @return the console command invoker
   */
  ConsoleCommandSource console();

  /**
   * Gets the {@link PluginManager} instance.
   *
   * @return the plugin manager instance
   */
  PluginManager pluginManager();

  /**
   * Gets the {@link EventManager} instance.
   *
   * @return the event manager instance
   */
  EventManager eventManager();

  /**
   * Gets the {@link CommandManager} instance.
   *
   * @return the command manager
   */
  CommandManager commandManager();

  /**
   * Gets the {@link Scheduler} instance.
   *
   * @return the scheduler instance
   */
  Scheduler scheduler();

  /**
   * Gets the {@link ChannelRegistrar} instance.
   *
   * @return the channel registrar
   */
  ChannelRegistrar channelRegistrar();

  /**
   * Gets the address that this proxy is bound to. This does not necessarily indicate the external
   * IP address of the proxy.
   *
   * @return the address the proxy is bound to
   */
  SocketAddress boundAddress();

  /**
   * Gets the {@link ProxyConfig} instance.
   *
   * @return the proxy config
   */
  ProxyConfig configuration();

  /**
   * Returns the version of the proxy.
   *
   * @return the proxy version
   */
  ProxyVersion version();

  /**
   * Creates a builder to build a {@link ResourcePackInfo} instance for use with
   * {@link com.velocitypowered.api.proxy.Player#sendResourcePackOffer(ResourcePackInfo)}.
   *
   * <p>Note: The resource-pack location should always:
   * - Use HTTPS with a valid certificate.
   * - Be in a crawler-accessible location. Having it behind Cloudflare or other DoS/Bot/crawler
   *   protection may cause issues in downloading.
   * - Be on a web-server with enough bandwidth and reliable connection
   *   so the download does not time out or fail.</p>
   *
   * <p>Do also make sure that the resource pack is in the correct format for the version
   * of the client. It is also highly recommended to always provide the resource-pack SHA-1 hash
   * of the resource pack with {@link ResourcePackInfo.Builder#hash(byte[])}
   * whenever possible to save bandwidth. If a hash is present the client will first check
   * if it already has a resource pack by that hash cached.</p>
   *
   * @param url The url where the resource pack can be found
   * @return a ResourcePackInfo builder
   */
  ResourcePackInfo.Builder createResourcePackBuilder(String url);
}
