package ac.grim.grimac.testutil;

import ac.grim.grimac.events.packets.PacketWorldBorder;
import ac.grim.grimac.manager.CheckManager;
import ac.grim.grimac.manager.SetbackTeleportUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.enums.Pose;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

public final class GrimPlayerFixture {
    private GrimPlayerFixture() {
    }

    public static void initializePacketEvents() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_4);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
        Attributes.SCALE.getName();
    }

    public static GrimPlayer player() throws ReflectiveOperationException {
        GrimPlayer player = mock(GrimPlayer.class);
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_4);
        when(player.getMovementThreshold()).thenReturn(.0002);
        player.gamemode = GameMode.SURVIVAL;
        player.pose = Pose.STANDING;
        player.hasGravity = true;
        player.gravity = .08;
        set(player, "joinTime", 0L);
        set(player, "lastTransactionSent", new AtomicInteger(10));
        set(player, "lastTransactionReceived", new AtomicInteger(10));
        set(player, "packetStateData", new PacketStateData());
        CompensatedEntities entities = mock(CompensatedEntities.class);
        entities.self = mock(PacketEntitySelf.class);
        when(entities.self.getAttributeValue(Attributes.SCALE)).thenReturn(1.0);
        when(entities.getPotionLevelForPlayer(any())).thenReturn(OptionalInt.empty());
        set(player, "compensatedEntities", entities);
        CompensatedWorld world = mock(CompensatedWorld.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        set(world, "openShulkerBoxes", new HashSet<>());
        set(player, "compensatedWorld", world);
        SetbackTeleportUtil teleports = mock(SetbackTeleportUtil.class);
        teleports.hasAcceptedSpawnTeleport = true;
        when(player.getSetbackTeleportUtil()).thenReturn(teleports);
        CheckManager checks = mock(CheckManager.class);
        PacketWorldBorder border = mock(PacketWorldBorder.class);
        when(border.getMinX()).thenReturn(-30_000_000.0);
        when(border.getMinZ()).thenReturn(-30_000_000.0);
        when(border.getMaxX()).thenReturn(30_000_000.0);
        when(border.getMaxZ()).thenReturn(30_000_000.0);
        when(checks.get(PacketWorldBorder.class)).thenReturn(border);
        set(player, "checkManager", checks);
        return player;
    }

    public static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(object, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
