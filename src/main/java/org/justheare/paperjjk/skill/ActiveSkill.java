package org.justheare.paperjjk.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.network.PacketIds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 실행 중인 술식(스킬)의 추상 기반.
 *
 * CE 충전 흐름:
 *   키 PRESS  → new XxxSkill() + WorkScheduler.register()
 *   충전 중   → JEntity.drainAndDistribute() 가 매 틱 chargeBuffer 에 CE 적립
 *   키 RELEASE → stopCharging() → onCharged() (chargeBuffer 확정) → phase = ACTIVE
 *   발동 중   → onActiveTick() 매 틱 호출
 *   종료      → end() → onEnd()
 *
 * 스킬 파워 = chargeBuffer × efficiency  (onCharged() 에서 확정)
 */
public abstract class ActiveSkill implements SkillExecution {

    protected final JEntity caster;

    protected SkillPhase phase = SkillPhase.CHARGING;

    // ── CE 버퍼 ───────────────────────────────────────────────────────────

    /** 현재 충전 세션에서 누적된 CE 량 */
    protected double chargeBuffer = 0;

    /**
     * 분배 가중치. 기본 1.0.
     * Aka 는 2.0 → 같은 maxOutput 에서 Ao 의 2배 CE 수신.
     */
    protected double chargeWeight = 1.0;

    /**
     * chargeBuffer 상한. 기본 무제한(Double.MAX_VALUE).
     * 연속 스킬(Passive, Hachi)은 생성자에서 적절한 값으로 설정.
     */
    protected double chargeBufferMax = Double.MAX_VALUE;

    /** 재충전 중 여부 (ACTIVE → CHARGING 전환 시 true) */
    private boolean recharging = false;

    private static final int PRIORITY_NORMAL = 1;

    /** Phase 2 블록 파괴 큐 */
    protected final List<Location> pendingBreaks = new ArrayList<>();

    public ActiveSkill(JEntity caster) {
        this.caster = caster;
    }

    // ── 생명주기 override 지점 ────────────────────────────────────────────

    protected void onChargingTick() {}
    protected void onCharged() {}
    protected void onActiveTick() {}
    protected void onEnd() {}

    // ── CE 버퍼 인터페이스 (JEntity.drainAndDistribute 에서 사용) ──────────

    public double getChargeWeight()     { return chargeWeight; }
    public double getChargeBuffer()     { return chargeBuffer; }
    public double getChargeBufferSpace(){ return chargeBufferMax - chargeBuffer; }

    /** 분배된 CE 를 버퍼에 적립. CHARGING 상태일 때만 유효. */
    public void addToBuffer(double amount) {
        if (phase == SkillPhase.CHARGING) {
            chargeBuffer = Math.min(chargeBufferMax, chargeBuffer + amount);
        }
    }

    // ── 표준 전환 (final) ─────────────────────────────────────────────────

    /**
     * 키 RELEASE 시 호출.
     * chargeBuffer 확정 → onCharged() → chargeBuffer 초기화.
     */
    public final void stopCharging() {
        if (phase != SkillPhase.CHARGING) return;
        phase = SkillPhase.ACTIVE;
        recharging = false;
        onCharged();
        chargeBuffer = 0;
    }

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
            case ENDED    -> {}
        }
    }

    @Override
    public final boolean isDone() { return phase == SkillPhase.ENDED; }

    @Override
    public int getPriority() { return PRIORITY_NORMAL; }

    // ── 재충전 ────────────────────────────────────────────────────────────

    public void startRecharging() {
        if (phase == SkillPhase.ACTIVE) {
            phase = SkillPhase.CHARGING;
            recharging = true;
        }
    }

    // ── 블록 파괴 큐 ──────────────────────────────────────────────────────

    protected void queueBreak(Location loc) {
        pendingBreaks.add(loc);
    }

    @Override
    public int flushBlocks(int budget) {
        if (pendingBreaks.isEmpty()) return 0;
        int consumed = 0;
        Iterator<Location> it = pendingBreaks.iterator();
        while (it.hasNext() && consumed < budget) {
            Location loc = it.next();
            it.remove();
            if (loc.getWorld() != null
                    && loc.getChunk().isLoaded()
                    && !loc.getBlock().isEmpty()) {
                loc.getBlock().setType(Material.AIR);
            }
            consumed++;
        }
        return consumed;
    }

    // ── 제어 (스킬이 지원하는 경우 override) ──────────────────────────────

    public void onScrollDistance(int delta) {}
    public void onControl() {}
    public boolean isLocked() { return false; }
    public String getSlotLabel() { return ""; }

    /**
     * 시전자가 물리 공격(직접 타격)으로 LivingEntity 를 실제로 맞혔을 때 호출.
     * 공격을 트리거로 사용하는 스킬은 이 메서드를 override 한다.
     * 호출 시점: DamagePipeline Phase 3 성공(JEntity 대상) 또는
     *            JEvent.onEntityDamageByEntity onAttackMob(일반 몹 대상) 직후.
     */
    public void onAttackLanded(LivingEntity target) {}

    // ── HUD 게이지 ────────────────────────────────────────────────────────

    public float getGaugePercent() { return 0f; }

    public byte getSlotGaugeState() {
        return switch (phase) {
            case CHARGING -> recharging ? PacketIds.SlotGaugeState.RECHARGING : PacketIds.SlotGaugeState.CHARGING;
            case ACTIVE   -> PacketIds.SlotGaugeState.ACTIVE;
            case ENDED    -> PacketIds.SlotGaugeState.NONE;
        };
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public SkillPhase getPhase() { return phase; }
    public JEntity getCaster()   { return caster; }
    public boolean isCharging()  { return phase == SkillPhase.CHARGING; }
    public boolean isActive()    { return phase == SkillPhase.ACTIVE; }
}
