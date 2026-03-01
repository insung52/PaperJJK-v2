package org.justheare.paperjjk.skill;

import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.network.PacketIds;

/**
 * 실행 중인 술식(스킬)의 추상 기반.
 * 기존 Jujut 를 대체. 충전 → 발동 → 종료 생명주기 표준화.
 *
 * 생명주기:
 *   키 PRESS  → new XxxSkill() + WorkScheduler.register()
 *   충전 중   → onChargingTick() 매 틱 호출, CursedEnergy 에서 출력 배정
 *   키 RELEASE → stopCharging() → phase = ACTIVE → onCharged()
 *   발동 중   → onActiveTick() 매 틱 호출
 *   종료 조건 → end() → phase = ENDED → onEnd()
 */
public abstract class ActiveSkill implements SkillExecution {

    protected final JEntity caster;

    protected SkillPhase phase = SkillPhase.CHARGING;

    /** 충전 완료 시 확정된 주력 출력량 (stopCharging() 시점에 고정, 이후 불변) */
    protected double chargedOutput = 0;

    /** 매 틱 CursedEnergy 에 요청하는 출력량 (ChargingRequest.perTickRequest) */
    protected double perTickChargeRequest;

    /** 현재까지 누적된 충전량 (충전 중에 distributeOutput 결과로 증가) */
    private double accumulatedCharge = 0;

    /** 재충전 중 여부 (발동 후 재홀드 → true, stopCharging() 시 false) */
    private boolean recharging = false;

    private static final int PRIORITY_NORMAL = 1;

    public ActiveSkill(JEntity caster, double perTickChargeRequest) {
        this.caster = caster;
        this.perTickChargeRequest = perTickChargeRequest;
    }

    // ── 생명주기 override 지점 ────────────────────────────────────────────

    /** 충전 중 매 틱 — 파티클, 사운드 등 충전 이펙트 */
    protected void onChargingTick() {}

    /** 충전 완료 (키에서 손 뗐을 때) — 스킬 발동 초기화 */
    protected void onCharged() {}

    /** 발동 중 매 틱 — 스킬 실제 동작 */
    protected void onActiveTick() {}

    /** 종료 시 정리 — 리소스 해제, 이펙트 제거 등 */
    protected void onEnd() {}

    // ── 표준 전환 (final) ─────────────────────────────────────────────────

    /**
     * 키 RELEASE 시 호출.
     * CHARGING → ACTIVE 전환, chargedOutput 확정.
     */
    public final void stopCharging() {
        if (phase != SkillPhase.CHARGING) return;
        chargedOutput = accumulatedCharge;
        phase = SkillPhase.ACTIVE;
        recharging = false;
        onCharged();
    }

    /**
     * 종료 조건 달성 시 호출 (스킬 내부 또는 외부에서).
     * → ENDED, onEnd() 호출.
     */
    public final void end() {
        if (phase == SkillPhase.ENDED) return;
        phase = SkillPhase.ENDED;
        onEnd();
    }

    // ── SkillExecution 구현 ───────────────────────────────────────────────

    @Override
    public final void tick() {
        switch (phase) {
            case CHARGING -> onChargingTick();
            case ACTIVE   -> onActiveTick();
            case ENDED    -> {} // WorkScheduler 가 다음 틱에 제거
        }
    }

    @Override
    public final boolean isDone() {
        return phase == SkillPhase.ENDED;
    }

    @Override
    public int getPriority() {
        return PRIORITY_NORMAL;
    }

    // ── 충전량 수신 (JEntity.onTick() 에서 호출) ─────────────────────────

    /**
     * distributeOutput() 결과로 배정된 실제 충전량을 적용.
     * 충전 단계에서만 동작.
     */
    public void applyCharge(double amount) {
        if (phase == SkillPhase.CHARGING) {
            accumulatedCharge += amount;
        }
    }

    // ── 재충전 (rechargeable 스킬) ───────────────────────────────────────

    /**
     * 발동 중인 스킬 재충전 시작 (SkillKeyMap 이 호출).
     * rechargeable=true 이고 ACTIVE 상태일 때만 유효.
     */
    public void startRecharging() {
        if (phase == SkillPhase.ACTIVE) {
            accumulatedCharge = 0;
            phase = SkillPhase.CHARGING;
            recharging = true;
        }
    }

    // ── 제어 (스킬이 지원하는 경우 override) ──────────────────────────────

    /**
     * SKILL_DISTANCE (스크롤) 패킷 수신 시 호출.
     * 지원하는 스킬 (InfinityAo 등) 에서 override 하여 distance 조정.
     */
    public void onScrollDistance(int delta) {}

    /**
     * SKILL_CONTROL (T 키) 패킷 수신 시 호출.
     * 지원하는 스킬에서 override — 예: InfinityAo 의 시선 고정 토글.
     */
    public void onControl() {}

    /**
     * 슬롯 위에 자물쇠 아이콘을 표시할지 여부.
     * SKILL_CONTROL 로 고정 상태가 된 스킬 (InfinityAo fixed=true 등) 에서 override.
     */
    public boolean isLocked() { return false; }

    /**
     * 슬롯 위에 표시할 짧은 레이블 (빈 문자열 = 표시 없음).
     * 거리 조정 스킬: "3m", "50m" / 방향 토글 스킬: "↑", "↓"
     */
    public String getSlotLabel() { return ""; }

    // ── HUD 게이지 (클라이언트 전송용) ───────────────────────────────────

    /**
     * 슬롯 게이지 퍼센트 (0.0~1.0). SLOT_GAUGE_UPDATE 패킷에 사용.
     *
     * 기본값: CE 누적량 기반 (퍼틱 요청량 × 40틱 = 100%).
     * 각 스킬은 override 하여 자신의 실제 파워 비율을 반환해야 함.
     * 예) InfinityAo: CHARGING → chargeDurationTicks / 100, ACTIVE → remainingPower / 100
     */
    public float getGaugePercent() {
        return switch (phase) {
            case CHARGING -> (float) Math.min(1.0, accumulatedCharge / Math.max(1e-9, perTickChargeRequest * 40));
            case ACTIVE   -> (float) Math.min(1.0, chargedOutput    / Math.max(1e-9, perTickChargeRequest * 40));
            case ENDED    -> 0.0f;
        };
    }

    /**
     * 슬롯 게이지 상태 바이트. SLOT_GAUGE_UPDATE 패킷에 사용.
     */
    public byte getSlotGaugeState() {
        return switch (phase) {
            case CHARGING -> recharging ? PacketIds.SlotGaugeState.RECHARGING : PacketIds.SlotGaugeState.CHARGING;
            case ACTIVE   -> PacketIds.SlotGaugeState.ACTIVE;
            case ENDED    -> PacketIds.SlotGaugeState.NONE;
        };
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public SkillPhase getPhase() { return phase; }
    public double getChargedOutput() { return chargedOutput; }
    public double getAccumulatedCharge() { return accumulatedCharge; }
    public double getPerTickChargeRequest() { return perTickChargeRequest; }
    public JEntity getCaster() { return caster; }
    public boolean isCharging() { return phase == SkillPhase.CHARGING; }
    public boolean isActive() { return phase == SkillPhase.ACTIVE; }
}
