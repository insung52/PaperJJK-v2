package org.justheare.paperjjk.barrier;

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
 */
public class MizushiDestructionWave implements SkillExecution {

    /** 틱당 순회할 최대 위치 수 (위치 검색 비용 제한, 구 표면적 한도) */
    private static final int MAX_POS_PER_TICK = 200_000;

    /** 블럭 파괴 기본 랜덤 딜레이 (0~JITTER 틱) */
    private static final int JITTER = 12;

    /**
     * 경도(hardness) 1당 추가되는 최대 딜레이 배율 (틱).
     * 예) 돌(1.5) → +6 틱, 흑요석(50) → +200 틱 (~10초).
     * 딜레이 자체가 저항을 표현하므로 flushBlocks 에서는 별도 확률 체크 없이 즉시 파괴.
     */
    private static final float HARDNESS_DELAY_SCALE = 4.0f;

    private final Location center;
    private final int      maxRadius;
    private final boolean  onground;   // true = 지상 위 반구만 파괴

    private int          currentRadius = 0;
    private int          shellIndex    = 0;
    private List<int[]>  currentShell  = null;
    private int          tickCount     = 0;

    /** targetTick → 파괴 대기 블럭 좌표 배열 */
    private final Map<Integer, ArrayDeque<int[]>> scheduledMap = new HashMap<>();

    /** 만료되어 즉시 처리 가능한 블럭 (WFQ flushBlocks 에서 소비) */
    private final ArrayDeque<int[]> overdue = new ArrayDeque<>();

    private boolean waveDone = false;
    private boolean stopped  = false;

    // ─────────────────────────────────────────────────────────────────────────

    public MizushiDestructionWave(Location center, int maxRadius, boolean onground) {
        this.center    = center.clone();
        this.maxRadius = maxRadius;
        this.onground  = onground;
    }

    /**
     * 영역이 닫힐 때(onClosing) 호출.
     * 파도 확장을 즉시 중단하고 미처리 항목을 모두 폐기.
     * isDone() 이 곧 true 가 되어 WorkScheduler 에서 자동 제거됨.
     */
    public void stop() {
        stopped  = true;
        waveDone = true;
        scheduledMap.clear();
        overdue.clear();
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
                int posProcessed = 0;
                int baseY = center.getBlockY();

                while (posProcessed < MAX_POS_PER_TICK) {
                    // 현재 셸 소진 시 다음 반경으로
                    if (currentShell == null || shellIndex >= currentShell.size()) {
                        if (currentRadius > maxRadius) {
                            waveDone = true;
                            break;
                        }
                        currentShell = DomainBlockBuilder.getSphereOffsets(currentRadius);
                        shellIndex   = 0;
                        currentRadius++;
                    }

                    while (shellIndex < currentShell.size() && posProcessed < MAX_POS_PER_TICK) {
                        int[] off = currentShell.get(shellIndex++);
                        posProcessed++;

                        // 지상 전개: 지하 블럭 스킵
                        if (onground && off[1] < 0) continue;

                        int bx = center.getBlockX() + off[0];
                        int by = center.getBlockY() + off[1];
                        int bz = center.getBlockZ() + off[2];

                        if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;

                        Block block = world.getBlockAt(bx, by, bz);
                        if (block.isEmpty()) continue;        // 공기 스킵
                        float h = block.getType().getHardness();
                        if (h < 0) continue;                  // 베드락 등 파괴불가 스킵
                        if(block.isLiquid()){
                            int maxDelay = 7;
                            int delay    = (int)(Math.random() * (maxDelay + 1));
                            int target   = tickCount + delay;
                            scheduledMap.computeIfAbsent(target, k -> new ArrayDeque<>())
                                    .add(new int[]{bx, by, bz});
                            continue;
                        }
                        else if(h>=25) {
                            if(Math.random()<0.1){
                                continue;
                            }
                        }
                        // 경도 기반 랜덤 딜레이: 딜레이 자체가 저항을 표현
                        // dirt(0.5)→max14틱, stone(1.5)→max18틱, obsidian(50)→max212틱(~10.6s)
                        int maxDelay = JITTER + (int)(h * HARDNESS_DELAY_SCALE);
                        int delay    = (int)(Math.random() * (maxDelay + 1));
                        int target   = tickCount + delay;
                        scheduledMap.computeIfAbsent(target, k -> new ArrayDeque<>())
                                    .add(new int[]{bx, by, bz});
                    }
                }
            }
        }

        // ── 이번 틱 만료 항목 → overdue 이동 ─────────────────────────────────
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

            // 딜레이 자체가 저항을 표현했으므로 만료 시 즉시 파괴
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
