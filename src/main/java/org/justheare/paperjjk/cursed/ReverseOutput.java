package org.justheare.paperjjk.cursed;

import org.justheare.paperjjk.entity.JEntity;

/**
 * 반전술식(Reverse Cursed Technique) 출력 관리.
 * 주력을 소모해 체력을 재생. JPlayer만 사용 가능 (JCursedSpirit은 null).
 *
 * 동작: shift+z 누르고 있으면 주력 소모 → 체력/디버프 회복
 */
public class ReverseOutput {

    private boolean active = false;

    /** 소모 주력 → 회복 체력 효율 */
    private static final double HEAL_EFFICIENCY = 0.01;

    /** 술식에 RCT 적용 시 출력 배율 (최소 2배) */
    private static final double RCT_MULTIPLIER = 2.0;

    // ── 활성화 ────────────────────────────────────────────────────────────

    public void start() { active = true; }

    public void stop() { active = false; }

    public boolean isActive() { return active; }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    /**
     * JEntity.onTick() 에서 호출.
     * active=true 이고 플레이어가 sneaking 중이면 주력 소모 → 체력 회복.
     */
    public void onTick(JEntity entity) {
        if (!active) return;

        // 실제 소모/회복 로직은 JPlayer 에서 처리
        // (isSneaking() 체크, 포션 이펙트 제거 등 Player API 필요)
    }

    // ── 술식반전 출력 배율 ────────────────────────────────────────────────

    /** RCT 적용 스킬의 attackOutput에 곱해지는 배율 */
    public double getRCTMultiplier() {
        return RCT_MULTIPLIER;
    }

    public double getHealEfficiency() {
        return HEAL_EFFICIENCY;
    }
}
