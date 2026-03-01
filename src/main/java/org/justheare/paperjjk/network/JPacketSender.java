package org.justheare.paperjjk.network;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Location;
import org.bukkit.entity.Player;
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
            if (p.getLocation().distance(pos) <= range) sendInfinityAkaStart(p, pos, strength, id);
        }
    }

    public static void broadcastInfinityAkaSync(Location pos, float strength, String id, double range) {
        for (Player p : pos.getWorld().getPlayers()) {
            if (p.getLocation().distance(pos) <= range) sendInfinityAkaSync(p, pos, strength, id);
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
}
