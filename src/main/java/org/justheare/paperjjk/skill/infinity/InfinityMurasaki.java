package org.justheare.paperjjk.skill.infinity;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.skill.SkillPhase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 무한(Infinity) — 자주(紫, Murasaki) 스킬.
 *
 * ao 와 aka 가 충돌할 때 자동 발동. 키 입력 없음.
 *
 * 일반 murasaki:
 *   방향으로 고속 돌진하며 블록 파괴 + 엔티티 데미지.
 *   클라이언트: MURASAKI_START → MURASAKI_SYNC (위치 추적)
 *
 * 무제한 murasaki (passive 꺼짐 + 충돌 근거리):
 *   제자리에서 반경 팽창 폭발. 시전자도 일정 데미지.
 *   클라이언트: MURASAKI_START_EXPLODE → MURASAKI_SYNC_RADIUS (반경 추적)
 */
public class InfinityMurasaki extends ActiveSkill {

    // ── Fibonacci 구면 에너지 그리드 (정적, 512 균등 분포 방향) ───────────

    private static final int   FIB_N          = 512;
    private static final float GOLDEN_ANGLE   = (float)(Math.PI * (3.0 - Math.sqrt(5.0)));
    private static final float[] FIB_X        = new float[FIB_N];
    private static final float[] FIB_Y        = new float[FIB_N];
    private static final float[] FIB_Z        = new float[FIB_N];
    private static final int[][] FIB_NEIGHBORS;  // 각 셀의 6-nearest 이웃 (확산용)
    private static final float BLEND_THRESHOLD = 0.90f;

    static {
        for (int i = 0; i < FIB_N; i++) {
            double cosT = 1.0 - 2.0 * i / (double)(FIB_N - 1);
            double sinT = Math.sqrt(Math.max(0.0, 1.0 - cosT * cosT));
            double phi  = GOLDEN_ANGLE * i;
            FIB_X[i] = (float)(sinT * Math.cos(phi));
            FIB_Y[i] = (float)cosT;
            FIB_Z[i] = (float)(sinT * Math.sin(phi));
        }
        FIB_NEIGHBORS = new int[FIB_N][];
        for (int i = 0; i < FIB_N; i++) {
            final int K = 6;
            int[]   topIdx = new int[K];
            float[] topDot = new float[K];
            Arrays.fill(topDot, -2f);
            for (int j = 0; j < FIB_N; j++) {
                if (j == i) continue;
                float dot = FIB_X[i]*FIB_X[j] + FIB_Y[i]*FIB_Y[j] + FIB_Z[i]*FIB_Z[j];
                int worst = 0;
                for (int k = 1; k < K; k++) if (topDot[k] < topDot[worst]) worst = k;
                if (dot > topDot[worst]) { topDot[worst] = dot; topIdx[worst] = j; }
            }
            FIB_NEIGHBORS[i] = topIdx.clone();
        }
    }

    // ── 구 표면 오프셋 캐시 ───────────────────────────────────────────────
    // 예전 PaperJJK.generateSphereSurfaceCache() / getSphereSurfaceOffsets() 그대로 포팅.
    // 표면 판정: (r-1)² < dx²+dy²+dz² ≤ r²  (정수 거리 기반, floating point 오차 없음)
    // 1/8 옥탄트(dx≥0, dy≥0, dz≥0) 만 저장 후 ±부호 8방향으로 전개.

    private static final Map<Integer, int[][]> SPHERE_CACHE = new ConcurrentHashMap<>();

