package org.justheare.paperjjk.barrier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.*;
import java.util.logging.Logger;

/**
 * 영역전개 결계 구(球) 블록의 점진적 생성 및 복원을 담당.
 *
 * 구 표면 오프셋은 반경당 1회 계산 후 SPHERE_CACHE에 공유 저장.
 * buildTick()과 restoreTick()은 각각 매 틱 budget 수만큼 블록을 처리.
 */
public class DomainBlockBuilder {

    /** 반경별 구 표면 오프셋 캐시 (서버 전역 공유, 비동기 워밍업 스레드와 공유하므로 ConcurrentHashMap) */
    private static final Map<Integer, List<int[]>> SPHERE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 원본 블록 스냅샷 (복원용) */
    private record BlockSnapshot(int x, int y, int z, String worldName,
                                 Material material, BlockData data) {}

    private final List<BlockSnapshot> snapshots = new ArrayList<>();

    /** buildTick 진행 인덱스 */
    private int buildIndex = 0;

    /** restoreTick 진행 인덱스 */
    private int restoreIndex = 0;

    /** 결계 블록 위치 색인 (BlockBreak 이벤트 빠른 조회용) */
    private final Set<String> barrierBlockKeys = new HashSet<>();

    /** buildTick 시 기록한 중심 좌표·반경·재질 (restoreTick 에서도 사용) */
    private int buildCX, buildCY, buildCZ;
    private String buildWorldName;
    private int buildRadius;
    private Material buildMaterial;
    private boolean buildStarted = false;

    // ── 구 표면 오프셋 캐시 ────────────────────────────────────────────────

