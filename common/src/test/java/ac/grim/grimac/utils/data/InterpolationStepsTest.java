package ac.grim.grimac.utils.data;

import ac.grim.grimac.testutil.GrimPlayerFixture;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterpolationStepsTest {
    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    @ParameterizedTest
    @CsvSource({
            "true, V_1_21, 10",
            "true, V_1_21_2, 3",
            "true, V_1_21_5, 3",
            "true, V_26_2, 3",
            "false, V_1_21_4, 5",
            "false, V_1_21_5, 3",
            "false, V_26_2, 3"
    })
    void vehicleInterpolationFollowsClientVersion(boolean boat, ClientVersion version, int expected) throws ReflectiveOperationException {
        PacketEntity entity = new PacketEntity(GrimPlayerFixture.player(), boat ? EntityTypes.BOAT : EntityTypes.MINECART);
        assertEquals(expected, ReachInterpolationData.getInterpolationStepsFor(entity, version));
    }
}
