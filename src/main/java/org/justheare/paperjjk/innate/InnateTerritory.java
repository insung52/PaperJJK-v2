package org.justheare.paperjjk.innate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.barrier.DomainBlockBuilder;
import org.justheare.paperjjk.entity.JEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 생득 영역(Innate Territory) 추상 기반.
 * 위치는 jjk id build 명령어로 사전 설정.
 * DomainExpansion 이 captureEntity() 를 호출해 대상을 이동시킴.
 */
public abstract class InnateTerritory {

    protected final JEntity owner;
    protected Location centerLocation;  // jjk id build 로 설정된 좌표
    protected boolean isReady = false;

    /** 생득 영역 내부 반경 (기본 30블록) */
    protected double innerRadius = 30.0;

    protected final List<JEntity> capturedEntities = new ArrayList<>();

    /** 포획 전 원래 위치 (귀환 시 사용) */
    protected final Map<UUID, Location> originalLocations = new HashMap<>();

    /** 생득 영역 결계 블록 관리 (jjk id build 시 배치, jjk id destroy 시 복원) */
    private DomainBlockBuilder innateBuilder;

    /** 점진적 배치/복원 스케줄러 태스크 ID (-1 = 없음) */
    private int innateTaskId = -1;

    private static final int INNATE_BLOCKS_PER_TICK = 200;

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
    public double getInnerRadius() { return innerRadius; }

    // ── 생득 영역 결계 블록 ────────────────────────────────────────────────

    /**
     * 생득 영역 중심 주변에 결계 블록을 점진적으로 배치한다 (200블록/틱).
     * jjk id build 실행 시 호출.
     * 기존 결계·스케줄러가 있으면 먼저 취소 후 즉시 복원한 뒤 새로 배치.
     *
     * @param material 결계 블록 재질 (BARRIER = Infinity, BEDROCK = Mizushi)
     * @param radius   결계 반경 (블록)
     */
    public void setupInnateBarrier(Material material, int radius) {
        if (!isReady || centerLocation == null) return;

        // 진행 중인 스케줄러 취소
        cancelInnateTask();

        // 기존 결계 즉시 복원 (이전 빌더가 있을 경우)
        if (innateBuilder != null) {
            innateBuilder.restoreTick(Integer.MAX_VALUE);
        }

        innateBuilder = new DomainBlockBuilder();
        Location loc = centerLocation.clone();

        // 200블록/틱 점진 배치
        innateTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                PaperJJK.instance,
                () -> {
                    boolean done = innateBuilder.buildTick(loc, radius, material, INNATE_BLOCKS_PER_TICK);
                    if (done) cancelInnateTask();
                },
                0L, 1L);
    }

    /**
     * 생득 영역 결계 블록을 점진적으로 복원한다 (200블록/틱).
     * jjk id destroy 또는 위치 재설정 시 호출.
     */
    public void removeInnateBarrier() {
        if (innateBuilder == null) return;

        // 진행 중인 빌드 스케줄러 취소
        cancelInnateTask();

        DomainBlockBuilder toRestore = innateBuilder;
        innateBuilder = null;

        // 200블록/틱 점진 복원
        innateTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                PaperJJK.instance,
                () -> {
                    boolean done = toRestore.restoreTick(INNATE_BLOCKS_PER_TICK);
                    if (done) cancelInnateTask();
                },
                0L, 1L);
    }

    private void cancelInnateTask() {
        if (innateTaskId != -1) {
            Bukkit.getScheduler().cancelTask(innateTaskId);
            innateTaskId = -1;
        }
    }

    // ── 대상 이동 ─────────────────────────────────────────────────────────

    /**
     * 결계 내 상대 위치를 생득 영역에 비율로 매핑해 이전.
     * barrierCenter/barrierRadius 를 이용해 위치 보존.
     */
    public void captureEntity(JEntity entity, Location barrierCenter, double barrierRadius) {
        if (!isReady) return;
        originalLocations.put(entity.uuid, entity.entity.getLocation().clone());
        capturedEntities.add(entity);

        Location entityLoc = entity.entity.getLocation();
        double offX = entityLoc.getX() - barrierCenter.getX();
        double offY = entityLoc.getY() - barrierCenter.getY();
        double offZ = entityLoc.getZ() - barrierCenter.getZ();

        double scale = innerRadius / barrierRadius;
        Location dest = centerLocation.clone().add(offX * scale, offY * scale, offZ * scale);
        entity.entity.teleport(dest);
    }

    /**
     * 단순 포획 (위치 보존 없이 생득 영역 중심으로 이전).
     * 주로 영역 ACTIVE 중 새로 진입한 엔티티 처리.
     */
    public void captureEntity(JEntity entity) {
        if (!isReady) return;
        originalLocations.put(entity.uuid, entity.entity.getLocation().clone());
        capturedEntities.add(entity);
        entity.entity.teleport(centerLocation);
    }

    /** 영역전개 종료 시 포획된 전원을 원래 위치로 귀환 */
    public void releaseAll(Location fallback) {
        for (JEntity entity : capturedEntities) {
            Location origin = originalLocations.getOrDefault(entity.uuid, fallback);
            entity.entity.teleport(origin);
        }
        capturedEntities.clear();
        originalLocations.clear();
    }

    // ── 술식별 구현 ───────────────────────────────────────────────────────

    /** 매 틱 처리 — 필중 효과, 시전자 강화 */
    public abstract void onActiveTick();

    /** 시전자 강화 효과 */
    public abstract void applyCasterBuff(JEntity caster);

    /** 대상 필중 효과 */
    public abstract void applySureHit(JEntity target);
}
