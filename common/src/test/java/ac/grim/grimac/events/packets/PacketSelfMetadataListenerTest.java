package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.movement.NoSlow;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.predictionengine.predictions.input.InputTransformer;
import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.anticheat.PlayerDataManager;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.latency.CompensatedCooldown;
import ac.grim.grimac.utils.latency.CompensatedInventory;
import ac.grim.grimac.utils.latency.LatencyUtils;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemUseEffects;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketSelfMetadataListenerTest {
    private GrimPlayer player;
    private NoSlow noSlow;
    private PlayerDataManager originalPlayers;
    private User user;

    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
        when(PacketEvents.getAPI().getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(PacketEvents.getAPI().getNettyManager()).thenReturn(new NettyManagerImpl());
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        player = GrimPlayerFixture.player();
        player.food = 10;
        GrimPlayerFixture.set(player, "platformPlayer", mock(PlatformPlayer.class));
        player.stuckSpeedMultiplier = StuckSpeed.NONE;
        CompensatedInventory inventory = mock(CompensatedInventory.class);
        ItemStack food = ItemStack.builder().type(ItemTypes.APPLE).amount(16).build();
        when(inventory.getItemInHand(any())).thenReturn(food);
        GrimPlayerFixture.set(player, "inventory", inventory);
        when(player.checkManager.getCompensatedCooldown()).thenReturn(mock(CompensatedCooldown.class));
        GrimPlayerFixture.set(player, "latencyUtils", new LatencyUtils(player));
        doAnswer(call -> {
            player.lastTransactionSent.incrementAndGet();
            return null;
        }).when(player).sendTransaction();
        noSlow = mock(NoSlow.class, CALLS_REAL_METHODS);
        GrimPlayerFixture.set(noSlow, "player", player);
        GrimPlayerFixture.set(noSlow, "offsetToFlag", 0.001);
        GrimPlayerFixture.set(noSlow, "bestOffset", 1.0);
        doReturn(true).when(noSlow).flagAndAlertWithSetback();
        user = mock(User.class);
        when(user.getClientVersion()).thenAnswer(call -> player.getClientVersion());
        originalPlayers = GrimAPI.INSTANCE.getPlayerDataManager();
        PlayerDataManager players = mock(PlayerDataManager.class);
        when(players.getPlayer(user)).thenReturn(player);
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "playerDataManager", players);
    }

    @AfterEach
    void restorePlayers() throws ReflectiveOperationException {
        GrimPlayerFixture.set(GrimAPI.INSTANCE, "playerDataManager", originalPlayers);
    }

    private int metadata(boolean active, InteractionHand hand) {
        int index = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_17) ? 8
                : player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) ? 7
                : player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_10) ? 6 : 5;
        byte flags = (byte) ((active ? 1 : 0) | (hand == InteractionHand.OFF_HAND ? 2 : 0));
        var wrapper = new WrapperPlayServerEntityMetadata(player.entityID,
                new ArrayList<>(List.of(new EntityData<>(index, EntityDataTypes.BYTE, flags))));
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getPacketType()).thenReturn(PacketType.Play.Server.ENTITY_METADATA);
        when(event.getUser()).thenReturn(user);
        when(event.getServerVersion()).thenReturn(ServerVersion.V_1_21_11);
        doReturn(wrapper).when(event).getLastUsedWrapper();
        List<Runnable> afterSend = new ArrayList<>();
        when(event.getTasksAfterSend()).thenReturn(afterSend);
        new PacketSelfMetadataListener().onPacketSend(event);
        int before = player.lastTransactionSent.get();
        afterSend.forEach(Runnable::run);
        assertEquals(before + 1, player.lastTransactionSent.get());
        return before;
    }

    private void acknowledge(int transaction) {
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private void use(InteractionHand hand) {
        PacketPlayerDigging.handleUseItem(player, hand);
        assertTrue(player.packetStateData.isSlowedByUsingItem());
    }

    private void fullSpeedMovement() {
        movement(0.098, 4);
    }

    private void movement(double acceleration, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            double predictedInput = InputTransformer.getTransformer(player).transformInputsToVector(player, 0, 0, 1).vector().getZ();
            noSlow.handlePredictionAnalysis(Math.abs(acceleration - predictedInput * 0.1));
            noSlow.onPredictionComplete(new PredictionComplete(0, null, true));
        }
    }

    @Test
    void groundedGrimPongGateCannotRemoveFoodSlowdown() {
        use(InteractionHand.MAIN_HAND);
        int before = metadata(true, InteractionHand.MAIN_HAND);
        acknowledge(before);
        fullSpeedMovement();
        verify(noSlow, atLeastOnce()).flagAndAlertWithSetback();
        assertTrue(player.packetStateData.isSlowedByUsingItem());
    }

    @Test
    void spookytimeReleaseAndReuseStillChecksNoSlowWhileFinalPongIsHeld() {
        use(InteractionHand.MAIN_HAND);
        acknowledge(metadata(true, InteractionHand.MAIN_HAND) + 1);
        player.packetStateData.setSlowedByUsingItem(false);
        player.packetStateData.slowedByUsingItemTransaction = player.lastTransactionReceived.get();
        acknowledge(metadata(false, InteractionHand.MAIN_HAND) + 1);
        use(InteractionHand.MAIN_HAND);
        int before = metadata(true, InteractionHand.MAIN_HAND);
        acknowledge(before);
        fullSpeedMovement();
        verify(noSlow, atLeastOnce()).flagAndAlertWithSetback();
    }

    @ParameterizedTest
    @CsvSource({
            "V_1_9, MAIN_HAND", "V_1_9, OFF_HAND",
            "V_1_10, MAIN_HAND", "V_1_10, OFF_HAND",
            "V_1_14, MAIN_HAND", "V_1_14, OFF_HAND",
            "V_1_17, MAIN_HAND", "V_1_17, OFF_HAND",
            "V_1_21_4, MAIN_HAND", "V_1_21_4, OFF_HAND",
            "V_1_21_11, MAIN_HAND", "V_1_21_11, OFF_HAND"
    })
    void keepsNoSlowActiveAcrossVersionsAndHands(ClientVersion version, InteractionHand hand) {
        when(player.getClientVersion()).thenReturn(version);
        use(hand);
        acknowledge(metadata(true, hand));
        assertTrue(player.packetStateData.isSlowedByUsingItem());
        assertEquals(hand, player.packetStateData.itemInUseHand);
        fullSpeedMovement();
        verify(noSlow, atLeastOnce()).flagAndAlertWithSetback();
    }

    @ParameterizedTest
    @EnumSource(InteractionHand.class)
    void ordinaryEatingDoesNotFlagWhenAcknowledgementsAreDelayed(InteractionHand hand) {
        when(player.getTransactionPing()).thenReturn(1500);
        use(hand);
        int before = metadata(true, hand);
        movement(0.0196, 40);
        acknowledge(before);
        movement(0.0196, 40);
        acknowledge(before + 1);
        movement(0.0196, 40);
        verify(noSlow, never()).flagAndAlertWithSetback();
    }

    @Test
    void serverStartedUseWaitsForFinalAcknowledgement() {
        int before = metadata(true, InteractionHand.OFF_HAND);
        assertFalse(player.packetStateData.isSlowedByUsingItem());
        acknowledge(before);
        assertFalse(player.packetStateData.isSlowedByUsingItem());
        fullSpeedMovement();
        verify(noSlow, never()).flagAndAlertWithSetback();
        acknowledge(before + 1);
        assertTrue(player.packetStateData.isSlowedByUsingItem());
        assertEquals(InteractionHand.OFF_HAND, player.packetStateData.itemInUseHand);
    }

    @Test
    void serverStoppingUseStillAllowsMovementInsideTransactionWindow() {
        use(InteractionHand.MAIN_HAND);
        int before = metadata(false, InteractionHand.MAIN_HAND);
        acknowledge(before);
        assertFalse(player.packetStateData.isSlowedByUsingItem());
        fullSpeedMovement();
        verify(noSlow, never()).flagAndAlertWithSetback();
        acknowledge(before + 1);
        assertFalse(player.packetStateData.isSlowedByUsingItem());
    }

    @Test
    void clientReleaseAfterFirstAcknowledgementOverridesActiveMetadata() {
        use(InteractionHand.MAIN_HAND);
        int before = metadata(true, InteractionHand.MAIN_HAND);
        acknowledge(before);
        player.packetStateData.setSlowedByUsingItem(false);
        player.packetStateData.slowedByUsingItemTransaction = player.lastTransactionReceived.get();
        acknowledge(before + 1);
        assertFalse(player.packetStateData.isSlowedByUsingItem());
        fullSpeedMovement();
        verify(noSlow, never()).flagAndAlertWithSetback();
    }

    @Test
    void clientReuseAfterFirstAcknowledgementOverridesInactiveMetadata() {
        use(InteractionHand.MAIN_HAND);
        int before = metadata(false, InteractionHand.MAIN_HAND);
        acknowledge(before);
        use(InteractionHand.OFF_HAND);
        acknowledge(before + 1);
        assertTrue(player.packetStateData.isSlowedByUsingItem());
        assertEquals(InteractionHand.OFF_HAND, player.packetStateData.itemInUseHand);
    }

    @Test
    void changingActiveHandKeepsTheExistingUncertaintyWindow() {
        use(InteractionHand.MAIN_HAND);
        int before = metadata(true, InteractionHand.OFF_HAND);
        acknowledge(before);
        assertFalse(player.packetStateData.isSlowedByUsingItem());
        fullSpeedMovement();
        verify(noSlow, never()).flagAndAlertWithSetback();
        acknowledge(before + 1);
        assertTrue(player.packetStateData.isSlowedByUsingItem());
        assertEquals(InteractionHand.OFF_HAND, player.packetStateData.itemInUseHand);
    }

    @ParameterizedTest
    @EnumSource(InteractionHand.class)
    void drinkingAlsoRemainsCheckedWhileFinalPongIsHeld(InteractionHand hand) {
        ItemStack potion = ItemStack.builder().type(ItemTypes.POTION).amount(1).build();
        when(player.inventory.getItemInHand(any())).thenReturn(potion);
        use(hand);
        acknowledge(metadata(true, hand));
        fullSpeedMovement();
        verify(noSlow, atLeastOnce()).flagAndAlertWithSetback();
    }

    @ParameterizedTest
    @ValueSource(floats = {0.2F, 0.5F, 1.0F})
    void respectsServerConfiguredItemUseSpeed(float multiplier) {
        when(player.getClientVersion()).thenReturn(ClientVersion.V_1_21_11);
        ItemStack food = ItemStack.builder().type(ItemTypes.APPLE).amount(1)
                .component(ComponentTypes.USE_EFFECTS, new ItemUseEffects(true, true, multiplier)).build();
        when(player.inventory.getItemInHand(any())).thenReturn(food);
        use(InteractionHand.MAIN_HAND);
        acknowledge(metadata(true, InteractionHand.MAIN_HAND));
        movement(0.098 * multiplier, 40);
        verify(noSlow, never()).flagAndAlertWithSetback();
    }
}
