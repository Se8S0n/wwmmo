package au.com.codeka.warworlds.client.net;

import android.support.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Queue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import au.com.codeka.warworlds.client.App;
import au.com.codeka.warworlds.client.R;
import au.com.codeka.warworlds.client.concurrency.Threads;
import au.com.codeka.warworlds.client.game.world.ChatManager;
import au.com.codeka.warworlds.client.game.world.StarManager;
import au.com.codeka.warworlds.client.util.GameSettings;
import au.com.codeka.warworlds.client.game.world.EmpireManager;
import au.com.codeka.warworlds.common.Log;
import au.com.codeka.warworlds.common.debug.PacketDebug;
import au.com.codeka.warworlds.common.net.PacketDecoder;
import au.com.codeka.warworlds.common.net.PacketEncoder;
import au.com.codeka.warworlds.common.proto.HelloPacket;
import au.com.codeka.warworlds.common.proto.LoginRequest;
import au.com.codeka.warworlds.common.proto.LoginResponse;
import au.com.codeka.warworlds.common.proto.Packet;

import static com.google.common.base.Preconditions.checkNotNull;

/** Represents our connection to the server. */
public class Server {
  private final static Log log = new Log("Server");

  private static final int DEFAULT_RECONNECT_TIME_MS = 1000;
  private static final int MAX_RECONNECT_TIME_MS = 30000;

  private final PacketDispatcher packetDispatcher = new PacketDispatcher();
  @Nonnull private ServerStateEvent currState =
      new ServerStateEvent("", ServerStateEvent.ConnectionState.DISCONNECTED, null);

  /** The socket that's connected to the server. Null if we're not connected. */
  @Nullable private Socket gameSocket;

  /** The PacketEncoder we'll use to send packets. Null if we're not connected. */
  @Nullable private PacketEncoder packetEncoder;

  /** The PacketDecoder we'll use to receive packets. Null if we're not connected. */
  @Nullable private PacketDecoder packetDecoder;

  /** A queue for storing packets while we attempt to reconnect. Will be null if we're connected. */
  @Nullable private Queue<Packet> queuedPackets;

  /** A lock used to guard access to the web socket/queue. */
  private final Object lock = new Object();

  private int reconnectTimeMs = DEFAULT_RECONNECT_TIME_MS;

  /** Connect to the server. */
  public void connect() {
    GameSettings.i.addSettingChangedHandler(key -> {
      if (key == GameSettings.Key.SERVER) {
        // If you change SERVER, we'll want to clear the cookie.
        GameSettings.i.edit()
            .setString(GameSettings.Key.COOKIE, "")
            .commit();
      } else if (key == GameSettings.Key.COOKIE) {
        // We got a new cookie, try connecting again.
        disconnect();
      }
    });

    final String cookie = GameSettings.i.getString(GameSettings.Key.COOKIE);
    if (cookie.isEmpty()) {
      log.warning("No cookie yet, not connecting.");
      return;
    }

    updateState(ServerStateEvent.ConnectionState.CONNECTING, null);

    // First of all, we'll want to wait for firebase to authenticate the user (if they've logged
    // in previously).
    FirebaseAuth.getInstance().addAuthStateListener(authStateListener);
  }

