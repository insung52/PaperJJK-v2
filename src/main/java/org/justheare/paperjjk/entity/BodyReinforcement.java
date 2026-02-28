package org.justheare.paperjjk.entity;

/**
 * 신체강화(Body Reinforcement) 상태 관리.
 *
 * NORMAL : 신체강화 — 데미지 증가, 감소, 속도, 점프력 강화
 * BITEN  : 비전, 낙화의 정 — 속도/점프 없음, 영역 필중도 방어
 */
public class BodyReinforcement {

    private BodyReinMode mode = BodyReinMode.NONE;
    private double current = 0;
    private double max;

    /** 틱당 자연 소모 비율 */
    private static final double TICK_DECAY_RATIO = 0.001;

    public BodyReinforcement(double max) {
        this.max = max;
    }

    // ── 충전 ──────────────────────────────────────────────────────────────

    public void startCharging(BodyReinMode mode) {
        if (this.mode == BodyReinMode.NONE) {
            this.mode = mode;
        }
    }

    public void stopCharging() {
        // 충전 중단 — mode는 유지 (충전된 주력은 남아 있음)
    }

    /**
     * 충전 중에 CursedEnergy로부터 받은 실제 충전량을 적용.
     * JEntity.onTick() 에서 distributeOutput() 결과를 받아 호출.
     */
    public void addCharge(double amount) {
        current = Math.min(max, current + amount);
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    /** JEntity.onTick() 에서 매 틱 호출 */
    public void onTick() {
        if (current <= 0) {
            mode = BodyReinMode.NONE;
            return;
        }
        current = Math.max(0, current - max * TICK_DECAY_RATIO);
    }

    // ── 효과 계산 (DamagePipeline 에서 참조) ──────────────────────────────

    /**
     * 받는 데미지 감소량 (PHYSICAL 계열에만 적용).
     * 최솟값: maxOutput * 0.01, 최댓값: maxOutput (= max)
     */
    public double getDamageReduction() {
        if (mode == BodyReinMode.NONE) return 0;
        return Math.max(max * 0.01, Math.min(current, max));
    }

    /**
     * 가하는 데미지 배율 (직접 타격에만 적용).
     * current가 많을수록 증가.
     */
    public double getAttackMultiplier() {
        if (mode == BodyReinMode.NONE) return 1.0;
        return 1.0 + (current / max) * 0.5;
    }

    /** NORMAL 전용 — 속도 보너스 */
    public double getSpeedBonus() {
        if (mode != BodyReinMode.NORMAL) return 0;
        return (current / max) * 0.3;
    }

    /** NORMAL 전용 — 점프력 보너스 */
    public double getJumpBonus() {
        if (mode != BodyReinMode.NORMAL) return 0;
        return (current / max) * 0.2;
    }

    /** BITEN 전용 — 영역전개 필중도 방어 */
    public boolean blocksDomainSureHit() {
        return mode == BodyReinMode.BITEN && current > 0;
    }

    // ── 조회 / 설정 ───────────────────────────────────────────────────────

    public BodyReinMode getMode() { return mode; }
    public double getCurrent() { return current; }
    public double getMax() { return max; }
    public boolean isActive() { return mode != BodyReinMode.NONE && current > 0; }

    public void setMax(double max) {
        this.max = max;
        this.current = Math.min(current, max);
    }
}
