package org.justheare.paperjjk.network;

/**
 * Plugin Messaging 패킷 ID 정의.
 * 기존 PaperJJK 의 PacketIds 와 동일한 값 유지 (클라이언트 모드 호환).
 */
public class PacketIds {

    // ── Client → Server ────────────────────────────────────────────────────
    public static final byte SKILL_RCT               = 0x01;
    public static final byte SKILL_SIMPLE_DOMAIN     = 0x02;
    public static final byte SKILL_TECHNIQUE         = 0x03;  // X/C/V/B 키
    public static final byte SKILL_REVERSE_TECHNIQUE = 0x04;  // Z + X/C/V/B
    public static final byte SKILL_TERMINATE         = 0x05;
    public static final byte SKILL_CONTROL           = 0x06;  // T + 슬롯 (fixed 토글)
    public static final byte SKILL_CONFIG            = 0x07;
    public static final byte DOMAIN_EXPANSION        = 0x08;
    public static final byte SKILL_DISTANCE          = 0x09;  // 스크롤
    public static final byte DOMAIN_SETTINGS         = 0x0A;
    public static final byte PLAYER_INFO_REQUEST     = 0x0B;
    public static final byte SKILL_INFO_REQUEST      = 0x0C;
    public static final byte SKILL_BINDING_UPDATE    = 0x0D;
    public static final byte CLIENT_SETTINGS_UPDATE  = 0x0E;

    // ── Server → Client ────────────────────────────────────────────────────
    public static final byte TECHNIQUE_FEEDBACK      = 0x10;
    public static final byte DOMAIN_VISUAL           = 0x11;
    public static final byte CE_UPDATE               = 0x12;
    public static final byte TECHNIQUE_COOLDOWN      = 0x13;
    public static final byte PARTICLE_EFFECT         = 0x14;
    public static final byte SCREEN_EFFECT           = 0x15;
    public static final byte DOMAIN_SETTINGS_RESPONSE= 0x16;
    public static final byte INFINITY_AO             = 0x17;
    public static final byte INFINITY_AKA            = 0x18;
    public static final byte INFINITY_MURASAKI       = 0x19;
    public static final byte PLAYER_INFO_RESPONSE    = 0x1A;
    public static final byte SKILL_INFO_RESPONSE     = 0x1B;

    // ── Server → Client: 간이영역 ──────────────────────────────────────────
    public static final byte SIMPLE_DOMAIN_ACTIVATE     = 0x21;
    public static final byte SIMPLE_DOMAIN_CHARGING_END = 0x22;
    public static final byte SIMPLE_DOMAIN_POWER_SYNC   = 0x23;
    public static final byte SIMPLE_DOMAIN_DEACTIVATE   = 0x24;
    public static final byte SIMPLE_DOMAIN_TRANSLATE    = 0x25;

    // ── 양방향 ─────────────────────────────────────────────────────────────
    public static final byte HANDSHAKE = 0x20;

    // ── 내부 상수 클래스 ───────────────────────────────────────────────────

    public static class SkillAction {
        public static final byte START = 0x01;
        public static final byte END   = 0x02;
    }

    /** SKILL_TECHNIQUE slot 값 (X=0x01, C=0x02, V=0x03, B=0x04) */
    public static class TechniqueSlot {
        public static final byte SLOT_1 = 0x01;  // X
        public static final byte SLOT_2 = 0x02;  // C
        public static final byte SLOT_3 = 0x03;  // V
        public static final byte SLOT_4 = 0x04;  // B
    }

    public static class InfinityAoAction {
        public static final byte START = 0x01;
        public static final byte SYNC  = 0x02;
        public static final byte END   = 0x03;
    }

    public static class InfinityAkaAction {
        public static final byte START = 0x01;
        public static final byte SYNC  = 0x02;
        public static final byte END   = 0x03;
    }

    public static class InfinityMurasakiAction {
        public static final byte START         = 0x01;
        public static final byte SYNC          = 0x02;
        public static final byte START_EXPLODE = 0x03;
        public static final byte SYNC_RADIUS   = 0x04;
        public static final byte END           = 0x05;
    }

    public static class DomainFlags {
        public static final byte NORMAL     = 0x00;
        public static final byte NO_BARRIER = 0x01;
    }

    public static class DomainType {
        public static final int NORMAL     = 0;
        public static final int NO_BARRIER = 1;
        public static final int MIZUSHI    = 2;
        public static final int INFINITY   = 3;
        public static final int OTHER      = 4;
    }
}
