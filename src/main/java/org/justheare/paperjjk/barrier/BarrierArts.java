package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.justheare.paperjjk.entity.JEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 결계술 전체의 공통 추상 기반.
 * 공통 속성: 시전자, 반경, 출입 조건, 내부 엔티티 목록.
 *
 * 구현체:
 *   Curtain         — 장막
 *   CounterBarrier  — 필중 방어 계열 (간이영역, 미허갈롱, 영역전연)
 *   DomainExpansion — 영역전개
 */
public abstract class BarrierArts {

    protected final JEntity caster;
    protected double range;
    protected final List<JEntity> entitiesInside = new ArrayList<>();

    protected BarrierArts(JEntity caster, double range) {
        this.caster = caster;
        this.range = range;
    }

    // ── 추상 메서드 ───────────────────────────────────────────────────────

    /** 출입 조건 (true = 통과 가능) */
    public abstract boolean canEnter(JEntity entity);

    public abstract void onEnter(JEntity entity);
    public abstract void onExit(JEntity entity);

    /** 매 틱 처리 */
    public abstract void onTick();

    /** 붕괴/종료 */
    public abstract void collapse();

    // ── 내부 엔티티 갱신 ──────────────────────────────────────────────────

    /** 매 틱 호출 — 범위 안팎 진입/이탈 감지 */
    protected void updateInsideEntities() {
        Location center = caster.entity.getLocation();

        // 이탈 감지
        entitiesInside.removeIf(entity -> {
            if (entity.entity.getLocation().distance(center) > range) {
                onExit(entity);
                return true;
            }
            return false;
        });

        // 진입 감지 (주변 엔티티 검색은 구현체에서 필요 시 override)
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public double getRange() { return range; }
    public void setRange(double range) { this.range = range; }
    public List<JEntity> getEntitiesInside() { return entitiesInside; }
    public JEntity getCaster() { return caster; }
}
