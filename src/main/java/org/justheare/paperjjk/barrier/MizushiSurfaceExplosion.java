package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.World;
import org.justheare.paperjjk.skill.SkillExecution;

import java.util.ArrayDeque;
import java.util.List;

/**
 * fuga 폭발 후 지표면과 영역 경계 껍질에 폭발+화염을 일으키는 이펙트.
 *
 * [바닥 페이즈]
 *   - y = center.y - 1 평면에서 반경 1 → maxRadius 까지 틱당 +2씩 원을 확장.
 *   - 각도 스텝 = FLOOR_DENSITY / r → 호 길이 ≈ 4블럭 간격.
 *   - 해당 위치가 비-공기 블럭이면 폭발 실행.
 *
 * [껍질 페이즈]
 *   - 바닥 원이 maxRadius 도달 직후 시작.
 *   - getSphereOffsets(maxRadius+1) 껍질을 DENSITY_STEP=4 간격으로 샘플링.
 *   - 비-공기 블럭 위치를 폭발 큐에 추가 후 틱당 MAX_SHELL_EXPLODE_PER_TICK 개씩 소비.
 */
public class MizushiSurfaceExplosion implements SkillExecution {

    /** 틱당 바닥 원 반경 증가량 */
    private static final int FLOOR_STEP = 4;

    /** 바닥 원 호 길이 간격 (블럭). angle step = FLOOR_DENSITY / r */
    private static final double FLOOR_DENSITY = 5.0;

    /** 껍질 페이즈: 틱당 스캔 최대 포지션 수 */
    private static final int MAX_SCAN_PER_TICK = 100_000;

    /** 껍질 페이즈: 포지션 샘플링 간격 (4포지션당 1개 검사) */
    private static final int DENSITY_STEP = 20;

    /** 껍질 페이즈: 틱당 최대 폭발 수 */
    private static final int MAX_SHELL_EXPLODE_PER_TICK = 100;

    private final Location  center;
    private final int       maxRadius;
    private final List<int[]> shellOffsets;

    // ── 바닥 페이즈 ──────────────────────────────────────────────────────────
    private int     floorRadius = 1;
    private boolean floorDone   = false;

    // ── 껍질 페이즈 ──────────────────────────────────────────────────────────
    private int     scanIndex = 0;
    private boolean scanDone  = false;
    private final ArrayDeque<int[]> explosionQueue = new ArrayDeque<>();

    // ─────────────────────────────────────────────────────────────────────────

    public MizushiSurfaceExplosion(Location center, int radius) {
        this.center       = center.clone();
        this.maxRadius    = radius;
        this.shellOffsets = DomainBlockBuilder.getSphereOffsets(radius + 1);
    }

    @Override
    public void tick() {
        World world = center.getWorld();
        if (world == null) return;

        if (!floorDone) {
            tickFloor(world);
        } else {
            tickShell(world);
        }
    }

    /**
     * 바닥 페이즈: y = center.y - 1 에서 반경 floorRadius 원을 따라 폭발.
     * angleStep = FLOOR_DENSITY / r 로 호 길이 ≈ 4블럭 간격 유지.
     */
    private void tickFloor(World world) {
        int cx   = center.getBlockX();
        int cy   = center.getBlockY() - 1; // 지표면
        int cz   = center.getBlockZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        if (cy >= minY && cy < maxY) {
            double angleStep = FLOOR_DENSITY / floorRadius;
            for (double a = 0; a < Math.PI * 2; a += angleStep) {
                int bx = cx + (int) Math.round(floorRadius * Math.cos(a));
                int bz = cz + (int) Math.round(floorRadius * Math.sin(a));
                if (!world.getBlockAt(bx, cy, bz).isEmpty()) {
                    world.createExplosion(bx + 0.5, cy + 1.5, bz + 0.5, 7f, true, true);
                }
            }
        }

        floorRadius += FLOOR_STEP;
        if (floorRadius > maxRadius) floorDone = true;
    }

    /**
     * 껍질 페이즈: getSphereOffsets(maxRadius+1) 껍질 스캔 후 폭발 큐 소비.
     */
    private void tickShell(World world) {
        // Phase 1: 스캔
        if (!scanDone) {
            int cx   = center.getBlockX();
            int cy   = center.getBlockY();
            int cz   = center.getBlockZ();
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int end  = Math.min(scanIndex + MAX_SCAN_PER_TICK, shellOffsets.size());

            for (int i = scanIndex; i < end; i++) {
                // 랜덤 샘플링: 인덱스 기반(% N)은 dy 정렬 구조로 인해 방향 편향 발생
                if (Math.random() * DENSITY_STEP >= 1.0) continue;

                int[] off = shellOffsets.get(i);
                if (off[1] < 0) continue;

                int by = cy + off[1];
                if (by < minY || by >= maxY) continue;

                int bx = cx + off[0];
                int bz = cz + off[2];
                if (!world.getBlockAt(bx, by, bz).isEmpty()) {
                    explosionQueue.add(new int[]{bx, by, bz});
                }
            }

            scanIndex = end;
            if (scanIndex >= shellOffsets.size()) scanDone = true;
        }

        // Phase 2: 폭발 큐 소비
        int count = 0;
        while (!explosionQueue.isEmpty() && count < MAX_SHELL_EXPLODE_PER_TICK) {
            int[] pos = explosionQueue.poll();
            world.createExplosion(pos[0], pos[1], pos[2], 5f, true, true);
            count++;
        }
    }

    @Override
    public boolean isDone() {
        return floorDone && scanDone && explosionQueue.isEmpty();
    }

    @Override
    public int getPriority() {
        return 2; // COSMETIC
    }
}
