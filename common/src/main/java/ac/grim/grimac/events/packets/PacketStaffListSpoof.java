package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayerFactory;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.connection.ConnectionUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class PacketStaffListSpoof extends PacketListenerAbstract {

    // Same shape cheat clients use to tell a player entry from a UUID-keyed entity entry in a team.
    private static final Pattern PLAYER_NAME = Pattern.compile("^\\w{3,16}$");

    private static final String[] NAME_HEADS = {
            "Kae", "Vex", "Sol", "Dusk", "Riff", "Nyx", "Tor", "Zeph", "Fros", "Kry",
            "Mir", "Vel", "Dra", "Lum", "Ryn", "Ash", "Cor", "Fen", "Hal", "Sev"
    };

    private static final String[] NAME_TAILS = {
            "lin", "ver", "dar", "mir", "ten", "rix", "son", "wyn", "gar", "lith",
            "vex", "ran", "dor", "nix", "fell", "born", "wick", "mont", "ley", "ric"
    };

    // Vanilla picks the tab bar icon from these cutoffs, and reports the band's value so nothing beyond
    // the icon a vanilla client already draws is left to read.
    private static final int[] PING_BANDS = {150, 300, 600, 1000};
    private static final int[] PING_BAND_VALUES = {75, 225, 450, 800, 1000};

    private static volatile boolean spoofPing;
    private static volatile int pingValue;
    private static volatile int pingSpread;
    private static volatile boolean pingRound;
    private static final long PING_SALT = ThreadLocalRandom.current().nextLong();

    private static volatile boolean spoofSpectators;
    private static volatile boolean spoofGameMode;
    private static volatile GameMode gameModeValue = GameMode.SURVIVAL;
    private static volatile boolean hideVanishedTeams;
    private static volatile boolean fakeStaff;
    private static volatile int fakeStaffMin;
    private static volatile int fakeStaffMax;
    private static volatile int fakeStaffRefresh;
    private static volatile boolean fakeStaffRandomNames;
    private static volatile List<String> fakeNames = List.of();
    private static volatile List<Component> fakePrefixes = List.of();
    private static volatile List<Fake> fakes = List.of();
    private static boolean started;
    private static @Nullable TaskHandle rotation;

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    public PacketStaffListSpoof() {
        super(PacketListenerPriority.HIGHEST);
    }

    @Override
    public boolean isPreVia() {
        return true;
    }

    public static void reload(ConfigManager config) {
        spoofPing = config.getBooleanElse("spoof.entity-ping", false);
        pingValue = Math.max(0, config.getIntElse("spoof.ping-value", 100));
        pingSpread = Math.max(0, Math.min(pingValue, config.getIntElse("spoof.ping-spread", 60)));
        pingRound = config.getBooleanElse("spoof.ping-round", false);

        spoofSpectators = config.getBooleanElse("spoof.spectator-gamemode", false);
        spoofGameMode = config.getBooleanElse("spoof.entity-gamemode", false);
        gameModeValue = parseGameMode(config.getStringElse("spoof.gamemode-value", "survival"));
        hideVanishedTeams = config.getBooleanElse("spoof.vanished-team-entries", false);
        if (!hideVanishedTeams) STATES.clear();

        fakeStaff = config.getBooleanElse("spoof.fake-staff", false);
        fakeStaffMax = Math.max(0, config.getIntElse("spoof.fake-staff-count.max", 6));
        fakeStaffMin = Math.max(0, Math.min(fakeStaffMax, config.getIntElse("spoof.fake-staff-count.min", 3)));
        fakeStaffRefresh = Math.max(1, config.getIntElse("spoof.fake-staff-refresh", 300));
        fakeStaffRandomNames = config.getBooleanElse("spoof.fake-staff-random-names", true);
        boolean colors = config.getBooleanElse("spoof.parse-colors", false);
        fakeNames = List.copyOf(config.getStringListElse("spoof-staff-names", List.of()));
        List<Component> prefixes = new ArrayList<>();
        for (String line : config.getStringListElse("spoof-staff-prefixes", List.of())) {
            prefixes.add(colors ? MessageUtil.miniMessage(line) : Component.text(line));
        }
        fakePrefixes = List.copyOf(prefixes);
        reschedule();
    }

    public static void start() {
        started = true;
        reschedule();
    }

    private static synchronized void reschedule() {
        if (rotation != null) {
            rotation.cancel();
            rotation = null;
        }
        if (!started) return;
        // A member without a prefix is not staff to any of these clients, so an empty list means off.
        if (!fakeStaff || fakeStaffMax == 0 || fakePrefixes.isEmpty()
                || (!fakeStaffRandomNames && fakeNames.isEmpty())) {
            rotate(List.of());
            return;
        }
        rotate(pick());
        rotation = GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(
                GrimAPI.INSTANCE.getGrimPlugin(), () -> rotate(pick()),
                fakeStaffRefresh, fakeStaffRefresh, TimeUnit.SECONDS);
    }

    private static void rotate(List<Fake> next) {
        List<Fake> previous = fakes;
        if (previous.isEmpty() && next.isEmpty()) return;
        fakes = next;

        // Built once and shared: at a few thousand fakes this costs more than the sending does. The
        // wrappers stay per receiver, a written one carries its buffer.
        List<String> goneTeams = new ArrayList<>(new LinkedHashSet<>(teamsOf(previous)));
        List<UUID> goneIds = new ArrayList<>(previous.size());
        for (Fake fake : previous) goneIds.add(fake.id());

        List<TeamBatch> batches = batchTeams(next);
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = infoEntries(next);

        for (User user : PacketEvents.getAPI().getProtocolManager().getUsers()) {
            if (user.getConnectionState() != ConnectionState.PLAY) continue;
            // The rotation pushes directly, so it needs the same exemption the send listener applies
            if (PacketInfoSpoof.spoofExempt(user)) continue;
            forget(user, goneTeams, goneIds);
            announce(user, batches, entries);
        }
    }

    private static List<String> teamsOf(List<Fake> fakes) {
        List<String> teams = new ArrayList<>(fakes.size());
        for (Fake fake : fakes) teams.add(fake.team());
        return teams;
    }

    private static List<TeamBatch> batchTeams(List<Fake> fakes) {
        Map<String, List<Fake>> byTeam = new LinkedHashMap<>();
        for (Fake fake : fakes) byTeam.computeIfAbsent(fake.team(), k -> new ArrayList<>()).add(fake);

        List<TeamBatch> batches = new ArrayList<>(byTeam.size());
        for (Map.Entry<String, List<Fake>> entry : byTeam.entrySet()) {
            List<String> members = new ArrayList<>(entry.getValue().size());
            for (Fake fake : entry.getValue()) members.add(fake.member());
            batches.add(new TeamBatch(entry.getKey(), entry.getValue().get(0).prefix(), members));
        }
        return batches;
    }

    private static List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> infoEntries(List<Fake> fakes) {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(fakes.size());
        for (Fake fake : fakes) {
            entries.add(new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    new UserProfile(fake.id(), fake.member()), false, fake.latency(),
                    GameMode.SURVIVAL, fake.display(), null));
        }
        return entries;
    }

    // Two channels: some cheats read scoreboard teams, others walk the tab list.
    private static void announce(User user, List<TeamBatch> batches,
                                 List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries) {
        if (entries.isEmpty()) return;
        for (TeamBatch batch : batches) {
            user.sendPacketSilently(new WrapperPlayServerTeams(batch.name(), WrapperPlayServerTeams.TeamMode.CREATE,
                    Optional.of(new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                            Component.text(batch.name()), batch.prefix(), Component.empty(),
                            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                            WrapperPlayServerTeams.CollisionRule.NEVER,
                            NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE)),
                    batch.members()));
        }

        // listed=false keeps it out of PlayerTabOverlay while getPlayerList(), what cheats walk, still
        // returns it. No such flag before 1.19.3.
        if (user.getClientVersion().isOlderThan(ClientVersion.V_1_19_3)) return;
        user.sendPacketSilently(new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME),
                entries));
    }

    private static void forget(User user, List<String> teams, List<UUID> ids) {
        if (ids.isEmpty()) return;
        for (String team : teams) {
            user.sendPacketSilently(new WrapperPlayServerTeams(team,
                    WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty(), List.<String>of()));
        }
        if (user.getClientVersion().isOlderThan(ClientVersion.V_1_19_3)) return;
        user.sendPacketSilently(new WrapperPlayServerPlayerInfoRemove(ids));
    }

    private static List<Fake> pick() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int target = fakeStaffMax <= fakeStaffMin ? fakeStaffMax : random.nextInt(fakeStaffMin, fakeStaffMax + 1);

        List<String> pool;
        if (fakeStaffRandomNames) {
            Set<String> generated = new LinkedHashSet<>();
            for (int attempt = 0; generated.size() < target && attempt < target * 8; attempt++) {
                generated.add(randomName(random));
            }
            pool = new ArrayList<>(generated);
        } else {
            pool = new ArrayList<>(fakeNames);
            Collections.shuffle(pool, random);
        }

        // One team per prefix, not per fake: a team packet carries a member list but cannot be merged
        // with another team, so this is what stops a packet per entry.
        List<Component> prefixes = fakePrefixes;
        String[] teams = new String[prefixes.size()];
        for (int i = 0; i < teams.length; i++) {
            // Team names are capped at 16 characters on old protocols.
            teams[i] = "gg" + Long.toHexString(random.nextLong() & 0xFFFFFFFFFFFFL);
        }

        List<Fake> picked = new ArrayList<>();
        for (int i = 0; i < Math.min(target, pool.size()); i++) {
            String name = pool.get(i);
            int rank = random.nextInt(prefixes.size());
            Component prefix = prefixes.get(rank);
            // Cheats recover the rank by cutting the nickname out of the display name, so it has to be in it.
            Component display = Component.text().append(prefix).append(Component.text(name)).build();
            picked.add(new Fake(teams[rank], name, prefix, profileId(name), display, random.nextInt(20, 220)));
        }
        return List.copyOf(picked);
    }

    // Stays inside ^\w{3,16}$, the only shape cheat clients read as a player.
    private static String randomName(ThreadLocalRandom random) {
        StringBuilder name = new StringBuilder()
                .append(NAME_HEADS[random.nextInt(NAME_HEADS.length)])
                .append(NAME_TAILS[random.nextInt(NAME_TAILS.length)]);
        if (random.nextInt(4) == 0) name.append(random.nextInt(10, 100));
        return name.toString();
    }

    private record Fake(String team, String member, Component prefix, UUID id, Component display, int latency) {
    }

    private record TeamBatch(String name, Component prefix, List<String> members) {
    }

    // A random version-4 uuid stands out where offline mode hashes "OfflinePlayer:<name>" into version 3.
    private static GameMode parseGameMode(String name) {
        for (GameMode mode : GameMode.values()) {
            if (mode.name().equalsIgnoreCase(name)) return mode;
        }
        return GameMode.SURVIVAL;
    }

    private static GameMode reportedMode(GameMode real) {
        if (spoofGameMode) return gameModeValue;
        return spoofSpectators && real == GameMode.SPECTATOR ? GameMode.SURVIVAL : real;
    }

    private static boolean pingSpoofingOn() {
        return spoofPing || pingRound;
    }

    private static int fakePing(@Nullable UUID target, int real) {
        if (spoofPing) {
            if (pingSpread <= 0 || target == null) return pingValue;
            long seed = target.getMostSignificantBits() * 31 + target.getLeastSignificantBits() + PING_SALT;
            seed ^= seed >>> 33;
            seed *= 0xff51afd7ed558ccdL;
            seed ^= seed >>> 33;
            return Math.max(0, pingValue - Math.floorMod(seed, pingSpread + 1));
        }
        if (!pingRound || real < 0) return real;

        int band = 0;
        while (band < PING_BANDS.length && real >= PING_BANDS[band]) band++;
        return PING_BAND_VALUES[band];
    }

    private static UUID profileId(String name) {
        for (PlatformPlayer online : GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            if (uuid == null) continue;
            return uuid.version() == 3
                    ? UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                    : UUID.randomUUID();
        }
        return UUID.randomUUID();
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid != null) STATES.remove(uuid);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!spoofSpectators && !spoofGameMode && !hideVanishedTeams && !fakeStaff && !pingSpoofingOn()) return;
        if (event.isCancelled() || PacketInfoSpoof.spoofExempt(event.getUser())) return;

        User user = event.getUser();
        UUID self = user.getUUID();
        if (self == null) return;

        if (event.getPacketType() == PacketType.Play.Server.TEAMS) {
            if (hideVanishedTeams) handleTeams(event, self);
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            handleInfoUpdate(event, self);
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            if (hideVanishedTeams) handleInfoRemove(event, self);
        } else if (event.getPacketType() == PacketType.Play.Server.PLAYER_INFO) {
            handleLegacyInfo(event, self);
        } else if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            STATES.remove(self);
            // The client builds a fresh scoreboard per connection, so every login needs the set again.
            List<Fake> current = fakes;
            if (!current.isEmpty()) {
                // After send, not a post task: those run inside the encoder while this join is still on
                // its way out, so the roster would arrive before the packet that wipes the scoreboard.
                event.getTasksAfterSend().add(() -> announce(user, batchTeams(current), infoEntries(current)));
            }
        }
    }

    private void handleTeams(PacketSendEvent event, UUID self) {
        State state = STATES.computeIfAbsent(self, k -> new State());
        WrapperPlayServerTeams wrapper = new WrapperPlayServerTeams(event);
        String team = wrapper.getTeamName();

        switch (wrapper.getTeamMode()) {
            case CREATE, ADD_ENTITIES -> {
                Collection<String> members = wrapper.getPlayers();
                List<String> keep = null;
                for (String member : members) {
                    boolean hidden = isHidden(self, member);
                    if (hidden) hideMembership(state, event, member);
                    state.memberTeam.put(member, team);
                    if (!hidden) {
                        state.hiddenTeams.remove(member);
                        continue;
                    }
                    if (keep == null) keep = new ArrayList<>(members);
                    keep.remove(member);
                    state.hiddenTeams.put(member, team);
                }
                if (keep == null) return;
                // An empty CREATE still has to reach the client, or later members have nothing to join.
                if (keep.isEmpty() && wrapper.getTeamMode() == WrapperPlayServerTeams.TeamMode.ADD_ENTITIES) {
                    event.setCancelled(true);
                } else {
                    wrapper.setPlayers(keep);
                    event.markForReEncode(true);
                }
            }
            case REMOVE_ENTITIES -> {
                Collection<String> members = wrapper.getPlayers();
                List<String> keep = null;
                for (String member : members) {
                    state.memberTeam.remove(member, team);
                    if (!state.hiddenTeams.remove(member, team)) continue;
                    if (keep == null) keep = new ArrayList<>(members);
                    keep.remove(member);
                }
                if (keep != null) {
                    if (keep.isEmpty()) event.setCancelled(true);
                    else {
                        wrapper.setPlayers(keep);
                        event.markForReEncode(true);
                    }
                }
            }
            case REMOVE -> {
                state.hiddenTeams.values().removeIf(team::equals);
                state.memberTeam.values().removeIf(team::equals);
            }
            default -> {
            }
        }
    }

    private void handleInfoUpdate(PacketSendEvent event, UUID self) {
        WrapperPlayServerPlayerInfoUpdate wrapper = new WrapperPlayServerPlayerInfoUpdate(event);
        EnumSet<Action> actions = wrapper.getActions();
        boolean gameMode = (spoofSpectators || spoofGameMode)
                && actions.contains(Action.UPDATE_GAME_MODE);
        boolean ping = pingSpoofingOn()
                && actions.contains(Action.UPDATE_LATENCY);
        if (!hideVanishedTeams && !gameMode && !ping) return;

        State state = hideVanishedTeams ? STATES.computeIfAbsent(self, k -> new State()) : null;
        PlatformPlayerFactory factory = state == null ? null : GrimAPI.INSTANCE.getPlatformPlayerFactory();
        PlatformPlayer receiver = factory == null ? null : factory.getFromUUID(self);
        List<PlayerInfo> entries = wrapper.getEntries();
        List<PlayerInfo> rewritten = null;

        for (int i = 0; i < entries.size(); i++) {
            PlayerInfo entry = entries.get(i);
            UUID uuid = entry.getProfileId();
            PlayerInfo outgoing = entry;
            boolean forward = true;
            if (!self.equals(uuid)) {
                GameMode mode = gameMode ? reportedMode(entry.getGameMode()) : entry.getGameMode();
                int latency = ping ? fakePing(uuid, entry.getLatency()) : entry.getLatency();
                if (mode != entry.getGameMode() || latency != entry.getLatency()) {
                    outgoing = new PlayerInfo(entry);
                    outgoing.setGameMode(mode);
                    outgoing.setLatency(latency);
                }
                if (receiver != null) {
                    forward = trackProfile(state, event, actions, outgoing, receiver, factory.getFromUUID(uuid));
                }
            }
            if (rewritten == null && (!forward || outgoing != entry)) {
                rewritten = new ArrayList<>(entries.subList(0, i));
            }
            if (rewritten != null && forward) rewritten.add(outgoing);
        }

        if (rewritten != null) {
            if (rewritten.isEmpty()) event.setCancelled(true);
            else {
                wrapper.setEntries(rewritten);
                event.markForReEncode(true);
            }
        }
    }

    private static boolean trackProfile(State state, PacketSendEvent event, EnumSet<Action> actions, PlayerInfo entry,
                                        PlatformPlayer receiver, @Nullable PlatformPlayer target) {
        UUID uuid = entry.getProfileId();
        TrackedProfile tracked = state.profiles.get(uuid);
        if (target == null && tracked == null) return true;

        boolean added = actions.contains(Action.ADD_PLAYER);
        boolean hidden = target != null && !receiver.canSee(target);
        if (hidden && (tracked == null ? !added : !tracked.hidden)) {
            send(event, PacketType.Play.Server.PLAYER_INFO_REMOVE, new WrapperPlayServerPlayerInfoRemove(List.of(uuid)));
        }
        if (tracked == null) {
            if (!added && !hidden) return true;
            tracked = new TrackedProfile(uuid);
            state.profiles.put(uuid, tracked);
        }
        tracked.merge(actions, entry);
        String name = tracked.info.getGameProfile().getName();
        if ((name == null || name.isEmpty()) && target != null) name = target.getName();

        if (hidden) {
            tracked.hidden = true;
            hideMembership(state, event, name);
            return false;
        }

        if (tracked.hidden) {
            if (target == null || !tracked.actions.contains(Action.ADD_PLAYER)) return false;
            // A listing update cannot bring back a removed profile on its own.
            send(event, PacketType.Play.Server.PLAYER_INFO_UPDATE,
                    new WrapperPlayServerPlayerInfoUpdate(tracked.actions.clone(), new PlayerInfo(tracked.info)));
            tracked.hidden = false;
            restoreMembership(state, event, name);
            return false;
        }

        if (added) restoreMembership(state, event, name);
        return true;
    }

    private void handleInfoRemove(PacketSendEvent event, UUID self) {
        State state = STATES.get(self);
        if (state == null) return;

        for (UUID uuid : new WrapperPlayServerPlayerInfoRemove(event).getProfileIds()) {
            state.profiles.remove(uuid);
            becameHidden(state, event, self, uuid);
        }
    }

    private void handleLegacyInfo(PacketSendEvent event, UUID self) {
        WrapperPlayServerPlayerInfo wrapper = new WrapperPlayServerPlayerInfo(event);
        WrapperPlayServerPlayerInfo.Action action = wrapper.getAction();
        boolean added = action == WrapperPlayServerPlayerInfo.Action.ADD_PLAYER;
        boolean removed = action == WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER;
        boolean gameMode = (spoofSpectators || spoofGameMode)
                && (added || action == WrapperPlayServerPlayerInfo.Action.UPDATE_GAME_MODE);
        boolean ping = pingSpoofingOn()
                && (added || action == WrapperPlayServerPlayerInfo.Action.UPDATE_LATENCY);
        if (!hideVanishedTeams && !gameMode && !ping) return;

        State state = hideVanishedTeams ? STATES.get(self) : null;
        PlatformPlayerFactory factory = hideVanishedTeams ? GrimAPI.INSTANCE.getPlatformPlayerFactory() : null;
        PlatformPlayer receiver = factory == null ? null : factory.getFromUUID(self);
        List<WrapperPlayServerPlayerInfo.PlayerData> entries = wrapper.getPlayerDataList();
        List<WrapperPlayServerPlayerInfo.PlayerData> rewritten = null;

        for (int i = 0; i < entries.size(); i++) {
            WrapperPlayServerPlayerInfo.PlayerData entry = entries.get(i);
            UserProfile profile = entry.getUserProfile();
            UUID uuid = profile == null ? null : profile.getUUID();
            WrapperPlayServerPlayerInfo.PlayerData outgoing = entry;
            boolean forward = true;
            if (uuid != null && !self.equals(uuid)) {
                PlatformPlayer target = receiver == null ? null : factory.getFromUUID(uuid);
                boolean hidden = target != null && !receiver.canSee(target);
                forward = removed || !hidden;
                if (state != null) {
                    if (hidden) hideMembership(state, event, target.getName());
                    else if (added) restoreMembership(state, event, profile.getName());
                }
                GameMode mode = gameMode ? reportedMode(entry.getGameMode()) : entry.getGameMode();
                int latency = ping ? fakePing(uuid, entry.getPing()) : entry.getPing();
                if (mode != entry.getGameMode() || latency != entry.getPing()) {
                    outgoing = new WrapperPlayServerPlayerInfo.PlayerData(
                            entry.getDisplayName(), profile, mode, entry.getSignatureData(), latency);
                }
            }
            if (rewritten == null && (!forward || outgoing != entry)) {
                rewritten = new ArrayList<>(entries.subList(0, i));
            }
            if (rewritten != null && forward) rewritten.add(outgoing);
        }

        if (rewritten != null) {
            if (rewritten.isEmpty()) event.setCancelled(true);
            else {
                wrapper.setPlayerDataList(rewritten);
                event.markForReEncode(true);
            }
        }
    }

    private static void restoreMembership(State state, PacketSendEvent event, @Nullable String name) {
        if (name == null) return;
        String team = state.hiddenTeams.remove(name);
        if (team != null) sendMembership(event, team, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, name);
    }

    private void becameHidden(State state, PacketSendEvent event, UUID self, @Nullable UUID uuid) {
        if (uuid == null) return;
        PlatformPlayer target = GrimAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid);
        if (target == null) return;

        String name = target.getName();
        if (name != null && isHidden(self, name)) hideMembership(state, event, name);
    }

    private static void hideMembership(State state, PacketSendEvent event, @Nullable String name) {
        if (name == null) return;
        String team = state.memberTeam.get(name);
        if (team != null && !team.equals(state.hiddenTeams.put(name, team))) {
            sendMembership(event, team, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, name);
        }
    }

    private static void sendMembership(PacketSendEvent event, String team, WrapperPlayServerTeams.TeamMode mode, String name) {
        send(event, PacketType.Play.Server.TEAMS,
                new WrapperPlayServerTeams(team, mode, Optional.empty(), List.of(name)));
    }

    private static void send(PacketSendEvent event, PacketType.Play.Server type, PacketWrapper<?> packet) {
        ServerVersion wireVersion = event.getServerVersion();
        ClientVersion version = wireVersion.toClientVersion();
        packet.setServerVersion(wireVersion);
        packet.setClientVersion(version);
        packet.setNativePacketId(type.getId(version));
        event.getPostTasks().add(() -> sendSilently(event.getUser(), packet));
    }

    private static void sendSilently(User user, PacketWrapper<?> packet) {
        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        if (packet.getServerVersion().getProtocolVersion() == serverVersion.getProtocolVersion()) {
            user.sendPacketSilently(packet);
            return;
        }

        // The pre-Via handler is after ViaVersion on outbound writes.
        Object channel = user.getChannel();
        String encoder = ConnectionUtils.PRE_VIA_ENCODER_NAME;
        if (!ChannelHelper.isOpen(channel) || ChannelHelper.getPipelineContext(channel, encoder) == null) return;

        try {
            packet.prepareForSend(channel, true, false);
            ChannelHelper.writeAndFlushInContext(channel, encoder, packet.buffer);
            packet.buffer = null;
        } finally {
            if (packet.buffer != null) {
                ByteBufHelper.release(packet.buffer);
                packet.buffer = null;
            }
        }
    }

    private static boolean isHidden(UUID self, String member) {
        if (!PLAYER_NAME.matcher(member).matches()) return false;

        PlatformPlayerFactory factory = GrimAPI.INSTANCE.getPlatformPlayerFactory();
        // Lookup by name is a prefix match on some platforms, so the name has to be confirmed exactly.
        PlatformPlayer target = factory.getFromName(member);
        if (target == null || !member.equals(target.getName())) return false;
        if (self.equals(target.getUniqueId())) return false;

        PlatformPlayer receiver = factory.getFromUUID(self);
        return receiver != null && !receiver.canSee(target);
    }

    private static final class State {
        final Map<String, String> memberTeam = new HashMap<>();
        final Map<String, String> hiddenTeams = new HashMap<>();
        final Map<UUID, TrackedProfile> profiles = new HashMap<>();
    }

    private static final class TrackedProfile {
        final PlayerInfo info;
        final EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        boolean hidden;

        TrackedProfile(UUID uuid) {
            info = new PlayerInfo(uuid);
            info.setListed(false);
        }

        void merge(EnumSet<Action> updates, PlayerInfo entry) {
            actions.addAll(updates);
            for (Action action : updates) {
                switch (action) {
                    case ADD_PLAYER -> info.setGameProfile(entry.getGameProfile());
                    case INITIALIZE_CHAT -> info.setChatSession(entry.getChatSession());
                    case UPDATE_GAME_MODE -> info.setGameMode(entry.getGameMode());
                    case UPDATE_LISTED -> info.setListed(entry.isListed());
                    case UPDATE_LATENCY -> info.setLatency(entry.getLatency());
                    case UPDATE_DISPLAY_NAME -> info.setDisplayName(entry.getDisplayName());
                    case UPDATE_LIST_ORDER -> info.setListOrder(entry.getListOrder());
                    case UPDATE_HAT -> info.setShowHat(entry.isShowHat());
                }
            }
        }
    }
}