    /**
     * 서버 시작 시 비동기 백그라운드 스레드에서 0~maxRadius 범위의 구 오프셋을 미리 계산.
     * 메인 스레드에서 처음 전개할 때 발생하는 수백ms 렉을 방지한다.
     */
    public static void warmupAsync(int maxRadius) {
        Thread t = new Thread(() -> {
            for (int r = 0; r <= maxRadius; r++) {
                getSphereOffsets(r);
            }
            java.util.logging.Logger.getLogger("PaperJJK")
                .info("[DomainBlockBuilder] Sphere offset warmup complete (r=0~" + maxRadius + ")");
        }, "PaperJJK-SphereWarmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 반경 radius의 구 표면을 이루는 블록 오프셋 목록을 반환.
     * |sqrt(dx²+dy²+dz²) - radius| ≤ 0.5 조건으로 필터링.
     */
    public static List<int[]> getSphereOffsets(int radius) {
        return SPHERE_CACHE.computeIfAbsent(radius, r -> {
            List<int[]> offsets = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                        if (Math.abs(dist - r) <= 0.5) {
                            offsets.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
            // +Y → -Y 방향으로 블록이 생성/소멸되도록 dy 내림차순 정렬
            offsets.sort((a, b) -> Integer.compare(b[1], a[1]));
            return offsets;
        });
    }

    // ── 점진적 구 생성 ─────────────────────────────────────────────────────

    /**
     * 매 틱 구 표면 블록을 budget 수만큼 배치한다.
     *
     * @param center   구 중심 위치
     * @param radius   반경 (블록)
     * @param material 결계 블록 재질
     * @param budget   이번 틱 최대 처리 블록 수
     * @return 구가 완전히 완성되면 true
     */
    public boolean buildTick(Location center, int radius, Material material, int budget) {
        World world = center.getWorld();
        if (world == null) return false;

        if (!buildStarted) {
            buildStarted = true;
            buildCX = center.getBlockX();
            buildCY = center.getBlockY();
            buildCZ = center.getBlockZ();
            buildWorldName = world.getName();
            buildRadius = radius;
            buildMaterial = material;
        }

        List<int[]> offsets = getSphereOffsets(radius);
        int end = Math.min(buildIndex + budget, offsets.size());

        for (int i = buildIndex; i < end; i++) {
            int[] off = offsets.get(i);
            int bx = buildCX + off[0];
            int by = buildCY + off[1];
            int bz = buildCZ + off[2];

            if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;

            Block block = world.getBlockAt(bx, by, bz);
            if (block.getType() != material) {
                // 원본 저장 후 결계 블록 배치
                snapshots.add(new BlockSnapshot(bx, by, bz, buildWorldName,
                        block.getType(), block.getBlockData().clone()));
                block.setType(material, false);
            }
            barrierBlockKeys.add(bx + "," + by + "," + bz);
        }

        buildIndex = end;
        return buildIndex >= offsets.size();
    }

    // ── 점진적 블록 복원 ────────────────────────────────────────────────────

    /**
     * 저장된 원본 블록 상태를 점진적으로 복원한다.
     *
     * @param budget 이번 틱 최대 처리 블록 수
     * @return 복원이 완전히 완료되면 true
     */
    public boolean restoreTick(int budget) {
        if (snapshots.isEmpty()) return true;

        int end = Math.min(restoreIndex + budget, snapshots.size());
        for (int i = restoreIndex; i < end; i++) {
            BlockSnapshot snap = snapshots.get(i);
            World world = Bukkit.getWorld(snap.worldName());
            if (world != null) {
                world.getBlockAt(snap.x(), snap.y(), snap.z())
                        .setBlockData(snap.data().clone(), false);
            }
        }
        restoreIndex = end;
        return restoreIndex >= snapshots.size();
    }

    // ── 조회 ──────────────────────────────────────────────────────────────

    /** 특정 위치가 이 결계의 블록인지 확인 */
    public boolean isBarrierBlock(int bx, int by, int bz) {
        return barrierBlockKeys.contains(bx + "," + by + "," + bz);
    }

    /** 결계 블록 색인에서 제거 (BlockBreak 이벤트 시 호출) */
    public void removeBarrierBlock(int bx, int by, int bz) {
        barrierBlockKeys.remove(bx + "," + by + "," + bz);
    }

    public boolean isBuildDone() {
        return buildStarted && buildIndex >= getSphereOffsets(buildRadius).size();
    }

    public boolean isRestoreDone() {
        return restoreIndex >= snapshots.size();
    }

    public int getSnapshotCount() { return snapshots.size(); }

    // ── 직렬화 / 역직렬화 (서버 재시작 후 복원) ──────────────────────────────

    /**
     * 스냅샷 목록을 문자열 리스트로 직렬화.
     * 형식: "x|y|z|worldName|MATERIAL|blockDataString"
     * 필드 구분자로 '|' 사용 (blockDataString 내부에 '|' 없음).
     */
    public List<String> serializeSnapshots() {
        List<String> result = new ArrayList<>(snapshots.size());
        for (BlockSnapshot s : snapshots) {
            result.add(s.x() + "|" + s.y() + "|" + s.z() + "|"
                    + s.worldName() + "|" + s.material().name() + "|"
                    + s.data().getAsString());
        }
        return result;
    }

    /**
     * 문자열 리스트에서 스냅샷을 복원한다 (서버 재시작 후 호출).
     * buildIndex 를 snapshots.size() 로 설정해 빌드 완료로 표시.
     */
    public void loadSnapshots(List<String> lines) {
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            String[] parts = line.split("\\|", 6);
            if (parts.length < 6) continue;
            try {
                int x            = Integer.parseInt(parts[0].trim());
                int y            = Integer.parseInt(parts[1].trim());
                int z            = Integer.parseInt(parts[2].trim());
                String worldName = parts[3].trim();
                Material mat     = Material.valueOf(parts[4].trim());
                BlockData data   = Bukkit.createBlockData(parts[5].trim());
                snapshots.add(new BlockSnapshot(x, y, z, worldName, mat, data));
                barrierBlockKeys.add(x + "," + y + "," + z);
            } catch (Exception e) {
                Logger.getLogger("PaperJJK").warning("[DomainBlockBuilder] Snapshot parse failed: " + line);
            }
        }
        buildIndex   = snapshots.size();
        buildStarted = !snapshots.isEmpty();
    }
}