    private static int[][] getSphereSurface(int r) {
        return SPHERE_CACHE.computeIfAbsent(r, radius -> {
            int rSq   = radius * radius;
            int rM1Sq = (radius - 1) * (radius - 1);

            // 1/8 옥탄트 수집
            List<int[]> octant = new ArrayList<>();
            for (int dx = 0; dx <= radius; dx++) {
                for (int dy = 0; dy <= radius; dy++) {
                    for (int dz = 0; dz <= radius; dz++) {
                        int dSq = dx*dx + dy*dy + dz*dz;
                        if (dSq > rM1Sq && dSq <= rSq) {
                            octant.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }

            // 8방향 ± 부호 전개 (Set으로 중복 제거 — 좌표 0인 경우 자동 처리)
            Set<Long> seen = new HashSet<>();
            List<int[]> full = new ArrayList<>(octant.size() * 8);
            for (int[] o : octant) {
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        for (int sz = -1; sz <= 1; sz += 2) {
                            int x = o[0]*sx, y = o[1]*sy, z = o[2]*sz;
                            // x,y,z ∈ [-100, 100] → +128 으로 [28,228], 8비트 이내
                            long key = ((long)(x+128) << 16) | ((long)(y+128) << 8) | (z+128);
                            if (seen.add(key)) full.add(new int[]{x, y, z});
                        }
                    }
                }
            }
            return full.toArray(new int[0][]);
        });
    }

    // ── 상수 ──────────────────────────────────────────────────────────────

    private static final double VISUAL_RANGE   = 2000.0;
    private static final int    STEPS_PER_TICK = 10;
    private static final double STEP_SIZE      = 1;

    // ── 상태 ──────────────────────────────────────────────────────────────

    private Location murasakiLocation;
    private final Vector  direction;
    private final double  power;
    private final boolean unlimitedMode;

    /** 일반 모드: 생존 틱 한도 */
    private final int lifeTime;
    private int activeTick = 0;

    /** 일반 모드: 이미 데미지를 받은 엔티티 (1회) */
    private final Set<UUID> hitEntities = new HashSet<>();

    /** 무제한 모드 */
    private int me_tick = 0;
    private int me_cr   = 0;
    private final Set<UUID> hitEntitiesUnlimited = new HashSet<>();

    // Fibonacci 에너지 그리드 (인스턴스, 첫 틱에 초기화)
    private float[] energyGrid;
    private float[] energyBuffer;
    private final float[] _bandW = new float[80];  // band ≤ 67, 여유분

    private boolean packetActive = false;
    private final String uniqueId;

    // ── 생성자 ────────────────────────────────────────────────────────────

    /**
     * @param location     ao-aka 충돌 지점
     * @param direction    aka 의 이동 방향 (일반 모드에서 사용)
     * @param power        ao.power + aka.power (최대 200)
     * @param unlimitedMode true → 무제한 murasaki (제자리 폭발)
     */
    public InfinityMurasaki(JEntity caster, Location location, Vector direction,
                             double power, boolean unlimitedMode) {
        super(caster, 0);
        this.phase = SkillPhase.ACTIVE;  // 충전 단계 없이 즉시 발동

        this.murasakiLocation = location;
        this.direction        = direction.normalize();
        this.power            = Math.min(200, power);
        this.unlimitedMode    = unlimitedMode;
        this.lifeTime         = (int)(10 + power / 2);
        this.uniqueId         = "MURASAKI_" + System.nanoTime();
    }

    // ── 생명주기 ──────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        activeTick++;
        if (unlimitedMode) {
            tickUnlimited(jp.player);
        } else {
            tickNormal(jp.player);
        }
    }

    @Override
    protected void onEnd() {
        if (packetActive) {
            JPacketSender.broadcastInfinityMurasakiEnd(murasakiLocation, uniqueId, VISUAL_RANGE);
            packetActive = false;
        }
    }

    // ── 일반 murasaki ─────────────────────────────────────────────────────

    private void tickNormal(Player p) {
        if (!packetActive) {
            float strength = powerToStrength(power);
            JPacketSender.broadcastInfinityMurasakiStart(murasakiLocation, strength, uniqueId, VISUAL_RANGE);
            packetActive = true;
            p.getWorld().playSound(murasakiLocation, Sound.ITEM_TRIDENT_THUNDER,
                    SoundCategory.PLAYERS, 7f, 1.7f);
            p.getWorld().playSound(murasakiLocation, Sound.ITEM_TRIDENT_RETURN,
                    SoundCategory.PLAYERS, 7f, 1.6f);
        }

        for (int r = 0; r < STEPS_PER_TICK; r++) {
            murasakiLocation.add(direction.clone().multiply(STEP_SIZE));

            float explodeSize = (float)(Math.pow(power, 0.5) + 1);
            murasakiLocation.createExplosion(explodeSize, false, true);

            double searchR = 1 + Math.pow(power, 0.5);
            List<Entity> nearby = (List<Entity>) murasakiLocation.getNearbyEntities(
                    searchR, searchR, searchR);
            for (Entity e : nearby) {
                if (e.equals(p)) continue;
                if (hitEntities.contains(e.getUniqueId())) continue;
                if (murasakiLocation.distance(e.getLocation())
                        <= searchR + e.getHeight() + e.getWidth()) {
                    hitEntities.add(e.getUniqueId());
                    if (e instanceof LivingEntity living) {
                        applyDamage(living, Math.pow(power, 1.3));
                    }
                }
            }
        }

        Particle.DustOptions dust = new Particle.DustOptions(
                Color.PURPLE, (float)(Math.pow(power, 0.9) / 17 + 0.5));
        murasakiLocation.getWorld().spawnParticle(Particle.DUST, murasakiLocation,
                (int)Math.pow(power, 0.5) + 5,
                Math.log(power + 1) / 3, Math.log(power + 1) / 3, Math.log(power + 1) / 3,
                0.5, dust, true);

        if (activeTick % 4 == 0) {
            JPacketSender.broadcastInfinityMurasakiSync(
                    murasakiLocation, powerToStrength(power / 2), uniqueId, VISUAL_RANGE);
        }

        if (activeTick % 2 == 0) {
            murasakiLocation.getWorld().playSound(murasakiLocation, Sound.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.BLOCKS, (float)(5 + power * 0.1), 0.5f);
        }

        if (activeTick >= lifeTime) end();
    }

