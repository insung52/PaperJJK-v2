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
    private static final double BASE_REGEN = 1.0;

    // 최대 방출량 스케일 상수 — 튜닝 필요
    // getMaxOutput() 공식: log2(current + 1) * OUTPUT_SCALE * healthPercent
    private static final double OUTPUT_SCALE = 10.0;

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

    /**
     * 최대 방출량 계산.
     * = log2(current + 1) * OUTPUT_SCALE * healthPercent
     * 체력이 낮을수록 충전 속도가 크게 떨어짐.
     *
     * @param healthPercent 현재 체력 비율 (0.0 ~ 1.0)
     */
    public double getMaxOutput(double healthPercent) {
        double base = Math.log(current + 1) / Math.log(2) * OUTPUT_SCALE;
        return base * Math.max(0, healthPercent);
    }

    // ── 소모 / 회복 ───────────────────────────────────────────────────────

    /**
     * 주력 소모. 부족하면 false 반환.
     */
    public boolean consume(double amount) {
        if (current < amount) return false;
        current = Math.max(0, current - amount);
        return true;
    }

    /**
     * 강제 소모 (부족해도 0까지 깎음).
     */
    public void forceConsume(double amount) {
        current = Math.max(0, current - amount);
    }

    /**
     * 틱당 자연 회복.
     * = BASE_REGEN + 0.0001 * max
     * current가 max를 초과할 수 없음.
     */
    public void regen() {
        current = Math.min(max, current + BASE_REGEN + 0.0001 * max);
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
        for (ChargingRequest req : requests) {
            req.actualCharged = req.perTickRequest * ratio;
        }
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
