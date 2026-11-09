package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

@CheckData(name = "FreezeAttack", description = "Hits delivered while withholding outbound packets", experimental = true, decay = 0.05, setback = 5)
public class FreezeAttack extends Check implements PacketReceiveListener {

    private boolean enabled = true;
    private boolean cancelHits = true;
    private boolean doSetback = true;

    public FreezeAttack(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        enabled = config.getBooleanElse("FreezeAttack.enabled", true);
        cancelHits = config.getBooleanElse("FreezeAttack.cancel-hits", true);
        doSetback = config.getBooleanElse("FreezeAttack.setback", true);
        player.freezeDetector.gapThresholdMs = config.getIntElse("FreezeAttack.gap-threshold-ms", 1500);
        player.freezeDetector.gapStrongThresholdMs = config.getIntElse("FreezeAttack.gap-strong-threshold-ms", 2000);
        player.freezeDetector.scoreCancel = config.getIntElse("FreezeAttack.score-cancel", 3);
        player.freezeDetector.scoreFlag = config.getIntElse("FreezeAttack.score-flag", 5);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY
                && event.getPacketType() != PacketType.Play.Client.ATTACK) return;

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrap = new WrapperPlayClientInteractEntity(event);
            if (wrap.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        }

        player.freezeDetector.onAttackPacket();

        int score = player.freezeDetector.getScore();
        if (score < player.freezeDetector.scoreCancel) return;

        if (cancelHits && shouldModifyPackets()) {
            // deny the withheld hit but stay off the shared spam-kick counter (GrimPlayer.onPacketCancel) -
            // a buffered-attack flush from a real lagger would otherwise cross spamThreshold and disconnect them
            event.setCancelled(true);
        }

        if (player.freezeDetector.shouldFlag()) {
            if (flagAndAlert(String.format("gap=%dms attacks=%d score=%d",
                    player.freezeDetector.getCurrentGapMs(),
                    player.freezeDetector.getAttacksDuringGap(),
                    score))) {
                if (doSetback) setbackIfAboveSetbackVL();
            }
        }
    }
}