    // ── 무제한 murasaki (Fibonacci 구면 에너지파 폭발) ────────────────────

    private void tickUnlimited(Player p) {

        // ── 첫 틱: 에너지 그리드 초기화 + START_EXPLODE 패킷 ────────────────
        if (me_tick == 0) {
            energyGrid   = new float[FIB_N];
            energyBuffer = new float[FIB_N];
            float baseEnergy = (float)(Math.pow(Math.max(0, power - 19), 1.1) * 1.5);
            Arrays.fill(energyGrid, baseEnergy);

            JPacketSender.broadcastInfinityMurasakiStartExplode(
                    murasakiLocation, 0.1f, uniqueId, VISUAL_RANGE);
            packetActive = true;
            p.getWorld().playSound(murasakiLocation, Sound.ITEM_TRIDENT_THUNDER,
                    SoundCategory.PLAYERS, 7f, 1.7f);
            p.getWorld().playSound(murasakiLocation, Sound.BLOCK_END_PORTAL_SPAWN,
                    SoundCategory.PLAYERS, 7f, 1.4f);
            // 시전자 자해 데미지
            applyDamage(p, Math.pow(power, 0.7));
        }
        me_tick++;

        // ── 2틱마다 엔티티 데미지 (방향별 에너지 반영) ───────────────────
        if (me_tick % 2 == 0) {
            List<Entity> targets = (List<Entity>) murasakiLocation.getNearbyEntities(
                    me_cr + 2, me_cr + 2, me_cr + 2);
            for (Entity tentity : targets) {
                if (tentity.equals(p)) continue;
                if (hitEntitiesUnlimited.contains(tentity.getUniqueId())) continue;

                Vector d_vector = tentity.getLocation().toVector()
                        .subtract(murasakiLocation.toVector());
                double len = d_vector.length();
                if (len > me_cr + 2 + tentity.getHeight() + tentity.getWidth()) continue;

                hitEntitiesUnlimited.add(tentity.getUniqueId());
                if (tentity instanceof LivingEntity living) {
                    float energy = (len > 0.001)
                            ? energyGrid[fibNearest(
                                (float)(d_vector.getX() / len),
                                (float)(d_vector.getY() / len),
                                (float)(d_vector.getZ() / len))]
                            : energyGrid[0];
                    applyDamage(living, 10 + energy * 10);
                }
            }
        }

        // ── 종료 체크 ─────────────────────────────────────────────────────
        if (me_cr > Math.min(power, 100)) { end(); return; }

        // ── 셸 4칸 확장 (틱당 4 radius step) ────────────────────────────
        for (int r = 0; r < 4; r++) {
            me_cr++;
            int[][] surface = getSphereSurface(me_cr);

            for (int[] offset : surface) {
                float len = (float)Math.sqrt(
                        offset[0]*offset[0] + offset[1]*offset[1] + offset[2]*offset[2]);
                if (len < 0.001f) continue;

                float dx = offset[0] / len;
                float dy = offset[1] / len;
                float dz = offset[2] / len;

                // band-Gaussian 블렌딩으로 방향 에너지 조회
                int approxI = (int)((1.0f - dy) * (FIB_N - 1) * 0.5f);
                approxI = Math.max(0, Math.min(FIB_N - 1, approxI));
                int band = (int)Math.sqrt(FIB_N) + 11;
                int lo   = Math.max(0, approxI - band);
                int hi   = Math.min(FIB_N - 1, approxI + band);
                int bSz  = hi - lo + 1;

                float dirEnergy = 0f, totalW = 0f;
                for (int ki = 0; ki < bSz; ki++) {
                    float dot = dx*FIB_X[lo+ki] + dy*FIB_Y[lo+ki] + dz*FIB_Z[lo+ki];
                    float w = Math.max(0f, dot - BLEND_THRESHOLD); w *= w;
                    _bandW[ki] = w;
                    dirEnergy += energyGrid[lo+ki] * w;
                    totalW    += w;
                }
                if (totalW <= 0f) continue;
                dirEnergy /= totalW;
                dirEnergy += (float)(Math.random() - 0.5) * 0.3f;
                if (dirEnergy <= 0f) continue;

                Location blockLoc = murasakiLocation.clone()
                        .add(offset[0], offset[1], offset[2]);
                float blockHardness = tryBreakBlock(blockLoc, (int)dirEnergy);
                if (blockHardness <= 0f) continue;

                // 블록 경도²에 비례한 에너지 감소
                float energyLoss = (float)Math.max(Math.pow(blockHardness, 2.0), 0.001);
                for (int ki = 0; ki < bSz; ki++) {
                    if (_bandW[ki] > 0f)
                        energyGrid[lo+ki] = Math.max(0f,
                                energyGrid[lo+ki] - energyLoss * _bandW[ki] / totalW);
                }
            }

            // 확산: 장애물 옆으로 에너지가 새어나오는 회절 효과
            diffuseEnergy(0.08f);

            // 감쇠
            for (int i = 0; i < FIB_N; i++) {
                energyGrid[i] *= 0.97f;
            }
        }

        // SYNC_RADIUS 패킷
        JPacketSender.broadcastInfinityMurasakiSyncRadius(
                murasakiLocation, me_cr, uniqueId, VISUAL_RANGE);
    }

