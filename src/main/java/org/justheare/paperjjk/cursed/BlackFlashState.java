package org.justheare.paperjjk.cursed;

/**
 * 흑섬(Black Flash) 및 Zone 상태를 관리.
 *
 * 흑섬: 주력으로 기본 공격 시 매우 낮은 확률로 발동.
 *       공격 데미지 2.5배 이상 증폭 + Zone 상태 진입.
 *
 * Zone: 신체 능력, 주력 조작 능력, 기본 방출량 대폭 상승.
 *       lifeTimeCount가 쌓일수록 baseProbability 영구 상승.
 */
public class BlackFlashState {

    // ── 확률 ──────────────────────────────────────────────────────────────

    /** 기본 흑섬 발동 확률 (lifeTimeCount에 따라 영구 상승) */
    private double baseProbability;

    /** Zone 중 발동 확률 (baseProbability보다 높음) */
    private static final double ZONE_PROBABILITY_MULTIPLIER = 3.0;

    /** 흑섬 데미지 배율 기본값 */
    private static final double BASE_DAMAGE_MULTIPLIER = 2.5;

    // ── Zone 상태 ─────────────────────────────────────────────────────────

    private boolean inZone = false;
    private int zoneRemainingTicks = 0;

    private static final int ZONE_DURATION_TICKS = 20 * 60; // 60초

    /** Zone 중 maxOutput 배율 (CursedEnergy.getMaxOutput() 에서 곱해짐) */
    private static final double ZONE_OUTPUT_MULTIPLIER = 2.0;

    /** Zone 중 신체강화 보너스 배율 */
    private static final double ZONE_BODY_REIN_BONUS = 1.5;

    // ── 성장 추적 ─────────────────────────────────────────────────────────

    /** 누적 흑섬 발동 횟수 (영구적, baseProbability 상승에 기여) */
    private int lifeTimeCount = 0;

    /** 현재 Zone 세션의 연속 흑섬 횟수 */
    private int sessionCount = 0;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public BlackFlashState(double baseProbability) {
        this.baseProbability = baseProbability;
    }

    // ── 핵심 동작 ─────────────────────────────────────────────────────────

    /**
     * 흑섬 발동 판정 (물리 공격 시 DamagePipeline Phase 1에서 호출).
     * 발동 시 Zone 시작, true 반환.
     */
    public boolean tryTrigger() {
        double probability = inZone
                ? baseProbability * ZONE_PROBABILITY_MULTIPLIER
                : baseProbability;

        if (Math.random() < probability) {
            lifeTimeCount++;
            sessionCount++;
            updateBaseProbability();
            startZone();
            return true;
        }
        return false;
    }

    /**
     * 흑섬 발동 시 데미지 배율.
     * sessionCount가 높을수록 약간 추가 증폭.
     */
    public double getDamageMultiplier() {
        return BASE_DAMAGE_MULTIPLIER + Math.min(sessionCount - 1, 5) * 0.1;
    }

    // ── Zone ──────────────────────────────────────────────────────────────

    public void startZone() {
        inZone = true;
        zoneRemainingTicks = ZONE_DURATION_TICKS;
    }

    public void endZone() {
        inZone = false;
        zoneRemainingTicks = 0;
        sessionCount = 0;
    }

    /** JEntity.onTick() 에서 매 틱 호출 */
    public void onTick() {
        if (!inZone) return;
        zoneRemainingTicks--;
        if (zoneRemainingTicks <= 0) {
            endZone();
        }
    }

    // ── CursedEnergy 연동 ─────────────────────────────────────────────────

    /** CursedEnergy.getMaxOutput() 에서 이 값을 곱해 Zone 보너스 적용 */
    public double getOutputMultiplier() {
        return inZone ? ZONE_OUTPUT_MULTIPLIER : 1.0;
    }

    public double getBodyReinBonus() {
        return inZone ? ZONE_BODY_REIN_BONUS : 1.0;
    }

    // ── 성장 ──────────────────────────────────────────────────────────────

    /** lifeTimeCount에 따라 baseProbability 영구 상승 */
    private void updateBaseProbability() {
        // 흑섬을 많이 경험할수록 발동 확률이 조금씩 오름 (상한 있음)
        baseProbability = Math.min(0.1, 0.01 + lifeTimeCount * 0.0005);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public boolean isInZone() { return inZone; }
    public int getLifeTimeCount() { return lifeTimeCount; }
    public int getSessionCount() { return sessionCount; }
    public double getBaseProbability() { return baseProbability; }

    public void setLifeTimeCount(int count) {
        this.lifeTimeCount = count;
        updateBaseProbability();
    }
}
