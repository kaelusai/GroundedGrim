package ac.grim.grimac.utils.nmsutil;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SafeGameStatePacketTest {
    @BeforeAll
    static void setup() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class, RETURNS_DEEP_STUBS);
        when(api.getServerManager().getVersion()).thenReturn(ServerVersion.V_1_21_4);
        ByteBufOperator operator = mock(ByteBufOperator.class);
        when(api.getNettyManager().getByteBufOperator()).thenReturn(operator);
        when(operator.readableBytes(any())).thenAnswer(c -> ((ByteBuffer) c.getArgument(0)).remaining());
        when(operator.readerIndex(any())).thenAnswer(c -> ((ByteBuffer) c.getArgument(0)).position());
        when(operator.getUnsignedByte(any(), anyInt())).thenAnswer(c -> (short) (((ByteBuffer) c.getArgument(0)).get((int) c.getArgument(1)) & 255));
        when(operator.readUnsignedByte(any())).thenAnswer(c -> (short) (((ByteBuffer) c.getArgument(0)).get() & 255));
        when(operator.readFloat(any())).thenAnswer(c -> ((ByteBuffer) c.getArgument(0)).getFloat());
        PacketEvents.setAPI(api);
    }

    private PacketSendEvent event(ByteBuffer buffer) {
        PacketSendEvent event = mock(PacketSendEvent.class);
        User user = mock(User.class);
        when(user.getClientVersion()).thenReturn(ClientVersion.V_1_21_4);
        when(event.getUser()).thenReturn(user);
        when(event.getByteBuf()).thenReturn(buffer);
        return event;
    }

    @Test
    void ignoresUnknownAndTruncatedPacketsWithoutConsumingBytes() {
        for (ByteBuffer buffer : new ByteBuffer[]{ByteBuffer.wrap(new byte[]{-1, 0, 0, 0, 0}), ByteBuffer.wrap(new byte[]{11})}) {
            assertNull(SafeGameStatePacket.read(event(buffer)));
            assertEquals(0, buffer.position());
        }
    }

    @Test
    void preservesKnownReasonAndValue() {
        ByteBuffer buffer = ByteBuffer.allocate(5).put((byte) 11).putFloat(1).flip();
        WrapperPlayServerChangeGameState packet = SafeGameStatePacket.read(event(buffer));
        assertNotNull(packet);
        assertEquals(WrapperPlayServerChangeGameState.Reason.ENABLE_RESPAWN_SCREEN, packet.getReason());
        assertEquals(1f, packet.getValue());
    }

    @Test
    void reusesDecodedPacketWhenBufferIsAlreadyConsumed() {
        PacketSendEvent event = event(ByteBuffer.allocate(0));
        WrapperPlayServerChangeGameState cached = new WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.WIN_GAME, 1f);
        doReturn(cached).when(event).getLastUsedWrapper();

        WrapperPlayServerChangeGameState packet = SafeGameStatePacket.read(event);
        assertNotNull(packet);
        assertEquals(WrapperPlayServerChangeGameState.Reason.WIN_GAME, packet.getReason());
        assertEquals(1f, packet.getValue());
    }
}