    // ── Fibonacci 유틸 ────────────────────────────────────────────────────

    /** 에너지 확산 (double-buffer): 각 셀의 8%를 6 이웃에 고르게 분배. */
    private void diffuseEnergy(float spread) {
        Arrays.fill(energyBuffer, 0f);
        for (int i = 0; i < FIB_N; i++) {
            float e = energyGrid[i];
            if (e <= 0f) continue;
            float diffused = e * spread;
            energyBuffer[i] += e - diffused;
            float share = diffused / FIB_NEIGHBORS[i].length;
            for (int j : FIB_NEIGHBORS[i]) energyBuffer[j] += share;
        }
        float[] tmp = energyGrid; energyGrid = energyBuffer; energyBuffer = tmp;
    }

    /** 방향 (dx,dy,dz) 에 가장 가까운 Fibonacci 셀 인덱스. */
    private int fibNearest(float dx, float dy, float dz) {
        int approxI = (int)((1.0f - dy) * (FIB_N - 1) * 0.5f);
        approxI = Math.max(0, Math.min(FIB_N - 1, approxI));
        int band = (int)Math.sqrt(FIB_N) + 11;
        int lo = Math.max(0, approxI - band), hi = Math.min(FIB_N - 1, approxI + band);
        int best = approxI; float bestDot = -2f;
        for (int k = lo; k <= hi; k++) {
            float dot = dx*FIB_X[k] + dy*FIB_Y[k] + dz*FIB_Z[k];
            if (dot > bestDot) { bestDot = dot; best = k; }
        }
        return best;
    }

    /**
     * 블록 파괴 시도. 빈 블록이면 0 반환 (에너지 손실 없음).
     * 블록이 있으면 제거하고 hardness 반환 (에너지 손실 계산에 사용).
     */
    private float tryBreakBlock(Location loc, int energy) {
        var block = loc.getBlock();
        if (block.isEmpty()) return 0f;
        if (block.isLiquid()) {
            block.setType(Material.AIR);
            return 0.3f;
        }
        float h = block.getType().getHardness();
        if (h < 0) return 50f;  // 깨지지 않는 블록 (베드락 등) → 에너지 완전 차단
        block.setType(Material.AIR);
        return Math.max(h, 0.1f);
    }

    // ── 데미지 ────────────────────────────────────────────────────────────

    private void applyDamage(LivingEntity living, double output) {
        JEntity targetEntity = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        if (targetEntity != null) {
            targetEntity.receiveDamage(
                    DamageInfo.skillHit(caster, DamageType.CURSED, output*100, "infinity_murasaki"));
        } else {
            living.damage(DamageInfo.outputToDamage(output*100));
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    private float powerToStrength(double power) {
        return (float)(power * 0.049 + 0.051);
    }

    @Override
    public float getGaugePercent() { return 0f; }
}
