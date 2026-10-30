package ac.grim.grimac.utils.nmsutil;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import org.jetbrains.annotations.Nullable;

public final class SafeGameStatePacket {
    private static final int REASON_COUNT = WrapperPlayServerChangeGameState.Reason.values().length;

    private SafeGameStatePacket() {
    }

    public static @Nullable WrapperPlayServerChangeGameState read(PacketSendEvent event) {
        if (!(event.getLastUsedWrapper() instanceof WrapperPlayServerChangeGameState)) {
            Object buffer = event.getByteBuf();
            if (ByteBufHelper.readableBytes(buffer) < 5
                    || ByteBufHelper.getUnsignedByte(buffer, ByteBufHelper.readerIndex(buffer)) >= REASON_COUNT) {
                return null;
            }
        }
        return new WrapperPlayServerChangeGameState(event);
    }
}
