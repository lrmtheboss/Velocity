/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_8;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.UuidUtils;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.network.protocol.StateRegistry;
import com.velocitypowered.proxy.network.protocol.packet.LoginAcknowledged;
import com.velocitypowered.proxy.network.protocol.packet.ServerLoginSuccess;
import com.velocitypowered.proxy.network.protocol.packet.SetCompression;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.permission.PermissionChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A session handler that is activated to complete the login phase.
 */
public class AuthSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(AuthSessionHandler.class);

  private final VelocityServer server;
  private final MinecraftConnection mcConnection;
  private final LoginInboundConnection inbound;
  private GameProfile profile;
  private @MonotonicNonNull ConnectedPlayer connectedPlayer;
  private final boolean onlineMode;
  private State loginState = State.START; // 1.20.2+

  AuthSessionHandler(VelocityServer server, LoginInboundConnection inbound,
      GameProfile profile, boolean onlineMode) {
    this.server = Preconditions.checkNotNull(server, "server");
    this.inbound = Preconditions.checkNotNull(inbound, "inbound");
    this.profile = Preconditions.checkNotNull(profile, "profile");
    this.onlineMode = onlineMode;
    this.mcConnection = inbound.delegatedConnection();
  }

  @Override
  public void activated() {
    // Some connection types may need to alter the game profile.
    profile = mcConnection.getType().addGameProfileTokensIfRequired(profile,
        server.configuration().getPlayerInfoForwardingMode());
    GameProfileRequestEvent profileRequestEvent = new GameProfileRequestEvent(inbound, profile,
        onlineMode);
    final GameProfile finalProfile = profile;

    server.eventManager().fire(profileRequestEvent).thenComposeAsync(profileEvent -> {
      if (mcConnection.isClosed()) {
        // The player disconnected after we authenticated them.
        return CompletableFuture.completedFuture(null);
      }

      // Initiate a regular connection and move over to it.
      ConnectedPlayer player = new ConnectedPlayer(server, profileEvent.profileToUse(),
          mcConnection, inbound.virtualHost().orElse(null), onlineMode,
          inbound.identifiedKey());
      this.connectedPlayer = player;
      if (!server.canRegisterConnection(player)) {
        player.disconnect0(
            Component.translatable("velocity.error.already-connected-proxy", NamedTextColor.RED),
            true);
        return CompletableFuture.completedFuture(null);
      }

      logger.info("{} has connected", player);

      return server.eventManager()
          .fire(new PermissionsSetupEvent(player, ConnectedPlayer.DEFAULT_PERMISSIONS))
          .thenAcceptAsync(event -> {
            if (!mcConnection.isClosed()) {
              // wait for permissions to load, then set the players permission function
              final PermissionChecker checker = event.createChecker(player);
              if (checker == null) {
                logger.error(
                    "A plugin permission provider {} provided an invalid permission function"
                        + " for player {}. This is a bug in the plugin, not in Velocity. Falling"
                        + " back to the default permission function.",
                    event.provider().getClass().getName(),
                    player.username());
              } else {
                player.setPermissionChecker(checker);
              }
              startLoginCompletion(player);
            }
          }, mcConnection.eventLoop());
    }, mcConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception during connection of {}", finalProfile, ex);
      return null;
    });
  }

  private void startLoginCompletion(ConnectedPlayer player) {
    int threshold = server.configuration().getCompressionThreshold();
    if (threshold >= 0 && mcConnection.getProtocolVersion().compareTo(MINECRAFT_1_8) >= 0) {
      mcConnection.write(new SetCompression(threshold));
      mcConnection.setCompressionThreshold(threshold);
    }
    VelocityConfiguration configuration = server.configuration();
    UUID playerUniqueId = player.uuid();
    if (configuration.getPlayerInfoForwardingMode() == PlayerInfoForwarding.NONE) {
      playerUniqueId = UuidUtils.generateOfflinePlayerUuid(player.username());
    }

    if (player.identifiedKey() != null) {
      IdentifiedKey playerKey = player.identifiedKey();
      if (playerKey.signatureHolder() == null) {
        if (playerKey instanceof IdentifiedKeyImpl) {
          IdentifiedKeyImpl unlinkedKey = (IdentifiedKeyImpl) playerKey;
          // Failsafe
          if (!unlinkedKey.internalAddHolder(player.uuid())) {
            if (onlineMode) {
              inbound.disconnect(
                  Component.translatable("multiplayer.disconnect.invalid_public_key"));
              return;
            } else {
              logger.warn("Key for player " + player.username() + " could not be verified!");
            }
          }
        } else {
          logger.warn("A custom key type has been set for player " + player.username());
        }
      } else {
        if (!Objects.equals(playerKey.signatureHolder(), playerUniqueId)) {
          logger.warn("UUID for Player " + player.username() + " mismatches! "
              + "Chat/Commands signatures will not work correctly for this player!");
        }
      }
    }

    completeLoginProtocolPhaseAndInitialize(player);
  }

  @Override
  public boolean handle(LoginAcknowledged packet) {
    if (loginState != State.SUCCESS_SENT) {
      inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_player_data"));
    } else {
      loginState = State.ACKNOWLEDGED;
      mcConnection.setActiveSessionHandler(StateRegistry.CONFIG,
          new ClientConfigSessionHandler(server, connectedPlayer));

      server.eventManager().fire(new PostLoginEvent(connectedPlayer))
          .thenCompose((ignored) -> connectToInitialServer(connectedPlayer)).exceptionally((ex) -> {
            logger.error("Exception while connecting {} to initial server", connectedPlayer, ex);
            return null;
          });
    }
    return true;
  }

  private void completeLoginProtocolPhaseAndInitialize(ConnectedPlayer player) {
    mcConnection.setAssociation(player);

    server.eventManager().fire(new LoginEvent(player)).thenAcceptAsync(event -> {
      if (mcConnection.isClosed()) {
        // The player was disconnected
        server.eventManager().fireAndForget(new DisconnectEvent(player,
            DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE));
        return;
      }

      @Nullable Component explanation = event.result().explanation();
      if (explanation != null) {
        player.disconnect0(explanation, true);
      } else {
        if (!server.registerConnection(player)) {
          player.disconnect0(Component.translatable("velocity.error.already-connected-proxy"),
              true);
          return;
        }

        ServerLoginSuccess success = new ServerLoginSuccess();
        success.setUsername(player.username());
        success.setProperties(player.profileProperties());
        success.setUuid(player.uuid());
        mcConnection.write(success);

        loginState = State.SUCCESS_SENT;
        if (inbound.protocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_2) < 0) {
          loginState = State.ACKNOWLEDGED;
          mcConnection.setActiveSessionHandler(StateRegistry.PLAY,
              new InitialConnectSessionHandler(player, server));
          server.eventManager().fire(new PostLoginEvent(player))
              .thenCompose((ignored) -> connectToInitialServer(player)).exceptionally((ex) -> {
                logger.error("Exception while connecting {} to initial server", player, ex);
                return null;
              });
        }
      }
    }, mcConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception while completing login initialisation phase for {}", player, ex);
      return null;
    });
  }

  private CompletableFuture<Void> connectToInitialServer(ConnectedPlayer player) {
    Optional<RegisteredServer> initialFromConfig = player.getNextServerToTry();
    PlayerChooseInitialServerEvent event =
        new PlayerChooseInitialServerEvent(player, initialFromConfig.orElse(null));

    return server.eventManager().fire(event).thenRunAsync(() -> {
      Optional<RegisteredServer> toTry = event.getInitialServer();
      if (!toTry.isPresent()) {
        player.disconnect0(
            Component.translatable("velocity.error.no-available-servers", NamedTextColor.RED),
            true);
        return;
      }
      player.createConnectionRequest(toTry.get()).fireAndForget();
    }, mcConnection.eventLoop());
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    mcConnection.close(true);
  }

  @Override
  public void disconnected() {
    if (connectedPlayer != null) {
      connectedPlayer.teardown();
    }
    this.inbound.cleanup();
  }

  static enum State {
    START, SUCCESS_SENT, ACKNOWLEDGED
  }
}
