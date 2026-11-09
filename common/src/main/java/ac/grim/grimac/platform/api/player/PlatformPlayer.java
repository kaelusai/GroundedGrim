package ac.grim.grimac.platform.api.player;

import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.platform.api.sender.Sender;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3d;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public interface PlatformPlayer extends GrimEntity, OfflinePlatformPlayer {
    void kickPlayer(String textReason);

    void resyncSharedFlags();

    boolean hasPermission(String s);

    boolean hasPermission(String s, boolean defaultIfUnset);

    void sendMessage(String message);

    void sendMessage(Component message);

    void updateInventory();

    Vector3d getPosition();

    PlatformInventory getInventory();

    @Nullable GrimEntity getVehicle();

    GameMode getGameMode();

    void setGameMode(GameMode gameMode);

    default boolean isFlying() {
        return false;
    }

    void resetFallDistance();

    boolean isExternalPlayer();

    // Vanish plugins hide staff through the platform's own visibility API. Platforms without one see everyone.
    default boolean canSee(PlatformPlayer other) {
        return true;
    }

    // Negative when the platform cannot tell, which callers must treat as "do not know"
    default double getHealth() {
        return -1;
    }

    void sendPluginMessage(String channelName, byte[] byteArray);

    Sender getSender();

    /*
     * Replaces native player reference in PlatformPlayer implementation with a new object
     * Vanilla MC replaces ServerPlayerEntity references on respawn and dimension change
     */
    default void replaceNativePlayer(Object nativePlayerObject) {}

    BlockTranslator getBlockTranslator();
}
