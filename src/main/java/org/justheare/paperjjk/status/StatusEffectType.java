package org.justheare.paperjjk.status;

public enum StatusEffectType {
    INFINITY_STUN,          // 모든 행동 불가 (천역모 등)
    TECHNIQUE_SEAL,         // 술식 사용 불가
    BURNED_TECHNIQUE,       // 영역전개 후 술식 타버림 — 시간 경과로 회복
    INFORMATION_OVERLOAD,   // 무량공처 — 이동/상호작용 불가, 수준에 따라 지속시간 다름
    BRAIN_DAMAGE            // 뇌손상 — stack 기반 영구 누적, 술식 성능 저하
}
