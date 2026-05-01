package org.justheare.paperjjk.innate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.barrier.DomainBlockBuilder;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.network.JEntityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 생득 영역(Innate Territory) 추상 기반.
 * 위치는 jjk id build 명령어로 사전 설정.
 * DomainExpansion 이 captureEntity() 를 호출해 대상을 이동시킴.
 *
 * 엔티티 추적 방식:
 *   - originalLocations: 포획 시점의 현실 좌표 (귀환용). 사망·해제까지 유지.
 *   - capturedEntities / capturedVanillaEntities: 매 틱 refreshActiveEntities() 로
 *     생득 영역 내부를 스캔해 재구성. 새로 소환된 엔티티도 자동 포함.
 */
public abstract class InnateTerritory {

    protected final JEntity owner;
    protected Location centerLocation;
    protected boolean isReady = false;

    /** 생득 영역 내부 반경 (기본 30블록) */
    protected double innerRadius = 30.0;

    /** 현재 생득 영역 내 JEntity 목록 (매 틱 refreshActiveEntities 로 재구성) */
    protected final List<JEntity> capturedEntities = new ArrayList<>();

    /** 현재 생득 영역 내 일반 LivingEntity 목록 (매 틱 refreshActiveEntities 로 재구성) */
    protected final List<Entity> capturedVanillaEntities = new ArrayList<>();

    /** 포획 전 원래 위치 (귀환 시 사용). setnodamagetick 이 처음 감지된 좌표로 저장. */
    protected final Map<UUID, Location> originalLocations = new HashMap<>();
    protected final Map<UUID, Location> vanillaOriginLocations = new HashMap<>();

    /** 생득 영역 결계 블록 관리 (jjk id build 시 배치, jjk id destroy 시 복원) */
    private DomainBlockBuilder innateBuilder;

    /** 점진적 배치/복원 스케줄러 태스크 ID (-1 = 없음) */
    private int innateTaskId = -1;

    private static final int INNATE_BLOCKS_PER_TICK = 200;

