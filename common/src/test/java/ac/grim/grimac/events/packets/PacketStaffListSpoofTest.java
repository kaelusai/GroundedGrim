package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.platform.api.PlatformLoader;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayerFactory;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.connection.ConnectionUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.exception.PacketProcessException;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.chat.RemoteChatSession;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.PublicProfileKey;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketStaffListSpoofTest {
    private static final String STAFF_NAME = "Moderator";
    private static final String OTHER_NAME = "Regular";
    private static final Component PREFIX = Component.text("Mod ");

    private final UUID staffId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final UUID viewerId = UUID.randomUUID();
    private final User user = mock(User.class);
    private final PacketStaffListSpoof listener = new PacketStaffListSpoof();
    private final PlatformPlayer viewer = mock(PlatformPlayer.class);
    private final PlatformPlayer staff = mock(PlatformPlayer.class);
    private final Map<UUID, PlayerInfo> profiles = new HashMap<>();
    private final Map<String, Component> prefixes = new HashMap<>();
    private final Map<String, String> memberships = new HashMap<>();
    private ClientVersion version = ClientVersion.V_1_21_11;
    private ClientVersion serverVersion;
    private EmbeddedChannel channel;
    private int viaWrites;
    private PlatformLoader originalLoader;
    private int profileRemovals;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        GrimPlayerFixture.initializePacketEvents();
        EventManager manager = new EventManager();
        manager.registerListener(listener);
        when(PacketEvents.getAPI().getEventManager()).thenReturn(manager);
        when(PacketEvents.getAPI().getServerManager().getVersion()).thenAnswer(call ->
                (serverVersion == null ? version : serverVersion).toServerVersion());
        when(user.getUUID()).thenReturn(viewerId);
        when(user.getClientVersion()).thenAnswer(call -> version);
        when(user.getPreViaEncoderState()).thenReturn(ConnectionState.PLAY);
        doAnswer(call -> {
            PacketWrapper<?> packet = call.getArgument(0);
            assertEquals(PacketEvents.getAPI().getServerManager().getVersion(), packet.getServerVersion(),
                    "The normal PacketEvents sender takes server-format packets");
            assertEquals(packet.getPacketTypeData().getPacketType().getId(version), packet.getNativePacketId());
            ByteBuf buffer = encode(packet, packet.getNativePacketId());
            try {
                observe(buffer);
            } finally {
                buffer.release();
            }
            return null;
        }).when(user).sendPacketSilently(any(PacketWrapper.class));

        originalLoader = GrimAPI.INSTANCE.getLoader();
        PlatformLoader loader = mock(PlatformLoader.class);
        PlatformPlayerFactory factory = mock(PlatformPlayerFactory.class);
        when(loader.getPlatformPlayerFactory()).thenReturn(factory);
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "loader", loader);
        when(factory.getFromUUID(viewerId)).thenReturn(viewer);
        when(factory.getFromUUID(staffId)).thenReturn(staff);
        when(factory.getFromName(STAFF_NAME)).thenReturn(staff);
        when(staff.getUniqueId()).thenReturn(staffId);
        when(staff.getName()).thenReturn(STAFF_NAME);
        setVisible(true);
        ConfigManager config = mock(ConfigManager.class);
        when(config.getBooleanElse("spoof.vanished-team-entries", false)).thenReturn(true);
        when(config.getBooleanElse("spoof.spectator-gamemode", false)).thenReturn(true);
        PacketStaffListSpoof.reload(config);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        if (channel != null) channel.finishAndReleaseAll();
        UserDisconnectEvent event = mock(UserDisconnectEvent.class);
        when(event.getUser()).thenReturn(user);
        listener.onUserDisconnect(event);
        PacketInfoSpoof.setNoSpoof(user, false);
        PacketStaffListSpoof.reload(mock(ConfigManager.class));
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "loader", originalLoader);
    }

    @ParameterizedTest
    @CsvSource({"V_1_21_11, V_1_21_4", "V_1_21_4, V_1_21_11", "V_1_18_2, V_1_21_11"})
    void translatedVanishPacketsStayOnTheClientSideOfVia(ClientVersion server, ClientVersion client) throws Exception {
        serverVersion = server;
        version = client;
        setupTranslatedChannel(true);

        team("staff", TeamMode.CREATE, STAFF_NAME);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));
        assertEquals(PREFIX, prefixOf(STAFF_NAME));
        assertTrue(profiles.containsKey(staffId));

        setVisible(false);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, false));
        assertNull(prefixOf(STAFF_NAME));
        assertFalse(profiles.containsKey(staffId));

        setVisible(true);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, true));
        assertEquals(PREFIX, prefixOf(STAFF_NAME));
        assertTrue(profiles.get(staffId).isListed());
        assertEquals(0, viaWrites, "The client-format packets must bypass ViaVersion");
        verify(user, never()).sendPacketSilently(any(PacketWrapper.class));
    }

    @Test
    void translatedPacketsDoNotUseTheNativeSenderIfViaContextIsMissing() throws Exception {
        serverVersion = ClientVersion.V_1_21_11;
        version = ClientVersion.V_1_21_4;
        setupTranslatedChannel(false);
        team("staff", TeamMode.CREATE, STAFF_NAME);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));

        setVisible(false);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, false));

        assertNull(channel.readOutbound());
        assertEquals(0, viaWrites);
        verify(user, never()).sendPacketSilently(any(PacketWrapper.class));
    }

    private void setupTranslatedChannel(boolean hasClientSideContext) {
        channel = new EmbeddedChannel();
        if (hasClientSideContext) {
            channel.pipeline().addLast(ConnectionUtils.PRE_VIA_ENCODER_NAME, new ChannelOutboundHandlerAdapter());
        }
        channel.pipeline().addLast("via-encoder", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                viaWrites++;
                ctx.write(msg, promise);
            }
        });
        channel.pipeline().addLast(ConnectionUtils.POST_VIA_ENCODER_NAME, new ChannelOutboundHandlerAdapter());
        when(user.getChannel()).thenReturn(channel);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_19_3", "V_1_21_11", "V_26_3"})
    void listingOnlyTransitionRemovesAndRestoresTheFullProfile(ClientVersion wire) throws Exception {
        version = wire;
        team("staff", TeamMode.CREATE, STAFF_NAME, OTHER_NAME);
        UserProfile profile = new UserProfile(staffId, STAFF_NAME, List.of(new TextureProperty("textures", "encoded", "signature")));
        UUID chatId = UUID.randomUUID();
        PublicProfileKey key = new PublicProfileKey(Instant.now().plusSeconds(60),
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic(), new byte[]{1, 2, 3});
        PlayerInfo original = new PlayerInfo(profile, true, 15, GameMode.SPECTATOR, Component.text("Initial"),
                new RemoteChatSession(chatId, key), 42, true);
        EnumSet<Action> actions = EnumSet.allOf(Action.class);
        if (wire.isOlderThan(ClientVersion.V_1_21_2)) actions.remove(Action.UPDATE_LIST_ORDER);
        if (wire.isOlderThan(ClientVersion.V_1_21_4)) actions.remove(Action.UPDATE_HAT);
        update(actions, original, player(otherId, OTHER_NAME));
        assertEquals(PREFIX, prefixOf(STAFF_NAME));

        setVisible(false);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, false), listed(otherId, false));
        assertFalse(profiles.containsKey(staffId));
        assertNull(prefixOf(STAFF_NAME));
        assertTrue(profiles.containsKey(otherId));
        assertFalse(profiles.get(otherId).isListed());
        assertEquals(PREFIX, prefixOf(OTHER_NAME));
        assertEquals(1, profileRemovals);

        PlayerInfo changed = new PlayerInfo(staffId);
        changed.setDisplayName(Component.text("Updated"));
        changed.setLatency(37);
        update(EnumSet.of(Action.UPDATE_DISPLAY_NAME, Action.UPDATE_LATENCY), changed);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, true));
        assertFalse(profiles.containsKey(staffId));
        assertEquals(1, profileRemovals);

        setVisible(true);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, true));
        PlayerInfo restored = profiles.get(staffId);
        assertNotNull(restored);
        assertTrue(restored.isListed());
        assertEquals(STAFF_NAME, restored.getGameProfile().getName());
        assertEquals("encoded", restored.getGameProfile().getTextureProperties().get(0).getValue());
        assertEquals(chatId, restored.getChatSession().getSessionId());
        assertEquals(GameMode.SURVIVAL, restored.getGameMode());
        assertEquals(37, restored.getLatency());
        assertEquals(Component.text("Updated"), restored.getDisplayName());
        if (actions.contains(Action.UPDATE_LIST_ORDER)) assertEquals(42, restored.getListOrder());
        if (actions.contains(Action.UPDATE_HAT)) assertTrue(restored.isShowHat());
        assertEquals(PREFIX, prefixOf(STAFF_NAME));
    }

    @Test
    void nativeRemovalAndRejoinUseTheLatestTeam() throws Exception {
        team("staff", TeamMode.CREATE, STAFF_NAME);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));

        setVisible(false);
        send(PacketType.Play.Server.PLAYER_INFO_REMOVE, new WrapperPlayServerPlayerInfoRemove(List.of(staffId)));
        assertFalse(profiles.containsKey(staffId));
        assertNull(prefixOf(STAFF_NAME));
        team("staff", TeamMode.REMOVE_ENTITIES, STAFF_NAME);
        team("new_staff", TeamMode.CREATE, STAFF_NAME);
        assertNull(prefixOf(STAFF_NAME));

        setVisible(true);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));
        assertTrue(profiles.get(staffId).isListed());
        assertEquals("new_staff", memberships.get(STAFF_NAME));
    }

    @Test
    void initiallyHiddenProfileIsNeverAnnouncedAndDoesNotRestoreDeletedTeam() throws Exception {
        setVisible(false);
        team("staff", TeamMode.CREATE, STAFF_NAME, OTHER_NAME);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(otherId, OTHER_NAME), player(staffId, STAFF_NAME));
        assertFalse(profiles.containsKey(staffId));
        assertNull(prefixOf(STAFF_NAME));
        assertEquals(0, profileRemovals);
        team("staff", TeamMode.REMOVE_ENTITIES, STAFF_NAME);

        setVisible(true);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, true));
        assertTrue(profiles.get(staffId).isListed());
        assertNull(prefixOf(STAFF_NAME));
    }

    @Test
    void movingToAnotherTeamWhileHiddenClearsThePreviousPrefix() throws Exception {
        team("old_staff", TeamMode.CREATE, STAFF_NAME);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));

        setVisible(false);
        team("new_staff", TeamMode.CREATE, STAFF_NAME);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, false));
        assertFalse(profiles.containsKey(staffId));
        assertNull(prefixOf(STAFF_NAME));

        setVisible(true);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, true));
        assertEquals("new_staff", memberships.get(STAFF_NAME));
    }

    @Test
    void hidesKnownTeamMembershipEvenWithoutACachedProfile() throws Exception {
        team("staff", TeamMode.CREATE, STAFF_NAME);
        assertEquals(PREFIX, prefixOf(STAFF_NAME));

        setVisible(false);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, false));
        assertNull(prefixOf(STAFF_NAME));

        setVisible(true);
        update(EnumSet.of(Action.UPDATE_LISTED), listed(staffId, true));
        assertFalse(profiles.containsKey(staffId));
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));
        assertTrue(profiles.get(staffId).isListed());
        assertEquals(PREFIX, prefixOf(STAFF_NAME));
    }

    @Test
    void legacyRemoveAndAddRestoreMembership() throws Exception {
        version = ClientVersion.V_1_16_4;
        team("staff", TeamMode.CREATE, STAFF_NAME);
        UserProfile profile = new UserProfile(staffId, STAFF_NAME);
        var entry = new WrapperPlayServerPlayerInfo.PlayerData(null, profile, GameMode.SURVIVAL, null, 10);
        legacy(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER, entry);
        assertTrue(profiles.containsKey(staffId));
        setVisible(false);
        legacy(WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER, entry);
        legacy(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER, entry);
        assertFalse(profiles.containsKey(staffId));
        assertNull(prefixOf(STAFF_NAME));
        setVisible(true);
        legacy(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER, entry);
        assertTrue(profiles.containsKey(staffId));
        assertEquals(PREFIX, prefixOf(STAFF_NAME));
    }

    @Test
    void viewerWithSpoofExemptionKeepsTheServerProvidedProfile() throws Exception {
        PacketInfoSpoof.setNoSpoof(user, true);
        setVisible(false);
        team("staff", TeamMode.CREATE, STAFF_NAME);
        update(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED), player(staffId, STAFF_NAME));
        assertTrue(profiles.containsKey(staffId));
        assertEquals(PREFIX, prefixOf(STAFF_NAME));
    }

    private void setVisible(boolean value) {
        when(viewer.canSee(staff)).thenReturn(value);
    }

    private Component prefixOf(String name) {
        return prefixes.get(memberships.get(name));
    }

    private static PlayerInfo player(UUID id, String name) {
        return new PlayerInfo(new UserProfile(id, name), true, 12, GameMode.SURVIVAL, null, null);
    }

    private static PlayerInfo listed(UUID id, boolean listed) {
        PlayerInfo entry = new PlayerInfo(id);
        entry.setListed(listed);
        return entry;
    }

    private void team(String name, TeamMode mode, String... members) throws Exception {
        var info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.text(name), PREFIX, Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS,
                NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE);
        send(PacketType.Play.Server.TEAMS, new WrapperPlayServerTeams(name, mode,
                mode == TeamMode.CREATE ? Optional.of(info) : Optional.empty(), List.of(members)));
    }

    private void legacy(WrapperPlayServerPlayerInfo.Action action, WrapperPlayServerPlayerInfo.PlayerData entry) throws Exception {
        send(PacketType.Play.Server.PLAYER_INFO, new WrapperPlayServerPlayerInfo(action, List.of(entry)));
    }

    private void update(EnumSet<Action> actions, PlayerInfo... entries) throws Exception {
        send(PacketType.Play.Server.PLAYER_INFO_UPDATE, new WrapperPlayServerPlayerInfoUpdate(actions, entries));
    }

    private void send(PacketType.Play.Server type, PacketWrapper<?> packet) throws Exception {
        packet.setServerVersion(version.toServerVersion());
        packet.setClientVersion(version);
        ByteBuf buffer = encode(packet, type.getId(version));
        try {
            PacketEventsImplHelper.handleClientBoundPacket(null, user, null, buffer, false);
            if (channel != null) {
                ByteBuf injected;
                while ((injected = channel.readOutbound()) != null) {
                    try {
                        observe(injected);
                    } finally {
                        injected.release();
                    }
                }
            }
            if (buffer.isReadable()) observe(buffer);
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf encode(PacketWrapper<?> packet, int id) {
        ByteBuf buffer = Unpooled.buffer();
        packet.buffer = buffer;
        packet.writeVarInt(id);
        packet.write();
        packet.buffer = null;
        return buffer;
    }

    private void observe(ByteBuf buffer) throws PacketProcessException {
        SenderEvent event = new SenderEvent(user, buffer);
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            var update = new WrapperPlayServerPlayerInfoUpdate(event);
            for (PlayerInfo entry : update.getEntries()) {
                UUID id = entry.getProfileId();
                if (update.getActions().contains(Action.ADD_PLAYER)) profiles.putIfAbsent(id, entry);
                PlayerInfo known = profiles.get(id);
                assertNotNull(known, "Received an update for a removed player");
                if (update.getActions().contains(Action.UPDATE_LISTED)) known.setListed(entry.isListed());
            }
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            for (UUID id : new WrapperPlayServerPlayerInfoRemove(event).getProfileIds()) {
                profiles.remove(id);
                profileRemovals++;
            }
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO) {
            var info = new WrapperPlayServerPlayerInfo(event);
            for (var entry : info.getPlayerDataList()) {
                UserProfile profile = entry.getUserProfile();
                if (info.getAction() == WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER) profiles.remove(profile.getUUID());
                else if (info.getAction() == WrapperPlayServerPlayerInfo.Action.ADD_PLAYER) profiles.put(profile.getUUID(), new PlayerInfo(profile));
            }
        } else if (event.getPacketType() == PacketType.Play.Server.TEAMS) {
            var team = new WrapperPlayServerTeams(event);
            String name = team.getTeamName();
            if (team.getTeamMode() == TeamMode.CREATE) prefixes.put(name, team.getTeamInfo().orElseThrow().getPrefix());
            assertTrue(prefixes.containsKey(name));
            for (String member : team.getPlayers()) {
                if (team.getTeamMode() == TeamMode.REMOVE_ENTITIES) assertTrue(memberships.remove(member, name));
                else memberships.put(member, name);
            }
        } else {
            fail("Unexpected packet " + event.getPacketType());
        }
        assertFalse(buffer.isReadable(), "Trailing bytes");
    }

    private static final class SenderEvent extends PacketSendEvent {
        SenderEvent(User user, ByteBuf buffer) throws PacketProcessException {
            super(null, user, null, buffer, false);
        }
    }
}