  private final FirebaseAuth.AuthStateListener authStateListener = new FirebaseAuth.AuthStateListener() {
    @Override
    public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
      FirebaseUser user = firebaseAuth.getCurrentUser();
      login(user);

      firebaseAuth.removeAuthStateListener(authStateListener);
    }
  };

  /**
   * Connects to the server to log this user in.
   *
   * <p>The server will check the firebase user credentials (if supplied) are associated with the
   * empire cookie we've got saved. If everything checks out, it'll give us the hostname and port
   * to connect to.
   *
   * <p>If there's some sort of mismatch on the server, we may need to work something out.
   *
   * @param user The {@link FirebaseUser} you've authenticated as (can be null if you haven't
   *             authenticated with firebase yet -- that's OK, anonymous empires don't need to be
   *             authenticated).
   */
  private void login(@Nullable FirebaseUser user) {
    final String cookie = GameSettings.i.getString(GameSettings.Key.COOKIE);
    log.debug("Logging in with cookie: %s");

    if (user != null) {
      user.getToken(true).addOnCompleteListener(task -> {
        if (task.isSuccessful()) {
          String token = task.getResult().getToken();
          log.debug("Got firebase token for %s: %s", user.getEmail(), token);
          login(cookie, token);
        } else {
          // TODO: error getting token
          log.warning("Error getting token.", task.getException());
        }
      });
    } else {
      log.debug("Anonymous login, no user token.");
      login(cookie, null);
    }
  }

  private void login(@Nonnull String cookie, @Nullable String token) {
    App.i.getTaskRunner().runTask(() -> {
      log.info("Logging in: %s", ServerUrl.getLoginUrl());
      HttpRequest request = new HttpRequest.Builder()
          .url(ServerUrl.getLoginUrl())
          .method(HttpRequest.Method.POST)
          .body(new LoginRequest.Builder()
              .cookie(cookie)
              .token(token)
              .build().encode())
          .build();
      if (request.getResponseCode() != 200) {
        if (request.getResponseCode() >= 401 && request.getResponseCode() < 500) {
          // Our cookie must not be valid, we'll clear it before trying again.
          GameSettings.i.edit()
              .setString(GameSettings.Key.COOKIE, "")
              .commit();
        }
        log.error(
            "Error logging in, will try again: %d",
            request.getResponseCode(),
            request.getException());
        disconnect();
      } else {
        LoginResponse loginResponse = checkNotNull(request.getBody(LoginResponse.class));
        if (loginResponse.status != LoginResponse.LoginStatus.SUCCESS) {
          updateState(ServerStateEvent.ConnectionState.ERROR, loginResponse.status);
          log.error("Error logging in, got login status: %s", loginResponse.status);
          disconnect();
        } else {
          connectGameSocket(loginResponse);
        }
      }
    }, Threads.BACKGROUND);
  }

  private void connectGameSocket(LoginResponse loginResponse) {
    try {
      gameSocket = new Socket();
      String host = loginResponse.host;
      if (host == null) {
        host = ServerUrl.getHost();
      }
      gameSocket.connect(new InetSocketAddress(host, loginResponse.port));
      packetEncoder = new PacketEncoder(gameSocket.getOutputStream(), packetEncodeHandler);
      packetDecoder = new PacketDecoder(gameSocket.getInputStream(), packetDecodeHandler);

      Queue<Packet> oldQueuedPackets = queuedPackets;
      queuedPackets = null;

      send(new Packet.Builder()
          .hello(new HelloPacket.Builder()
              .empire_id(loginResponse.empire.id)
              .our_star_last_simulation(StarManager.i.getLastSimulationOfOurStar())
              .last_chat_time(ChatManager.i.getLastChatTime())
              .build())
          .build());

      EmpireManager.i.onHello(loginResponse.empire);

      reconnectTimeMs = DEFAULT_RECONNECT_TIME_MS;
      updateState(ServerStateEvent.ConnectionState.CONNECTED, loginResponse.status);

      while (oldQueuedPackets != null && !oldQueuedPackets.isEmpty()) {
        send(oldQueuedPackets.remove());
      }
    } catch (IOException e) {
      gameSocket = null;
      log.error("Error connecting game socket, will try again.", e);
      disconnect();
    }
  }

  @Nonnull
  public ServerStateEvent getCurrState() {
    return currState;
  }

  public void send(Packet pkt) {
    synchronized (lock) {
      if (queuedPackets != null) {
        queuedPackets.add(pkt);
      } else {
        checkNotNull(packetEncoder);
        try {
          packetEncoder.send(pkt);
        } catch (IOException e) {
          // TODO: handle error
          disconnect();
        }
      }
    }
  }

  private void disconnect() {
    synchronized (lock) {
      if (gameSocket != null) {
        try {
          gameSocket.close();
        } catch (IOException e) {
          // ignore.
        }
        gameSocket = null;
      }
      packetEncoder = null;
      packetDecoder = null;
      queuedPackets = new ArrayDeque<>();
    }

    if (currState.getState() != ServerStateEvent.ConnectionState.ERROR) {
      updateState(ServerStateEvent.ConnectionState.DISCONNECTED, null);
      App.i.getTaskRunner().runTask(() -> {
        reconnectTimeMs *= 2;
        if (reconnectTimeMs > MAX_RECONNECT_TIME_MS) {
          reconnectTimeMs = MAX_RECONNECT_TIME_MS;
        }

        connect();
      }, Threads.BACKGROUND, reconnectTimeMs);
    }
  }

  private final PacketEncoder.PacketHandler packetEncodeHandler =
      (packet, encodedSize) -> {
        String packetDebug = PacketDebug.getPacketDebug(packet, encodedSize);
        App.i.getEventBus().publish(new ServerPacketEvent(
            packet, encodedSize, ServerPacketEvent.Direction.Sent, packetDebug));
        log.debug(">> %s", packetDebug);
      };

  private final PacketDecoder.PacketHandler packetDecodeHandler =
      new PacketDecoder.PacketHandler() {
        @Override
        public void onPacket(PacketDecoder decoder, Packet pkt, int encodedSize) {
          String packetDebug = PacketDebug.getPacketDebug(pkt, encodedSize);
          App.i.getEventBus().publish(new ServerPacketEvent(
              pkt, encodedSize, ServerPacketEvent.Direction.Received, packetDebug));
          log.debug("<< %s", packetDebug);

          packetDispatcher.dispatch(pkt);
        }

        @Override
        public void onDisconnect() {
          // TODO: disconnected
        }
      };

  private void updateState(
      ServerStateEvent.ConnectionState state,
      @Nullable LoginResponse.LoginStatus loginStatus) {
    currState = new ServerStateEvent(ServerUrl.getUrl(), state, loginStatus);
    App.i.getEventBus().publish(currState);
  }
}
