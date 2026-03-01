package org.justheare.paperjjk.cursed;

import java.util.List;

/**
 * 주력(저주 에너지) 상태를 담당하는 클래스.
 * 기존 Jobject의 max_curseenergy, curseenergy, max_cursecurrent, cursecurrent 등을 통합.
 */
public class CursedEnergy {

    // 최대 주력량 (캐릭터 등급에 따라 고정)
    // 완전체 스쿠나: 400,000,000 / 고죠: 50,000,000 / 3급: 400 / 일반인: 5
    private double max;

    // 현재 주력량
    private double current;

    // 틱당 기본 회복량 상수
    private static final double BASE_REGEN = 0.01;

    // 최대 방출량 선형 계수 — maxOutput = BASE_OUTPUT + current * OUTPUT_RATIO
    private static final double BASE_OUTPUT   = 0.05;
    private static final double OUTPUT_RATIO  = 0.1;

    /**
     * 주력 효율 레벨 (0~100).
     * 레벨 0: 명목 소모량의 100% 실제 소모 (기본값).
     * 레벨 100: 명목 소모량의 1% 실제 소모.
     * 실제소모 = 명목소모 × (1 - level × 0.0099)
     */
    private int efficiencyLevel = 0;

    public CursedEnergy(double max) {
        this.max = max;
        this.current = max;
    }

    public CursedEnergy(double max, double initial) {
        this.max = max;
        this.current = Math.min(initial, max);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public double getMax() { return max; }
    public double getCurrent() { return current; }
    public int getEfficiencyLevel() { return efficiencyLevel; }

    public void setEfficiencyLevel(int level) {
        efficiencyLevel = Math.max(0, Math.min(100, level));
    }

    /**
     * 명목 소모량에 효율을 적용한 실제 소모량 반환.
     * 소모 시 자동 적용되므로 외부에서 직접 호출할 필요 없음.
     */
    private double applyEfficiency(double nominalAmount) {
        return nominalAmount * (1.0 - efficiencyLevel * 0.0099);
    }

    /**
     * 최대 방출량 계산.
     * = BASE_OUTPUT + current * OUTPUT_RATIO
     * 현재 주력량에 선형 비례. 속도·점프·대쉬 등 이펙트 강도는 호출부에서 log2 스케일.
     *
     * @param healthPercent 사용 안 함 (API 호환 유지)
     */
    public double getMaxOutput(double healthPercent) {
        return BASE_OUTPUT + Math.pow(current,0.5);
    }

    // ── 소모 / 회복 ───────────────────────────────────────────────────────

    /**
     * 주력 소모. 효율 적용 후 실제 소모량이 부족하면 false 반환.
     */
    public boolean consume(double amount) {
        double actual = applyEfficiency(amount);
        if (current < actual) return false;
        current = Math.max(0, current - actual);
        return true;
    }

    /**
     * 강제 소모 (부족해도 0까지 깎음). 효율 적용.
     */
    public void forceConsume(double amount) {
        current = Math.max(0, current - applyEfficiency(amount));
    }

    /**
     * 효율 미적용 강제 소모.
     * 피해로 인한 주력 감소 등 외부 CE 감소에 사용 (효율은 본인이 주력을 쓸 때만 적용).
     */
    public void rawForceConsume(double amount) {
        current = Math.max(0, current - amount);
    }

    /**
     * 틱당 자연 회복.
     * = BASE_REGEN + 0.0001 * max
     * current가 max를 초과할 수 없음.
     */
    public void regen() {
        current = Math.min(max, current + BASE_REGEN + Math.pow(max,0.3) + max/100000000);
    }

    // ── 충전 분산 ─────────────────────────────────────────────────────────

    /**
     * 동시에 충전 중인 스킬들에게 출력을 비례 분배.
     * 매 틱 JEntity.onTick() 에서 호출.
     *
     * totalRequested <= maxOutput : ratio = 1.0 (전부 충전)
     * totalRequested >  maxOutput : ratio = maxOutput / totalRequested (비례 감소)
     *
     * @param requests     충전 중인 스킬들의 요청 목록
     * @param healthPercent 현재 체력 비율 (0.0 ~ 1.0)
     */
    public void distributeOutput(List<ChargingRequest> requests, double healthPercent) {
        if (requests.isEmpty()) return;

        double maxOutput = getMaxOutput(healthPercent);
        double totalRequested = 0;
        for (ChargingRequest req : requests) {
            totalRequested += req.perTickRequest;
        }

        double ratio = (totalRequested > 0) ? Math.min(1.0, maxOutput / totalRequested) : 0;
        double actualTotal = 0;
        for (ChargingRequest req : requests) {
            req.actualCharged = req.perTickRequest * ratio;
            actualTotal += req.actualCharged;
        }
        forceConsume(actualTotal);
    }

    // ── 내부 수치 조정 (관리자 명령어 등) ───────────────────────────────────

    public void setMax(double max) {
        this.max = max;
        this.current = Math.min(current, max);
    }

    public void setCurrent(double current) {
        this.current = Math.max(0, Math.min(current, max));
    }

    public void fill() {
        this.current = this.max;
    }
}
