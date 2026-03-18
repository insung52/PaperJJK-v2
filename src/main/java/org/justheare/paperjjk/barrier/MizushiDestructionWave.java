package org.justheare.paperjjk.barrier;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.justheare.paperjjk.skill.SkillExecution;

import java.util.*;

/**
 * 결계 없는 영역전개(결없영) 전용 블럭 파괴 파도.
 *
 * 구조:
 *   tick()        : 매 틱 MAX_POS_PER_TICK 위치를 순회하며 반경을 바깥으로 확장.
 *                   비-공기 파괴가능 블럭은 0~JITTER 틱의 랜덤 딜레이를 부여해 scheduledMap 에 등록.
 *                   만료된(targetTick ≤ now) 항목을 overdue 큐로 이동.
 *   flushBlocks() : WFQ 가 부여한 예산만큼 overdue 큐에서 블럭을 꺼내 AIR 로 교체.
 *   stop()        : 영역 해제 시 파도 확장을 즉시 중단하고 미처리 항목 폐기.
 *
 * 지상 전개(onground=true) 시 y < centerY 인 블럭은 건너뛴다 (반구 처리).
 *
 * 성능 최적화: ChunkSnapshot 청크 그루핑.
 *   위치를 (chunkX, chunkZ) 로 그루핑하여 청크당 1회 ChunkSnapshot 생성.
 *   snapshot.isSectionEmpty(sectionY) 로 빈 섹션 통째로 스킵.
 *   snapshot.getBlockType(lx, y, lz) 로 HashMap 없는 직접 팔레트 접근.
 *   ~ r=50 기준 62,000번 HashMap 조회 → ~40회 snapshot 생성 + 직접 배열 접근 (~5x 속도향상).
 */
public class MizushiDestructionWave implements SkillExecution {

    /** 틱당 순회할 최대 위치 수 (위치 검색 비용 제한, 구 표면적 한도) */
    private static final int MAX_POS_PER_TICK = 220_000;

    /** 틱당 최대 반경 증가량 (블록). 시각적 확장 속도를 제어한다. */
    public static final int MAX_RADIUS_PER_TICK = 3;

    /** 블럭 파괴 기본 랜덤 딜레이 (0~JITTER 틱) */
    private static final int JITTER = 20;

    /**
     * 경도(hardness) 1당 추가되는 최대 딜레이 배율 (틱).
     * 예) 돌(1.5) → +6 틱, 흑요석(50) → +200 틱 (~10초).
     */
    private static final float HARDNESS_DELAY_SCALE = 40.0f;

    /** 블럭 파괴 시작 고정 딜레이 (틱). 판별은 즉시, 파괴는 2초 뒤. */
    static final int START_DELAY_TICKS = 40;

    private static final Set<Material> LIQUID_MATERIALS = EnumSet.of(
        Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN
    );

    private final Location center;
    private final int      maxRadius;
    private final boolean  onground;   // true = 지상 위 반구만 파괴

    private int          currentRadius = 0;
    private int          shellIndex    = 0;
    private List<int[]>  currentShell  = null;
    private int          tickCount     = 0;

    /** 최근 START_DELAY_TICKS 틱간의 currentRadius 이력 (오래된 순). 파괴 반경 계산용. */
    private final ArrayDeque<Integer> radiusHistory = new ArrayDeque<>();

    /** targetTick → 파괴 대기 블럭 좌표 배열 */
    private final Map<Integer, ArrayDeque<int[]>> scheduledMap = new HashMap<>();

    /** 만료되어 즉시 처리 가능한 블럭 (WFQ flushBlocks 에서 소비) */
    private final ArrayDeque<int[]> overdue = new ArrayDeque<>();

    private boolean waveDone = false;
    private boolean stopped  = false;

