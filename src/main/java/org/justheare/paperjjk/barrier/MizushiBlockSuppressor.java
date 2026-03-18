package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.skill.SkillExecution;

import java.util.*;

/**
 * 결없영(isOpen=true) 활성 중에 결계 반경 내 새로 생성되는 블럭을 감지해
 * 딜레이 후 파괴하는 suppressor.
 *
 * 동작:
 *   1. 이벤트 감지: BlockPlaceEvent, BlockFromToEvent, BlockGrowEvent, BlockSpreadEvent
 *      → 이벤트를 취소하지 않고 블럭 위치를 큐에 추가
 *   2. 랜덤 샘플링 폴백: 틱당 SAMPLE_PER_TICK 회 반경 내 임의 위치를 확인
 *      → DomainBlockBuilder 옵시디언 등 plugin-placed 블럭 처리용
 *   3. 딜레이 파괴: MizushiDestructionWave 와 동일한 hardness 기반 scheduledMap
 *      (START_DELAY_TICKS 없음 — 이미 활성 도메인)
 */
public class MizushiBlockSuppressor implements SkillExecution, Listener {

    /** 블럭 파괴 기본 랜덤 딜레이 (틱) */
    private static final int JITTER = 15;

    /** 경도 1당 추가되는 최대 딜레이 배율 (틱) — DestructionWave와 동일 */
    private static final float HARDNESS_DELAY_SCALE = 40.0f;

    private final Location center;
    private final int radius;
    private final double radiusSq;
    /** 파괴 범위 (radius+1)² — 반경보다 1블럭 더 파괴 허용 */
    private final double radiusSqExpanded;
    /** 틱당 랜덤 샘플링 횟수 — 반경² / 25, 최소 20 */
    private final int samplePerTick;
    /** 이 틱 이후부터 소리 재생 (충전 딜레이 동안 무음) */
    private final int soundDelayTicks;

    private int tickCount = 0;
    private boolean stopped = false;

    private final Random rng = new Random();

    /** 이미 큐에 등록된 블럭 좌표 (중복 방지). Minecraft 표준 블록 인코딩 사용. */
    private final Set<Long> queued = new HashSet<>();

    /** targetTick → 파괴 대기 블럭 좌표 배열 */
    private final Map<Integer, ArrayDeque<int[]>> scheduledMap = new HashMap<>();

    /** 만료되어 즉시 처리 가능한 블럭 (flushBlocks 에서 소비) */
    private final ArrayDeque<int[]> overdue = new ArrayDeque<>();

    public MizushiBlockSuppressor(Location center, int radius, int soundDelayTicks) {
        this.center          = center.clone();
        this.radius          = radius;
        this.radiusSq        = (double) radius * radius;
        this.radiusSqExpanded = (double)(radius + 1) * (radius + 1);
        this.samplePerTick   = Math.max(20, radius * radius / 25);
        this.soundDelayTicks = soundDelayTicks;

        PaperJJK.instance.getServer().getPluginManager()
            .registerEvents(this, PaperJJK.instance);
    }

    // ── 좌표 인코딩 ───────────────────────────────────────────────────────

    /** (x, y, z) → long. Minecraft 범위(±30M, y -2048~2048)를 처리한다. */
    private static long encode(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38)
             | ((long)(z & 0x3FFFFFF) << 12)
             | (long)((y + 2048) & 0xFFF);
    }

    // ── 내부: 블럭 큐 등록 ────────────────────────────────────────────────

    private void tryQueue(Block block) {
        if (stopped) return;
        if (!block.getWorld().equals(center.getWorld())) return;

        int bx = block.getX(), by = block.getY(), bz = block.getZ();
        if (by < center.getBlockY()-1) return; // 지상 전개 — 지면 아래 제외
        double dx = bx - center.getX(), dy = by - center.getY(), dz = bz - center.getZ();
        if (dx*dx + dy*dy + dz*dz > radiusSqExpanded) return;

        Material mat = block.getType();
        if (mat == Material.AIR || mat == Material.CAVE_AIR || mat == Material.VOID_AIR) return;

        float hardness = mat.getHardness();
        if (hardness < 0) return; // 파괴불가 (베드락 등)

        long key = encode(bx, by, bz);
        if (!queued.add(key)) return; // 이미 등록됨

        int jitterMax = JITTER + (int)(hardness * HARDNESS_DELAY_SCALE);
        int delay  = 2 + (int)(rng.nextInt(jitterMax + 1));
        int target = tickCount + delay;
        scheduledMap.computeIfAbsent(target, k -> new ArrayDeque<>())
                    .add(new int[]{bx, by, bz});
    }

    // ── Bukkit 이벤트 핸들러 ──────────────────────────────────────────────

    /** 플레이어가 블럭을 설치한 경우 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        tryQueue(event.getBlock());
    }

    /** 물/용암 흐름이 새 블럭을 형성한 경우 (도착 위치) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        tryQueue(event.getToBlock());
    }

    /** 식물 등 블럭이 성장한 경우 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        tryQueue(event.getBlock());
    }

    /** 불, 균사체 등 블럭이 인접 위치로 번진 경우 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        tryQueue(event.getBlock());
    }

    // ── SkillExecution ────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (stopped) return;
        tickCount++;

        // ── 랜덤 샘플링: plugin-placed 블럭(옵시디언 등) 처리 ────────────
        World world = center.getWorld();
        if (world != null) {
            int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
            int minH = world.getMinHeight(), maxH = world.getMaxHeight();

            for (int i = 0; i < samplePerTick; i++) {
                int dx = (int)((rng.nextDouble() * 2 - 1) * (radius+1));
                int dy = (int)((rng.nextDouble() * 2 - 1) * (radius+1));
                int dz = (int)((rng.nextDouble() * 2 - 1) * (radius+1));
                if ((double)(dx*dx + dy*dy + dz*dz) > radiusSqExpanded) continue;

                int bx = cx + dx, by = cy + dy, bz = cz + dz;
                if (by < cy - 1 || by < minH || by >= maxH) continue; // 지면 1칸 아래까지 허용

                // 백색소음: 충전 딜레이 이후부터, 샘플 위치마다 무조건 소리 재생
                if (tickCount > soundDelayTicks) {
                    world.playSound(new Location(world, bx + 0.5, by + 0.5, bz + 0.5),
                            Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0f, 0.6f);
                }

                Block block = world.getBlockAt(bx, by, bz);
                if (!block.isEmpty() && !block.isLiquid()) tryQueue(block);
            }
        }

        // ── 이번 틱 만료 항목 → overdue 이동 ─────────────────────────────
        ArrayDeque<int[]> ready = scheduledMap.remove(tickCount);
        if (ready != null) overdue.addAll(ready);
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
            // 파괴 처리 후 queued 에서 제거 → 같은 위치에 다시 블럭 놓이면 재큐 가능
            queued.remove(encode(pos[0], pos[1], pos[2]));
            count++;
        }
        return count;
    }

    @Override
    public boolean isDone() {
        return stopped && overdue.isEmpty() && scheduledMap.isEmpty();
    }

    @Override
    public int getPriority() { return 1; } // NORMAL

    public void stop() {
        stopped = true;
        HandlerList.unregisterAll(this);
        scheduledMap.clear();
        overdue.clear();
        queued.clear();
    }
}
