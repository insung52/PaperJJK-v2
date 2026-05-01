package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.World;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.network.JEntityManager;

import javax.annotation.Nullable;

/**
 * 영역전개(Domain Expansion) 추상 기반.
 * BarrierArts 를 상속. 결계 → 생득 영역 이전의 2단계 구조.
 *
 * isOpen = false : 일반 영역전개 (공간 분단 + 생득 영역 이전)
 * isOpen = true  : 결계가 없는 영역전개 (현실에 생득 영역 전개)
 *
 * 페이즈 흐름:
 *   EXPANDING → ACTIVE → CLOSING → DONE
 *   또는 EXPANDING/ACTIVE → CLASH → ACTIVE(승리) 또는 CLOSING(패배)
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

    /** EXPANDING → ACTIVE 전환 시 저장되는 현실 결계 중심 좌표 */
    protected Location barrierCenter;

    private static final double ENTRY_DETECTION_MARGIN = 1.5;

    public enum DomainPhase { EXPANDING, ACTIVE, CLASH, CLOSING, DONE }

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

    /** 전개 연출 틱 — 구 블록 점진 생성 등 */
    protected abstract void onExpanding();

    /** 필중 효과 적용 틱 — 생득 영역 InnateTerritory 위임 등 */
    protected abstract void onDomainActive();

    /**
     * 종료 연출 틱 — 블록 점진 복원 등.
     * 기본 구현: 즉시 DONE 전환 (블록 없는 결없영 등).
     * 블록 결계가 있는 구현체는 override 해 점진 복원 처리.
     */
    protected void onClosing() {
        domainPhase = DomainPhase.DONE;
    }

    /** 특정 위치가 이 영역의 결계 블록인지 확인 (기본: false, 구현체에서 override) */
    public boolean containsBarrierBlock(Location loc) {
        return false;
    }

    // ── BarrierArts override ──────────────────────────────────────────────

    @Override
    public void onTick() {
        switch (domainPhase) {
            case EXPANDING -> {
                onExpanding();
                updateInsideEntities();
            }
            case ACTIVE -> {
                if (!isOpen && barrierCenter != null) {
                    innateTerritory.refreshActiveEntities(barrierCenter);
                }
                onDomainActive();
                updateInsideEntities();
                if (!isOpen) checkNewEntrantsInBarrier();
            }
            case CLASH -> resolveClash();
            case CLOSING -> onClosing();
            case DONE -> {} // JPlayer 가 DONE 감지 후 finalizeCollapse() 처리
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

    /**
     * 방어자 측에서 호출 — CLASH 페이즈로 전환하고 상대를 등록.
     */
    public void startClash(DomainExpansion attacker) {
        this.clashOpponent = attacker;
        this.domainPhase = DomainPhase.CLASH;
    }

    /**
     * 공격자 측에서 호출 — CLASH 페이즈로 전환하고 상대를 등록.
     */
    public void startClashAsAttacker(DomainExpansion defender) {
        this.clashOpponent = defender;
        this.domainPhase = DomainPhase.CLASH;
    }

    /**
     * CLASH 틱 — 결계 레벨 차이로 양쪽 체력을 감소시키고 승패를 판정.
     *
     * 공식: diff = myLevel - opponentLevel
     *   내 체력 감소 = max(0.1, -diff * 0.5)   → 상대가 강할수록 더 빨리 깎임
     *   상대 체력은 상대의 resolveClash() 에서 처리 (자기 자신만 수정)
     */
    protected void resolveClash() {
        if (clashOpponent == null) {
            domainPhase = DomainPhase.ACTIVE;
            return;
        }

        double diff = barrierLevel - clashOpponent.barrierLevel;
        barrierHealth -= Math.max(0.1, -diff * 0.5);

        if (barrierHealth <= 0) {
            // 내가 패배
            DomainExpansion opponent = clashOpponent;
            clashOpponent = null;
            opponent.clashOpponent = null;
            opponent.onClashVictory();
            this.onClashDefeat();
        } else if (clashOpponent.barrierHealth <= 0) {
            // 상대가 패배
            DomainExpansion opponent = clashOpponent;
            clashOpponent = null;
            opponent.clashOpponent = null;
            this.onClashVictory();
            opponent.onClashDefeat();
        }
    }

    protected void onClashVictory() {
        domainPhase = DomainPhase.ACTIVE;
    }

    protected void onClashDefeat() {
        collapse();
    }

    // ── 엔티티 포획 ───────────────────────────────────────────────────────

    /**
     * 현재 반경 내 모든 엔티티(JEntity + 일반 LivingEntity)를 생득 영역으로 이전.
     * EXPANDING → ACTIVE 전환 시 한 번 호출.
     */
    protected void captureAllEntitiesInRange() {
        this.barrierCenter = caster.entity.getLocation().clone();
        Location center = this.barrierCenter;
        double r = getRange();
        World world = center.getWorld();
        if (world == null) return;

        // ── JEntity 포획 (시전자 포함) ──────────────────────────────────
        for (JEntity je : JEntityManager.instance.all()) {
            if (!world.equals(je.entity.getWorld())) continue;
            // 천여주박(PhysicalGifted)은 포획 제외
            if (je.technique != null && !je.technique.isDomainTarget()) continue;
            if (je == caster || je.entity.getLocation().distance(center) <= r) {
                innateTerritory.captureEntity(je, center, r);
            }
        }

        // ── 일반 엔티티 포획 (결없영은 포획 없이 현실 전개) ─────────────
        if (!isOpen) {
            innateTerritory.captureVanillaEntitiesInRange(center, r, world);
        }
    }

    /**
     * 현실 결계 내부에 있는 JEntity가 감지되면 생득 영역으로 이전한다.
     * 바닐라 엔티티 침입은 refreshActiveEntities() 스캔이 담당하므로 여기서는 처리하지 않는다.
     */
    private void checkNewEntrantsInBarrier() {
        if (barrierCenter == null) return;
        World world = barrierCenter.getWorld();
        if (world == null) return;
        double detectionRadius = range - ENTRY_DETECTION_MARGIN;

        for (JEntity je : JEntityManager.instance.all()) {
            if (!world.equals(je.entity.getWorld())) continue;
            if (je == caster) continue;
            if (je.technique != null && !je.technique.isDomainTarget()) continue;
            if (innateTerritory.isCaptured(je)) continue;
            if (je.entity.getLocation().distance(barrierCenter) <= detectionRadius) {
                innateTerritory.captureEntity(je, barrierCenter, range);
            }
        }
    }

    // ── 출입 조건 ─────────────────────────────────────────────────────────

    @Override
    public boolean canEnter(JEntity entity) {
        // 천여주박(PhysicalGifted)은 결계 자유 통과
        return entity.technique != null && !entity.technique.isDomainTarget();
    }

    @Override
    public void onEnter(JEntity entity) {
        // 결계 ACTIVE 상태에서 새로 진입하면 생득 영역으로 이전
        if (!isOpen && domainPhase == DomainPhase.ACTIVE) {
            if (entity.technique != null && !entity.technique.isDomainTarget()) return;
            innateTerritory.captureEntity(entity);
        }
    }

    @Override
    public void onExit(JEntity entity) {}

    // ── 붕괴 ──────────────────────────────────────────────────────────────

    /**
     * 영역전개 붕괴를 시작한다. CLOSING 페이즈로 전환하고 포획된 엔티티를 귀환시킴.
     * 이미 CLOSING 또는 DONE 이면 멱등(idempotent).
     */
    @Override
    public void collapse() {
        if (domainPhase == DomainPhase.CLOSING || domainPhase == DomainPhase.DONE) return;
        domainPhase = DomainPhase.CLOSING;
        innateTerritory.releaseAll(caster.entity.getLocation());
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public boolean isOpen() { return isOpen; }
    public DomainPhase getDomainPhase() { return domainPhase; }
    public double getBarrierHealth() { return barrierHealth; }
    public double getBarrierHealthPercent() { return barrierHealth / barrierMaxHealth; }
}