    /**
     * Two-generation ChunkSnapshot 캐시.
     *
     * snapCurrent: 이번 에포크(SNAP_EPOCH_TICKS 틱) 내 접근된 스냅샷.
     * snapPrev:    이전 에포크의 스냅샷 — 이번 에포크에 다시 접근되면 snapCurrent로 승격.
     *
     * 에포크 종료 시: snapPrev = snapCurrent, snapCurrent = 새 맵.
     * 한 에포크 이상 접근 없는 청크는 snapPrev에서 자동 소멸 → 최대 생존 1~2 에포크.
     *
     * 왜 고정 TTL 대신 2-gen 방식을 쓰나:
     *   r=200에서 한 쉘(~502K 포지션)을 처리하는 데 ~5틱이 걸리므로,
     *   같은 청크를 인접 쉘에서 다시 쓰기까지의 간격이 최대 ~8틱에 달한다.
     *   EPOCH=8로 설정하면 접근 간격이 8틱 이하인 청크는 항상 캐시 히트하고,
     *   파도가 완전히 지나간 청크는 다음 에포크 교체 시 자동 폐기된다.
     */
    private Map<Long, ChunkSnapshot> snapCurrent = new HashMap<>();
    private Map<Long, ChunkSnapshot> snapPrev    = new HashMap<>();
    private static final int SNAP_EPOCH_TICKS = 8;

    // ─────────────────────────────────────────────────────────────────────────

    public MizushiDestructionWave(Location center, int maxRadius, boolean onground) {
        this.center    = center.clone();
        this.maxRadius = maxRadius;
        this.onground  = onground;
    }

    /** scan wave 전면 반경. 나중에 시전 postprocessing 충전 효과에 사용. */
    public int getCurrentRadius() { return currentRadius; }

    /**
     * 실제 파괴가 도달한 반경 (START_DELAY_TICKS 틱 전 scan 반경).
     * 클라이언트 SYNC 패킷 전송에 사용.
     */
    public int getDestructionRadius() {
        if (radiusHistory.size() < START_DELAY_TICKS) return 0;
        return radiusHistory.peekFirst();
    }

    public void stop() {
        stopped  = true;
        waveDone = true;
        scheduledMap.clear();
        overdue.clear();
        snapCurrent.clear();
        snapPrev.clear();
        currentShell = null;
    }

    // ── SkillExecution ────────────────────────────────────────────────────────

