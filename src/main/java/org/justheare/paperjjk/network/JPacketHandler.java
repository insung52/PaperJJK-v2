package org.justheare.paperjjk.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.justheare.paperjjk.entity.BodyReinMode;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.skill.*;

import java.util.logging.Logger;

/**
 * 클라이언트 → 서버 패킷 수신 라우터.
 * 채널: paperjjk:main
 *
 * 각 패킷은 SkillKeyMap.onKeyPress() 로 위임.
 * 기존 JPacketHandler 의 복잡한 로직은 SkillKeyMap/ActiveSkill 이 담당.
 */
public class JPacketHandler implements PluginMessageListener {

    public static final String CHANNEL = "paperjjk:main";

    private final Logger logger;

    public JPacketHandler(Plugin plugin) {
        this.logger = plugin.getLogger();
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!channel.equals(CHANNEL)) return;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            byte packetId = in.readByte();
            switch (packetId) {
                case PacketIds.SKILL_TECHNIQUE         -> handleTechnique(player, in);
                case PacketIds.SKILL_REVERSE_TECHNIQUE -> handleReverseTechnique(player, in);
                case PacketIds.SKILL_TERMINATE         -> handleTerminate(player, in);
                case PacketIds.SKILL_DISTANCE          -> handleDistance(player, in);
                case PacketIds.SKILL_CONTROL           -> handleControl(player, in);
                case PacketIds.SKILL_RCT               -> handleRct(player, in);
                case PacketIds.BODY_REIN_KEY           -> handleBodyReinKey(player, in);
                case PacketIds.DASH                    -> handleDash(player, in);
                case PacketIds.HANDSHAKE               -> handleHandshake(player, in);
                case PacketIds.PLAYER_INFO_REQUEST     -> handlePlayerInfoRequest(player, in);
                default -> logger.warning(String.format("[Packet] Unknown: 0x%02X from %s", packetId, player.getName()));
            }
        } catch (Exception e) {
            logger.severe("[Packet] Error from " + player.getName() + ": " + e.getMessage());
        }
    }

    private JPlayer getJPlayer(Player player) {
        return JEntityManager.instance != null
                ? JEntityManager.instance.getPlayer(player.getUniqueId())
                : null;
    }

    // ── SKILL_TECHNIQUE (0x03): [action(1)] [slot(1)] [timestamp(8)] ──────

    private void handleTechnique(Player player, ByteArrayDataInput in) {
        byte action = in.readByte();
        byte slot   = in.readByte();
        in.readLong(); // timestamp

        JPlayer jp = getJPlayer(player);
        if (jp == null) return;

        SkillKey key = SkillKey.fromSlot(slot);
        if (key == null) return;

        KeyEventType event = action == PacketIds.SkillAction.START
                ? KeyEventType.PRESS : KeyEventType.RELEASE;
        jp.skillKeyMap.onKeyPress(key, event);
    }

    // ── SKILL_REVERSE_TECHNIQUE (0x04): [action(1)] [slot(1)] [timestamp(8)] ──

    private void handleReverseTechnique(Player player, ByteArrayDataInput in) {
        byte action = in.readByte();
        byte slot   = in.readByte();
        in.readLong();

        JPlayer jp = getJPlayer(player);
        if (jp == null) return;
        if (jp.reverseOutput == null) return; // 반전술식 없음

        SkillKey key = SkillKey.fromSlot(slot);
        if (key == null) return;

        // 역술식 skillId = "reverse_" + 원본 skillId
        SkillSlot slotObj = jp.skillKeyMap.getSlot(key);
        if (slotObj == null) return;
        String reverseId = "reverse_" + slotObj.skillId;

        if (action == PacketIds.SkillAction.START) {
            ActiveSkill skill = SkillFactory.create(reverseId, jp);
            if (skill == null) return;
            jp.addActiveSkill(skill);
            org.justheare.paperjjk.scheduler.WorkScheduler.getInstance().register(skill);
        } else {
            // RELEASE: 역술식 스킬의 stopCharging은 직접 activeSkills 에서 찾아 처리
            for (ActiveSkill skill : jp.getActiveSkills()) {
                if (skill.isCharging()) {
                    skill.stopCharging();
                    break;
                }
            }
        }
    }

    // ── SKILL_TERMINATE (0x05): [slot(1)] [timestamp(8)] ─────────────────

    private void handleTerminate(Player player, ByteArrayDataInput in) {
        byte slot = in.readByte();
        in.readLong();

        JPlayer jp = getJPlayer(player);
        if (jp == null) return;

        SkillKey key = SkillKey.fromSlot(slot);
        if (key == null) return;

        SkillSlot slotObj = jp.skillKeyMap.getSlot(key);
        if (slotObj == null || slotObj.runningSkill == null) return;
        slotObj.runningSkill.end();
    }

    // ── SKILL_DISTANCE (0x09): [slot(1)] [scrollDelta(1)] [timestamp(8)] ─

    private void handleDistance(Player player, ByteArrayDataInput in) {
        byte slot  = in.readByte();
        byte delta = in.readByte(); // +1 up, -1 down
        in.readLong();

        JPlayer jp = getJPlayer(player);
        if (jp == null) return;

        SkillKey key = SkillKey.fromSlot(slot);
        if (key == null) return;

        SkillSlot slotObj = jp.skillKeyMap.getSlot(key);
        if (slotObj == null || slotObj.runningSkill == null) return;
        slotObj.runningSkill.onScrollDistance(delta);
    }

    // ── SKILL_CONTROL (0x06): [action(1)] [slot(1)] [timestamp(8)] ────────

    private void handleControl(Player player, ByteArrayDataInput in) {
        in.readByte(); // action (항상 START)
        byte slot = in.readByte();
        in.readLong();

        JPlayer jp = getJPlayer(player);
        if (jp == null) return;

        SkillKey key = SkillKey.fromSlot(slot);
        if (key == null) return;

        SkillSlot slotObj = jp.skillKeyMap.getSlot(key);
        if (slotObj == null || slotObj.runningSkill == null) return;
        slotObj.runningSkill.onControl();
    }

    // ── SKILL_RCT (0x01): [action(1)] [slot(1)] [timestamp(8)] ──────────

    private void handleRct(Player player, ByteArrayDataInput in) {
        byte action = in.readByte();
        in.readByte(); // slot (RCT는 슬롯 없음)
        in.readLong();

        JPlayer jp = getJPlayer(player);
        if (jp == null || jp.reverseOutput == null) return;

        if (action == PacketIds.SkillAction.START) {
            jp.reverseOutput.start();
        } else {
            jp.reverseOutput.stop();
        }
    }

    // ── DASH (0x10, C2S): [] ─────────────────────────────────────────────

    private void handleDash(Player player, ByteArrayDataInput in) {
        JPlayer jp = getJPlayer(player);
        if (jp == null) return;
        jp.dash();
    }

    // ── BODY_REIN_KEY (0x0F): [action(1)] [mode(1)] ───────────────────────

    private void handleBodyReinKey(Player player, ByteArrayDataInput in) {
        byte action = in.readByte();
        byte modeId = in.readByte();

        JPlayer jp = getJPlayer(player);
        if (jp == null) return;

        if (action == PacketIds.SkillAction.START) {
            BodyReinMode mode = (modeId == PacketIds.BodyReinAction.BITEN)
                    ? BodyReinMode.BITEN : BodyReinMode.NORMAL;
            jp.handleBodyReinKey(true, mode);
        } else {
            jp.handleBodyReinKey(false, BodyReinMode.NONE);
        }
    }

    // ── HANDSHAKE (0x20) ──────────────────────────────────────────────────

    private void handleHandshake(Player player, ByteArrayDataInput in) {
        try {
            String modVersion = in.readUTF();
            logger.info("[Handshake] " + player.getName() + " mod=" + modVersion);
            JPacketSender.sendHandshake(player);

            // 핸드쉐이크 직후 플레이어 정보 전송 (클라이언트 HUD 초기화)
            JPlayer jp = getJPlayer(player);
            if (jp != null) {
                JPacketSender.sendCEUpdate(player, jp);
                JPacketSender.sendPlayerInfoResponse(player, jp);
            }
        } catch (Exception e) {
            logger.warning("[Handshake] Failed to read from " + player.getName());
        }
    }

    // ── PLAYER_INFO_REQUEST (0x0B): [timestamp(8)] ───────────────────────

    private void handlePlayerInfoRequest(Player player, ByteArrayDataInput in) {
        in.readLong(); // timestamp
        JPlayer jp = getJPlayer(player);
        if (jp == null) { logger.warning("[PlayerInfo] No JPlayer for " + player.getName()); return; }
        JPacketSender.sendPlayerInfoResponse(player, jp);
    }
}
