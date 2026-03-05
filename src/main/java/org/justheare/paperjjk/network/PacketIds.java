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
    public static final byte BODY_REIN_KEY           = 0x0F; // 신체강화 키 홀드/릴리즈 (C2S)
    // 주의: 아래 DASH 는 C2S 전용 (S2C 의 0x10 TECHNIQUE_FEEDBACK 과 방향이 달라 충돌 없음)
    public static final byte DASH                    = 0x10; // 대쉬 요청 (Z + Space, C2S)

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

    // ── Server → Client: HUD 실시간 업데이트 ──────────────────────────────
    public static final byte SLOT_GAUGE_UPDATE  = 0x30; // 슬롯 충전/발동 게이지
    public static final byte BODY_REIN_UPDATE   = 0x31; // 신체강화 비율

    // ── Server → Client: 참격 효과 ────────────────────────────────────────
    // KAI_SLASH  Format: [packetId(1)][hitX(4)][hitY(4)][hitZ(4)][axisX(4)][axisY(4)][axisZ(4)]
    public static final byte KAI_SLASH          = 0x32; // 해(Kai) 참격 화면 효과
    // HACHI_SLASH Format: [packetId(1)][hitX(4)][hitY(4)][hitZ(4)]
    public static final byte HACHI_SLASH        = 0x33; // 팔(Hachi) 격자 참격 화면 효과

    // ── 양방향 ─────────────────────────────────────────────────────────────
    public static final byte HANDSHAKE = 0x20;

    /** BODY_REIN_KEY 의 mode 값 (C2S) */
    public static class BodyReinAction {
        public static final byte NORMAL = 0x01; // 신체강화 (일반)
        public static final byte BITEN  = 0x02; // 비전, 낙화의 정
    }

    /** SLOT_GAUGE_UPDATE 의 슬롯 상태 */
    public static class SlotGaugeState {
        public static final byte NONE       = 0x00; // 스킬 없음 / 종료
        public static final byte CHARGING   = 0x01; // 키 홀드 중 (초기 충전)
        public static final byte ACTIVE     = 0x02; // 발동 완료, 스킬 실행 중
        public static final byte RECHARGING = 0x03; // 재충전 중 (발동 후 재홀드)
    }

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

    /** DOMAIN_VISUAL (0x11) action 값 */
    public static class DomainVisualAction {
        public static final byte START     = 0x01; // 영역전개 시작 (상세 정보 포함)
        public static final byte SYNC      = 0x02; // 반경 동기화
        public static final byte CLASH     = 0x03; // 밀어내기 싸움 개시
        public static final byte CLASH_END = 0x04; // 밀어내기 싸움 종료
        public static final byte END       = 0x05; // 영역전개 종료
    }

    public static class DomainFlags {
        public static final byte NORMAL     = 0x00;
        public static final byte NO_BARRIER = 0x01;
    }

    /** DOMAIN_SETTINGS (0x0A) action 값 */
    public static class DomainSettingsAction {
        /** 클라이언트가 현재 설정 요청 */
        public static final byte REQUEST = 0x01;
        /** 클라이언트가 새 설정 전송 */
        public static final byte UPDATE  = 0x02;
    }

    public static class DomainType {
        public static final int NORMAL     = 0;
        public static final int NO_BARRIER = 1;
        public static final int MIZUSHI    = 2;
        public static final int INFINITY   = 3;
        public static final int OTHER      = 4;
    }
}