    @Override
    public void tick() {
        tickCount++;

        // ── 파도 전진: MAX_POS_PER_TICK 위치 순회 ────────────────────────────
        if (!waveDone && !stopped) {
            World world = center.getWorld();
            if (world == null) {
                waveDone = true;
            } else {
                int minHeight = world.getMinHeight();
                int maxHeight = world.getMaxHeight();

                // 에포크 교체: snapPrev 폐기 → snapCurrent이 snapPrev로, 새 빈 맵이 snapCurrent로
                if (tickCount % SNAP_EPOCH_TICKS == 0) {
                    snapPrev    = snapCurrent;
                    snapCurrent = new HashMap<>();
                }

                int posProcessed  = 0;
                int radiiAdvanced = 0;

                // 이번 틱에 수집된 좌표를 청크별로 그루핑.
                // key = (chunkX << 32) | (chunkZ & 0xFFFFFFFFL)
                // value = List<int[]> { bx, by, bz, lx, lz }
                Map<Long, List<int[]>> chunkGroups = new HashMap<>();

                while (posProcessed < MAX_POS_PER_TICK) {
                    if (currentShell == null || shellIndex >= currentShell.size()) {
                        if (currentRadius > maxRadius) {
                            waveDone = true;
                            break;
                        }
                        if (radiiAdvanced >= MAX_RADIUS_PER_TICK) break;
                        currentShell = DomainBlockBuilder.getSphereOffsets(currentRadius);
                        shellIndex   = 0;
                        currentRadius++;
                        radiiAdvanced++;
                    }

                    while (shellIndex < currentShell.size() && posProcessed < MAX_POS_PER_TICK) {
                        int[] off = currentShell.get(shellIndex++);
                        posProcessed++;

                        if (onground && off[1] < 0) continue;

                        int bx = center.getBlockX() + off[0];
                        int by = center.getBlockY() + off[1];
                        int bz = center.getBlockZ() + off[2];

                        if (by < minHeight || by >= maxHeight) continue;

                        int cx = bx >> 4;
                        int cz = bz >> 4;
                        int lx = bx & 15;
                        int lz = bz & 15;

                        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                        chunkGroups.computeIfAbsent(key, k -> new ArrayList<>())
                                   .add(new int[]{bx, by, bz, lx, lz});
                    }
                }

                // ── 청크 그루핑 처리: 크로스-틱 캐시에서 스냅샷 조회, 없으면 새로 생성 ──
                int sectionMinY = world.getMinHeight() >> 4;
                for (Map.Entry<Long, List<int[]>> entry : chunkGroups.entrySet()) {
                    long key = entry.getKey();
                    int cx = (int)(key >> 32);
                    int cz = (int)(key);

                    // 2-gen 캐시 조회: hot → warm 순, warm 히트 시 hot으로 승격
                    ChunkSnapshot snapshot = snapCurrent.get(key);
                    if (snapshot == null) {
                        snapshot = snapPrev.get(key);
                        if (snapshot != null) {
                            snapCurrent.put(key, snapshot); // warm → hot 승격
                        } else {
                            if (!world.isChunkLoaded(cx, cz)) continue;
                            // false, false, false = 높이맵/생물군계/그림자 불필요
                            snapshot = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
                            snapCurrent.put(key, snapshot);
                        }
                    }

                    for (int[] pos : entry.getValue()) {
                        int bx = pos[0], by = pos[1], bz = pos[2];
                        int lx = pos[3], lz = pos[4];

                        int sectionY = (by >> 4) - sectionMinY;
                        // 빈 섹션(전부 공기) 통째로 스킵
                        if (snapshot.isSectionEmpty(sectionY)) continue;

                        Material mat = snapshot.getBlockType(lx, by, lz);
                        if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) continue;

                        float hardness = mat.getHardness();
                        if (hardness < 0) continue; // 베드락 등 파괴불가 스킵

                        // 액체 처리: 짧은 딜레이로 제거
                        if (LIQUID_MATERIALS.contains(mat)) {
                            int delay  = START_DELAY_TICKS + (int)(Math.random() * 6);
                            int target = tickCount + delay;
                            scheduledMap.computeIfAbsent(target, k -> new ArrayDeque<>())
                                        .add(new int[]{bx, by, bz});
                            continue;
                        }

                        // 매우 단단한 블록 (경도 25 이상): 10% 확률로 건너뜀
                        if (hardness >= 25 && Math.random() < 0.1) continue;

                        int jitterMax = JITTER + (int)(hardness * HARDNESS_DELAY_SCALE);
                        int delay     = START_DELAY_TICKS + (int)(Math.random() * (jitterMax + 1));
                        int target    = tickCount + delay;
                        scheduledMap.computeIfAbsent(target, k -> new ArrayDeque<>())
                                    .add(new int[]{bx, by, bz});
                    }
                }
            }
        }

        // ── 이번 틱 만료 항목 → overdue 이동 ─────────────────────────────────
        ArrayDeque<int[]> ready = scheduledMap.remove(tickCount);
        if (ready != null) overdue.addAll(ready);

        // ── 반경 이력 갱신: START_DELAY_TICKS 틱 전 scan 반경을 파괴 반경으로 노출 ──
        radiusHistory.addLast(currentRadius);
        if (radiusHistory.size() > START_DELAY_TICKS) radiusHistory.pollFirst();
    }

    @Override
    public int flushBlocks(int budget) {
        if (overdue.isEmpty()) return 0;

        World world = center.getWorld();
        if (world == null) return 0;

        int count = 0;
        while (!overdue.isEmpty() && count < budget) {
            int[] pos   = overdue.poll();
            Block block = world.getBlockAt(pos[0], pos[1], pos[2]);

            if (!block.isEmpty() || block.isLiquid()) {
                float h = block.getType().getHardness();
                if (h >= 0) block.setType(Material.AIR, false);
            }
            count++;
        }
        return count;
    }

    @Override
    public boolean isDone() {
        return waveDone && overdue.isEmpty() && scheduledMap.isEmpty();
    }

    @Override
    public int getPriority() {
        return 0; // CRITICAL — 다른 스킬보다 먼저 블럭 파괴 예산 할당
    }
}
