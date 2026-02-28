package org.justheare.paperjjk.barrier;

import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;

import javax.annotation.Nullable;

/**
 * 영역전개(Domain Expansion) 추상 기반.
 * BarrierArts 를 상속. 결계 → 생득 영역 이전의 2단계 구조.
 *
 * isOpen = false : 일반 영역전개 (공간 분단 + 생득 영역 이전)
 * isOpen = true  : 결계가 없는 영역전개 (현실에 생득 영역 전개)
 */
public abstract class DomainExpansion extends BarrierArts {

    protected final InnateTerritory innateTerritory;
    protected DomainPhase domainPhase = DomainPhase.EXPANDING;

    /** 결계가 없는 영역전개 여부 */
    protected final boolean isOpen;

    /** 결계 내구도 (isOpen=false 일 때만 사용) */
    protected double barrierHealth;
    protected double barrierMaxHealth;

    /** 결계술 레벨 — 밀어내기 싸움 승패에 영향 */
    protected double barrierLevel;

    /** 밀어내기 싸움 상대 */
    @Nullable
    protected DomainExpansion clashOpponent;

    public enum DomainPhase { EXPANDING, ACTIVE, CLASH, CLOSING }

    protected DomainExpansion(JEntity caster, InnateTerritory territory,
                               double range, boolean isOpen, double barrierLevel) {
        super(caster, range);
        this.innateTerritory = territory;
        this.isOpen = isOpen;
        this.barrierLevel = barrierLevel;
        this.barrierMaxHealth = range * 100;
        this.barrierHealth = barrierMaxHealth;
    }

    // ── 술식별 구현 ───────────────────────────────────────────────────────

    /** 전개 연출 틱 (구 생성 등) */
    protected abstract void onExpanding();

    /** 필중 효과 적용 틱 (술식마다 완전히 다름) */
    protected abstract void onDomainActive();

    // ── BarrierArts override ──────────────────────────────────────────────

    @Override
    public void onTick() {
        switch (domainPhase) {
            case EXPANDING -> {
                onExpanding();
                updateInsideEntities();
            }
            case ACTIVE -> {
                onDomainActive();
                updateInsideEntities();
            }
            case CLASH -> resolveClash();
            case CLOSING -> collapse();
        }
    }

    // ── 결계 피격 ─────────────────────────────────────────────────────────

    public void onBarrierDamaged(double damage) {
        if (isOpen) return;
        barrierHealth = Math.max(0, barrierHealth - damage);
        // 30% 이상 파손 시 강제 종료
        if (barrierHealth < barrierMaxHealth * 0.3) {
            collapse();
        }
    }

    // ── 밀어내기 싸움 ─────────────────────────────────────────────────────

    public void startClash(DomainExpansion other) {
        this.clashOpponent = other;
        this.domainPhase = DomainPhase.CLASH;
    }

    protected void resolveClash() {
        if (clashOpponent == null) {
            domainPhase = DomainPhase.ACTIVE;
            return;
        }
        // barrierLevel 차이로 승패 결정 — 구체적 공식은 추후 확정
        // 임시: 매 틱 1씩 체력 감소, 먼저 0 되면 패배
        double myPower = barrierLevel;
        double opponentPower = clashOpponent.barrierLevel;
        double diff = myPower - opponentPower;
        clashOpponent.barrierHealth -= Math.max(0.1, diff);
        barrierHealth -= Math.max(0.1, -diff);
    }

    // ── 출입 조건 ─────────────────────────────────────────────────────────

    @Override
    public boolean canEnter(JEntity entity) {
        // 천여주박(PhysicalGifted)은 결계 자유 통과
        if (entity.technique != null && !entity.technique.isDomainTarget()) return true;
        return false;
    }

    @Override
    public void onEnter(JEntity entity) {
        // 결계 안으로 들어온 경우 생득 영역으로 이전
        if (!isOpen && domainPhase == DomainPhase.ACTIVE) {
            innateTerritory.captureEntity(entity);
        }
    }

    @Override
    public void onExit(JEntity entity) {}

    @Override
    public void collapse() {
        domainPhase = DomainPhase.CLOSING;
        innateTerritory.releaseAll(caster.entity.getLocation());
        // 영역전개 종료 → 술식 타버림 처리는 JPlayer 에서
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public boolean isOpen() { return isOpen; }
    public DomainPhase getDomainPhase() { return domainPhase; }
    public double getBarrierHealth() { return barrierHealth; }
    public double getBarrierHealthPercent() { return barrierHealth / barrierMaxHealth; }
}
