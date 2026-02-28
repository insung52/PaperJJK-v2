package org.justheare.paperjjk.innate;

import org.bukkit.Location;
import org.justheare.paperjjk.entity.JEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 생득 영역(Innate Territory) 추상 기반.
 * 위치는 jjk id build 명령어로 사전 설정.
 * DomainExpansion 이 captureEntity() 를 호출해 대상을 이동시킴.
 */
public abstract class InnateTerritory {

    protected final JEntity owner;
    protected Location centerLocation;  // jjk id build 로 설정된 좌표
    protected boolean isReady = false;

    protected final List<JEntity> capturedEntities = new ArrayList<>();

    protected InnateTerritory(JEntity owner) {
        this.owner = owner;
    }

    // ── 위치 설정 (jjk id build) ──────────────────────────────────────────

    public void setLocation(Location location) {
        this.centerLocation = location;
        this.isReady = true;
    }

    public boolean isReady() { return isReady; }
    public Location getCenterLocation() { return centerLocation; }

    // ── 대상 이동 ─────────────────────────────────────────────────────────

    /** DomainExpansion 이 결계 안 대상들에게 호출 */
    public void captureEntity(JEntity entity) {
        if (!isReady) return;
        capturedEntities.add(entity);
        entity.entity.teleport(centerLocation);
    }

    /** 영역전개 종료 시 전원 귀환 */
    public void releaseAll(Location returnLocation) {
        for (JEntity entity : capturedEntities) {
            entity.entity.teleport(returnLocation);
        }
        capturedEntities.clear();
    }

    // ── 술식별 구현 ───────────────────────────────────────────────────────

    /** 매 틱 처리 — 필중 효과, 시전자 강화 */
    public abstract void onActiveTick();

    /** 시전자 강화 효과 */
    public abstract void applyCasterBuff(JEntity caster);

    /** 대상 필중 효과 */
    public abstract void applySureHit(JEntity target);
}