    /**
     * 바닐라 엔티티 스캔 주기 (5틱 = 0.25초).
     * JEntity 스캔은 매 틱(O(플레이어 수)로 저렴).
     * getNearbyEntities() 는 공간 쿼리라 더 무거우므로 간격을 둔다.
     */
    private static final int VANILLA_REFRESH_INTERVAL = 5;
    private int vanillaRefreshCounter = 0;

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
     */
    public void setupInnateBarrier(Material material, int radius) {
        if (!isReady || centerLocation == null) return;
        cancelInnateTask();
        if (innateBuilder != null) {
            innateBuilder.restoreTick(Integer.MAX_VALUE);
        }
        innateBuilder = new DomainBlockBuilder();
        Location loc = centerLocation.clone();
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
     */
    public void removeInnateBarrier() {
        if (innateBuilder == null) return;
        cancelInnateTask();
        DomainBlockBuilder toRestore = innateBuilder;
        innateBuilder = null;
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

    // ── 대상 이동 (현실 → 생득 영역) ──────────────────────────────────────

    /**
     * 결계 내 상대 위치를 생득 영역에 비율로 매핑해 이전.
     * EXPANDING → ACTIVE 초기 포획 및 침입자 감지 시 호출.
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
        entity.entity.teleport(centerLocation.clone().add(offX * scale, offY * scale, offZ * scale));
    }

    /**
     * 단순 포획 (위치 보존 없이 생득 영역 중심으로 이전).
     */
    public void captureEntity(JEntity entity) {
        if (!isReady) return;
        originalLocations.put(entity.uuid, entity.entity.getLocation().clone());
        capturedEntities.add(entity);
        entity.entity.teleport(centerLocation);
    }

    public boolean isCaptured(JEntity entity) {
        return capturedEntities.contains(entity);
    }

    /**
     * 현실 결계 내 바닐라 LivingEntity를 생득 영역으로 이전.
     * EXPANDING → ACTIVE 전환 시 한 번 호출.
     */
    public void captureVanillaEntitiesInRange(Location center, double r, World world) {
        if (!isReady) return;
        double scale = innerRadius / r;
        for (Entity e : world.getNearbyEntities(center, r, r, r)) {
            if (e instanceof Player) continue;
            if (JEntityManager.instance.get(e.getUniqueId()) != null) continue;
            if (!(e instanceof LivingEntity)) continue;
            Location entityLoc = e.getLocation();
            vanillaOriginLocations.put(e.getUniqueId(), entityLoc.clone());
            capturedVanillaEntities.add(e);
            double offX = entityLoc.getX() - center.getX();
            double offY = entityLoc.getY() - center.getY();
            double offZ = entityLoc.getZ() - center.getZ();
            e.teleport(centerLocation.clone().add(offX * scale, offY * scale, offZ * scale));
        }
    }

    /**
     * 생득 영역 내부를 스캔해 추적 목록을 최신화한다.
     * 새로 소환된 엔티티도 자동 포함되며, 사망·이탈한 엔티티는 목록에서 제거된다.
     * 새로 발견된 엔티티는 barrierCenterFallback을 귀환 좌표로 저장.
     *
     * JEntity  : 매 틱 스캔 (JEntityManager 순회, O(플레이어 수)로 저렴)
     * 바닐라   : VANILLA_REFRESH_INTERVAL 틱마다 스캔 (getNearbyEntities 공간 쿼리)
     */
    public void refreshActiveEntities(Location barrierCenterFallback) {
        if (centerLocation == null || !isReady) return;
        World world = centerLocation.getWorld();
        if (world == null) return;

        // ── JEntity: 매 틱 재구성 ─────────────────────────────────────────
        capturedEntities.clear();
        for (JEntity je : JEntityManager.instance.all()) {
            if (!world.equals(je.entity.getWorld())) continue;
            if (je.entity.getLocation().distance(centerLocation) > innerRadius) continue;
            capturedEntities.add(je);
            originalLocations.computeIfAbsent(je.uuid, k -> barrierCenterFallback.clone());
        }

        // ── 바닐라 엔티티: 5틱 간격 재구성 ──────────────────────────────
        if (vanillaRefreshCounter++ % VANILLA_REFRESH_INTERVAL != 0) return;
        capturedVanillaEntities.clear();
        for (Entity e : world.getNearbyEntities(centerLocation, innerRadius, innerRadius, innerRadius)) {
            if (e instanceof Player) continue;
            if (JEntityManager.instance.get(e.getUniqueId()) != null) continue;
            if (!(e instanceof LivingEntity)) continue;
            if (e.getLocation().distance(centerLocation) > innerRadius) continue;
            capturedVanillaEntities.add(e);
            vanillaOriginLocations.computeIfAbsent(e.getUniqueId(), k -> barrierCenterFallback.clone());
        }
    }

    /** 영역전개 종료 시 포획된 전원을 원래 위치로 귀환 */
    public void releaseAll(Location fallback) {
        for (JEntity entity : capturedEntities) {
            Location origin = originalLocations.getOrDefault(entity.uuid, fallback);
            entity.entity.teleport(origin);
        }
        capturedEntities.clear();
        originalLocations.clear();

        for (Entity e : capturedVanillaEntities) {
            if (e.isValid()) {
                Location origin = vanillaOriginLocations.getOrDefault(e.getUniqueId(), fallback);
                e.teleport(origin);
            }
        }
        capturedVanillaEntities.clear();
        vanillaOriginLocations.clear();
    }

    // ── 저장/복원 ─────────────────────────────────────────────────────────

    public DomainBlockBuilder getInnateBuilder() { return innateBuilder; }

    /**
     * 서버 재시작 후 호출 — 저장된 위치와 스냅샷으로 생득 영역을 복원한다.
     */
    public void restoreFromSave(Location center, List<String> snapshotLines) {
        this.centerLocation = center.clone();
        this.isReady = true;
        if (snapshotLines != null && !snapshotLines.isEmpty()) {
            innateBuilder = new DomainBlockBuilder();
            innateBuilder.loadSnapshots(snapshotLines);
        }
    }

    // ── 술식별 구현 ───────────────────────────────────────────────────────

    /** 매 틱 처리 — 필중 효과, 시전자 강화 */
    public abstract void onActiveTick();

    /** 시전자 강화 효과 */
    public abstract void applyCasterBuff(JEntity caster);

    /** 대상 필중 효과 */
    public abstract void applySureHit(JEntity target);
}
