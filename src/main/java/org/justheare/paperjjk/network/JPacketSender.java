package org.justheare.paperjjk.network;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.entity.BodyReinforcement;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;

/**
 * 서버 → 클라이언트 패킷 전송 유틸리티.
 * 모든 메서드는 static. 채널 = JPacketHandler.CHANNEL.
 */
public class JPacketSender {

    private JPacketSender() {}

    private static void send(Player player, byte[] data) {
        player.sendPluginMessage(PaperJJK.instance, JPacketHandler.CHANNEL, data);
    }

    // ── HANDSHAKE (0x20) ──────────────────────────────────────────────────

    public static void sendHandshake(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.HANDSHAKE);
        out.writeInt(1);
        out.writeUTF("PaperJJK-2.0.0");
        out.writeInt(0x07);
        send(player, out.toByteArray());
    }

    // ── TECHNIQUE_FEEDBACK (0x10) ─────────────────────────────────────────

    public static void sendTechniqueResult(Player player, boolean success, byte reason, String message) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.TECHNIQUE_FEEDBACK);
        out.writeBoolean(success);
        out.writeByte(reason);
        out.writeUTF(message);
        send(player, out.toByteArray());
    }

    // ── CE_UPDATE (0x12) ──────────────────────────────────────────────────
    // 클라이언트 파싱: readInt(current), readInt(max), readFloat(regenRate),
    //                  readUTF(technique), readBoolean(blocked)

    public static void sendCEUpdate(Player player, JPlayer jp) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.CE_UPDATE);
        out.writeInt((int) jp.cursedEnergy.getCurrent());
        out.writeInt((int) jp.cursedEnergy.getMax());
        out.writeFloat(0f); // regenRate — 현재 미구현
        out.writeUTF(jp.technique != null ? jp.technique.getKey() : "");
        out.writeBoolean(jp.isTechniqueBlocked());
        send(player, out.toByteArray());
    }

    // ── PLAYER_INFO_RESPONSE (0x1A) ───────────────────────────────────────
    // 클라이언트 파싱: readUTF(naturaltech), readInt(curseEnergy), readInt(maxCurseEnergy),
    //                  readBoolean(hasRCT), readInt(domainLevel),
    //                  readUTF(slot1..4), readInt(skillCount), readUTF(skillId)...

    public static void sendPlayerInfoResponse(Player player, JPlayer jp) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.PLAYER_INFO_RESPONSE);
        out.writeUTF(jp.technique != null ? jp.technique.getDisplayName() : "없음");
        out.writeInt((int) jp.cursedEnergy.getCurrent());
        out.writeInt((int) jp.cursedEnergy.getMax());
        out.writeBoolean(jp.reverseOutput != null);
        out.writeInt(0); // domainLevel — 추후 구현
        out.writeInt(jp.cursedEnergy.getEfficiencyLevel());
        out.writeBoolean(jp.canGraspAirSurface);

        // 슬롯 1-4 (X, C, V, B) 스킬 ID
        out.writeUTF(slotId(jp, SkillKey.X));
        out.writeUTF(slotId(jp, SkillKey.C));
        out.writeUTF(slotId(jp, SkillKey.V));
        out.writeUTF(slotId(jp, SkillKey.B));

        // 이 술식에서 사용 가능한 스킬 목록
        if (jp.technique != null) {
            var bindings = jp.technique.getDefaultBindings();
            out.writeInt(bindings.size());
            for (SkillSlot slot : bindings.values()) {
                out.writeUTF(slot.skillId);
            }
        } else {
            out.writeInt(0);
        }

        send(player, out.toByteArray());
    }

    private static String slotId(JPlayer jp, SkillKey key) {
        SkillSlot slot = jp.skillKeyMap.getSlot(key);
        return slot != null ? slot.skillId : "";
    }

    // ── SLOT_GAUGE_UPDATE (0x30) ──────────────────────────────────────────
    // 슬롯 X/C/V/B 의 상태·게이지·자물쇠·레이블을 한 패킷에 전송.
    // 각 슬롯: [state(1)][gauge(1, 0~100)][locked(1 boolean)][label(UTF)]

    public static void sendSlotGaugeUpdate(Player player, byte[] states, float[] gauges,
                                           boolean[] locked, String[] labels) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.SLOT_GAUGE_UPDATE);
        for (int i = 0; i < 4; i++) {
            out.writeByte(states[i]);
            out.writeByte((byte)(int)(Math.max(0f, Math.min(1f, gauges[i])) * 100));
            out.writeBoolean(locked[i]);
            out.writeUTF(labels[i] != null ? labels[i] : "");
        }
        send(player, out.toByteArray());
    }

    // ── BODY_REIN_UPDATE (0x31) ───────────────────────────────────────────

    public static void sendBodyReinUpdate(Player player, BodyReinforcement br) {
        float ratio = br.getMax() > 0 ? (float)(br.getCurrent() / br.getMax()) : 0f;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.BODY_REIN_UPDATE);
        out.writeByte((byte)(int)(Math.max(0f, Math.min(1f, ratio)) * 100));
        send(player, out.toByteArray());
    }

    // ── DOMAIN_SETTINGS_RESPONSE (0x16) ──────────────────────────────────
    // S2C: [packetId(1)][normalRange(4)][noBarrierRange(4)][timestamp(8)]

    public static void sendDomainSettingsResponse(Player player, JPlayer jp) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.DOMAIN_SETTINGS_RESPONSE);
        out.writeInt(jp.normalDomainRange);
        out.writeInt(jp.noBarrierDomainRange);
        out.writeLong(System.currentTimeMillis());
        send(player, out.toByteArray());
    }

    // ── DOMAIN_VISUAL (0x11) ──────────────────────────────────────────────
    // S2C: [packetId(1)][action(1)][...action별 payload...]
    //
    // START payload: [casterUUID(16)][domainType(4)][cx(8)][cy(8)][cz(8)][maxRadius(4)][isOpen(1)]
    // END   payload: [casterUUID(16)]

    public static void sendDomainVisualStart(Player player, java.util.UUID casterUuid,
            int domainType, Location center, float maxRadius, boolean isOpen) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.DOMAIN_VISUAL);
        out.writeByte(PacketIds.DomainVisualAction.START);
        out.writeLong(casterUuid.getMostSignificantBits());
        out.writeLong(casterUuid.getLeastSignificantBits());
        out.writeInt(domainType);
        out.writeDouble(center.getX());
        out.writeDouble(center.getY());
        out.writeDouble(center.getZ());
        out.writeFloat(maxRadius);
        out.writeBoolean(isOpen);
        send(player, out.toByteArray());
    }

    public static void sendDomainVisualEnd(Player player, java.util.UUID casterUuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.DOMAIN_VISUAL);
        out.writeByte(PacketIds.DomainVisualAction.END);
        out.writeLong(casterUuid.getMostSignificantBits());
        out.writeLong(casterUuid.getLeastSignificantBits());
        send(player, out.toByteArray());
    }

    public static void broadcastDomainVisualStart(Location center, java.util.UUID casterUuid,
            int domainType, Location domainCenter, float maxRadius, boolean isOpen, double range) {
        if (center.getWorld() == null) return;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distance(center) <= range) {
                sendDomainVisualStart(p, casterUuid, domainType, domainCenter, maxRadius, isOpen);
            }
        }
    }

    public static void broadcastDomainVisualEnd(Location center, java.util.UUID casterUuid, double range) {
        if (center.getWorld() == null) return;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distance(center) <= range) {
                sendDomainVisualEnd(p, casterUuid);
            }
        }
    }

    /** 월드/거리 제한 없이 전체 접속 플레이어에게 END 패킷 전송. 월드 전환 버그 방지. */
    public static void broadcastDomainVisualEndGlobal(java.util.UUID casterUuid) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.DOMAIN_VISUAL);
        out.writeByte(PacketIds.DomainVisualAction.END);
        out.writeLong(casterUuid.getMostSignificantBits());
        out.writeLong(casterUuid.getLeastSignificantBits());
        byte[] data = out.toByteArray();
        for (Player p : Bukkit.getOnlinePlayers()) send(p, data);
    }

    /** 월드/거리 제한 없이 전체 접속 플레이어에게 START 패킷 전송. 늦게 들어온 플레이어 복구용. */
    public static void broadcastDomainVisualStartGlobal(java.util.UUID casterUuid,
            int domainType, Location domainCenter, float currentRadius, boolean isOpen) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.DOMAIN_VISUAL);
        out.writeByte(PacketIds.DomainVisualAction.START);
        out.writeLong(casterUuid.getMostSignificantBits());
        out.writeLong(casterUuid.getLeastSignificantBits());
        out.writeInt(domainType);
        out.writeDouble(domainCenter.getX());
        out.writeDouble(domainCenter.getY());
        out.writeDouble(domainCenter.getZ());
        out.writeFloat(currentRadius);
        out.writeBoolean(isOpen);
        byte[] data = out.toByteArray();
        for (Player p : Bukkit.getOnlinePlayers()) send(p, data);
    }

    // SYNC payload: [casterUUID(16)][radius(4f)]
    public static void sendDomainVisualSync(Player player, java.util.UUID casterUuid, float radius) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.DOMAIN_VISUAL);
        out.writeByte(PacketIds.DomainVisualAction.SYNC);
        out.writeLong(casterUuid.getMostSignificantBits());
        out.writeLong(casterUuid.getLeastSignificantBits());
        out.writeFloat(radius);
        send(player, out.toByteArray());
    }

    public static void broadcastDomainVisualSync(Location center, java.util.UUID casterUuid, float radius, double range) {
        if (center.getWorld() == null) return;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distance(center) <= range) {
                sendDomainVisualSync(p, casterUuid, radius);
            }
        }
    }

    // ── INFINITY_AO (0x17) ────────────────────────────────────────────────

    public static void sendInfinityAoStart(Player player, Location pos, float strength, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_AO);
        out.writeByte(PacketIds.InfinityAoAction.START);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(strength);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityAoSync(Player player, Location pos, float strength, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_AO);
        out.writeByte(PacketIds.InfinityAoAction.SYNC);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(strength);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityAoEnd(Player player, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_AO);
        out.writeByte(PacketIds.InfinityAoAction.END);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void broadcastInfinityAoStart(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAoStart(p, pos, strength, id);
        }
    }

    public static void broadcastInfinityAoSync(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAoSync(p, pos, strength, id);
        }
    }

    public static void broadcastInfinityAoEnd(Location pos, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAoEnd(p, id);
        }
    }

    // ── INFINITY_AKA (0x18) ───────────────────────────────────────────────

    public static void sendInfinityAkaStart(Player player, Location pos, float strength, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_AKA);
        out.writeByte(PacketIds.InfinityAkaAction.START);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(strength);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityAkaSync(Player player, Location pos, float strength, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_AKA);
        out.writeByte(PacketIds.InfinityAkaAction.SYNC);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(strength);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityAkaEnd(Player player, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_AKA);
        out.writeByte(PacketIds.InfinityAkaAction.END);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void broadcastInfinityAkaStart(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAkaStart(p, pos, strength*0.2f, id);
        }
    }

    public static void broadcastInfinityAkaSync(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAkaSync(p, pos, strength*0.2f, id);
        }
    }

    public static void broadcastInfinityAkaEnd(Location pos, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAkaEnd(p, id);
        }
    }

    // ── INFINITY_MURASAKI (0x19) ──────────────────────────────────────────

    public static void sendInfinityMurasakiStart(Player player, Location pos, float strength, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_MURASAKI);
        out.writeByte(PacketIds.InfinityMurasakiAction.START);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(strength);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityMurasakiSync(Player player, Location pos, float strength, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_MURASAKI);
        out.writeByte(PacketIds.InfinityMurasakiAction.SYNC);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(strength);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityMurasakiStartExplode(Player player, Location pos, float radius, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_MURASAKI);
        out.writeByte(PacketIds.InfinityMurasakiAction.START_EXPLODE);
        out.writeDouble(pos.getX());
        out.writeDouble(pos.getY());
        out.writeDouble(pos.getZ());
        out.writeFloat(radius);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityMurasakiSyncRadius(Player player, float radius, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_MURASAKI);
        out.writeByte(PacketIds.InfinityMurasakiAction.SYNC_RADIUS);
        out.writeFloat(radius);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void sendInfinityMurasakiEnd(Player player, String id) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.INFINITY_MURASAKI);
        out.writeByte(PacketIds.InfinityMurasakiAction.END);
        out.writeUTF(id);
        send(player, out.toByteArray());
    }

    public static void broadcastInfinityMurasakiStart(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityMurasakiStart(p, pos, strength, id);
        }
    }

    public static void broadcastInfinityMurasakiSync(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityMurasakiSync(p, pos, strength, id);
        }
    }

    public static void broadcastInfinityMurasakiStartExplode(Location pos, float radius, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityMurasakiStartExplode(p, pos, radius, id);
        }
    }

    public static void broadcastInfinityMurasakiSyncRadius(Location pos, float radius, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityMurasakiSyncRadius(p, radius, id);
        }
    }

    public static void broadcastInfinityMurasakiEnd(Location pos, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityMurasakiEnd(p, id);
        }
    }

    // ── KAI_SLASH (0x32) ──────────────────────────────────────────────────
    // S2C: [packetId(1)][hitX(4)][hitY(4)][hitZ(4)][axisX(4)][axisY(4)][axisZ(4)]
    // hitPos: 참격 중심 위치 (월드 좌표), slashAxis: 정규화된 참격 방향 벡터

    public static void sendKaiSlash(Player player, Location hitPos, Vector slashAxis) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.KAI_SLASH);
        out.writeFloat((float) hitPos.getX());
        out.writeFloat((float) hitPos.getY());
        out.writeFloat((float) hitPos.getZ());
        out.writeFloat((float) slashAxis.getX());
        out.writeFloat((float) slashAxis.getY());
        out.writeFloat((float) slashAxis.getZ());
        send(player, out.toByteArray());
    }

    // ── HACHI_SLASH (0x33) ─────────────────────────────────────────────────
    // S2C: [packetId(1)][hitX(4)][hitY(4)][hitZ(4)]
    // 격자 회전 각도는 클라이언트가 랜덤 생성

    public static void broadcastHachiSlash(Location hitPos, double range) {
        if (hitPos.getWorld() == null) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(PacketIds.HACHI_SLASH);
        out.writeFloat((float) hitPos.getX());
        out.writeFloat((float) hitPos.getY());
        out.writeFloat((float) hitPos.getZ());
        byte[] data = out.toByteArray();
        for (Player p : hitPos.getWorld().getPlayers()) {
            if (p.getLocation().distance(hitPos) <= range) send(p, data);
        }
    }

    public static void broadcastKaiSlash(Location hitPos, Vector slashAxis, double range) {
        if (hitPos.getWorld() == null) return;
        for (Player p : hitPos.getWorld().getPlayers()) {
            if (p.getLocation().distance(hitPos) <= range) {
                sendKaiSlash(p, hitPos, slashAxis);
            }
        }
    }
}
