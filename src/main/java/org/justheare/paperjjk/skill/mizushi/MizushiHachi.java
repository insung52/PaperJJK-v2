package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 어주자 스킬 2 — 팔(hachi).
 *
 * [엔티티 모드]
 *   키 홀드 → CHARGING, 키 뗌 → ACTIVE (5초 대기)
 *   접촉/공격 감지 → 0.4초 딜레이 후 HachiStrike 1회 → 종료
 *
 * [술식 확장 : 블록 모드]
 *   ACTIVE 중 블록 좌클릭 → 블록 표면 BFS 탐색 → 0.5초 후 순차 효과 발동
 *   효과: 표면 공기 블록 위치에서 인접 솔리드 파괴 + 주변 엔티티 넉백/데미지
 */
public class MizushiHachi extends ActiveSkill {

    // ── 엔티티 모드 상수 ──────────────────────────────────────────────────

    private static final double POWER_SCALE     = 500.0;
    private static final int    CONTACT_TIMEOUT = 100;   // 5초
    private static final int    DAMAGE_DELAY    = 8;     // 0.4초
    private static final double CONTACT_RADIUS  = 2.0;

    private static final Particle.DustOptions DUST_CHARGE =
            new Particle.DustOptions(Color.fromRGB(180, 0, 60), 0.4f);

    // ── 블록 확장 상수 ────────────────────────────────────────────────────

    private static final int   BFS_PER_TICK  = 150;    // 틱당 BFS 탐색 수
    private static final int   EFFECT_PER_TICK = 60;  // 틱당 효과 처리 수
    private static final int   EFFECT_DELAY  = 10;   // 효과 시작까지 딜레이 (0.5초)
    private static final float BRANCH_PROB     = 0.15f; // 브랜치 발생 확률
    private static final float SKIP_PROB       = 0.20f; // 균열이 그 자리에서 소멸할 확률
    private static final int   INITIAL_BRANCHES = 7;   // 시작 균열 개수 (1 = 방향 1개만)

    /** 6방향 (솔리드 이웃 체크 + 인접 블록 파괴) */
    private static final int[] DX6 = { 1,-1,0,0,0,0 };
    private static final int[] DY6 = { 0,0,1,-1,0,0 };
    private static final int[] DZ6 = { 0,0,0,0,1,-1 };

    /** 26방향 (3x3x3 BFS 확산) */
    private static final int[][] NB26 = new int[26][3];
    static {
        int i = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        NB26[i++] = new int[]{dx, dy, dz};
    }

    // ── 엔티티 모드 상태 ──────────────────────────────────────────────────

    private double       storedPower     = 0;
    private int          timeoutTick     = 0;
    private int          damageCountdown = -1;  // -1 = 미접촉
    private LivingEntity contactTarget   = null;

    // ── 블록 확장 상태 ────────────────────────────────────────────────────

    private boolean          blockMode    = false;
    private int              blockTick    = 0;
    private int              maxSurface;
    private org.bukkit.World expWorld;
    private float[]          expFaceNorm  = new float[3]; // 면 법선 (드리프트 회전축)
    private final Deque<long[]> bfsQueue = new ArrayDeque<>();
    private final List<long[]>  surface  = new ArrayList<>();
    private int                 effectIdx   = 0;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public MizushiHachi(JEntity caster) {
        super(caster);
    }

    @Override
    public void startRecharging() { /* 재충전 없음 */ }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        chargeBufferMax = caster.cursedEnergy.getMaxOutput(1.0) * 80.0;

        Location eye = p.getEyeLocation();
        p.getWorld().spawnParticle(Particle.DUST,
                eye.clone().add(
                        (Math.random() - 0.5) * 0.4,
                        (Math.random() - 0.5) * 0.4,
                        (Math.random() - 0.5) * 0.4),
                1, 0, 0, 0, 0, DUST_CHARGE, true);
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        if (chargeBuffer <= 0) { end(); return; }
        double efficiency = 1.0 + caster.cursedEnergy.getEfficiencyLevel() * 0.01;
        storedPower = chargeBuffer * efficiency / POWER_SCALE;
        timeoutTick = 0;

        if (caster instanceof JPlayer jp) {
            jp.player.playSound(jp.player.getLocation(),
                    Sound.ITEM_SPEAR_LUNGE_3, SoundCategory.PLAYERS, 1f, 0.6f);
        }
    }

    // ── 발동 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // [엔티티] 딜레이 카운트다운
        if (damageCountdown > 0) {
            damageCountdown--;
            if (damageCountdown == 0) { applyStrike(); end(); }
            return;
        }

        // [블록] 블록 확장 모드
        if (blockMode) {
            tickBlockMode();
            return;
        }

        // [엔티티] 타임아웃
        timeoutTick++;
        if (timeoutTick >= CONTACT_TIMEOUT) { end(); return; }

        // [엔티티] 근접 접촉 감지
        Location center = p.getLocation().add(0, p.getHeight() / 2.0, 0);
        List<LivingEntity> nearby = (List<LivingEntity>) center.getNearbyLivingEntities(
                CONTACT_RADIUS, CONTACT_RADIUS, CONTACT_RADIUS);
        for (LivingEntity entity : nearby) {
            if (entity.equals(p)) continue;
            triggerContact(entity);
            break;
        }
    }

    @Override
    protected void onEnd() {
        if (contactTarget == null && !blockMode && caster instanceof JPlayer jp) {
            jp.player.playSound(jp.player.getLocation(),
                    Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 0.1f, 1.5f);
        }
    }

    // ── 엔티티 모드 공통 ──────────────────────────────────────────────────

    private void triggerContact(LivingEntity target) {
        if (damageCountdown != -1 || blockMode) return;
        contactTarget   = target;
        damageCountdown = DAMAGE_DELAY;
        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 0.5f);
        }
    }

    private void applyStrike() {
        if (contactTarget == null || !contactTarget.isValid()) return;
        Location hitLoc = contactTarget.getLocation().add(0, contactTarget.getHeight() / 2.0, 0);
        HachiStrike.apply(caster, contactTarget, hitLoc, storedPower);
        contactTarget.addScoreboardTag("hachi");
    }

    @Override
    public void onAttackLanded(LivingEntity target) {
        if (!isActive()) return;
        triggerContact(target);
    }

    // ── 블록 확장 모드 ────────────────────────────────────────────────────

    @Override
    public boolean onLeftClickBlock(Block block, BlockFace face) {
        if (!isActive() || blockMode) return false;
        if (block.isEmpty() || block.getType() == Material.BARRIER) return false;
        float hardness = block.getType().getHardness();
        if (hardness < 0) return false;  // 파괴 불가 블록 (bedrock 등)

        Block startAir = block.getRelative(face);
        if (!startAir.isEmpty() && !startAir.isLiquid()) return false;

        expWorld    = block.getWorld();
        maxSurface  = Math.max(3, (int)(storedPower*0.7));

        // 초기 균열 방향: 플레이어 시선을 면의 법선에 수직인 평면으로 투영
        int sx = startAir.getX(), sy = startAir.getY(), sz = startAir.getZ();
        Vector faceNorm = face.getDirection().clone().normalize();
        expFaceNorm[0] = (float)faceNorm.getX();
        expFaceNorm[1] = (float)faceNorm.getY();
        expFaceNorm[2] = (float)faceNorm.getZ();
        Vector look = ((JPlayer)caster).player.getEyeLocation().getDirection();
        Vector tangent = look.clone().subtract(faceNorm.clone().multiply(look.dot(faceNorm)));
        if (tangent.lengthSquared() < 0.001) tangent = randomPerpendicular(faceNorm);
        tangent.normalize();

        if (hasSolidFaceNeighbor(sx, sy, sz)) surface.add(new long[]{sx, sy, sz});

        // 초기 균열: 면 평면 위에서 INITIAL_BRANCHES 개를 등각도로 배치
        // 각 방향에 5~40도 랜덤 회전을 더해 격자 정렬 방지
        // (dir 이 faceNorm 에 수직이므로 Rodrigues 식: v_rot = v·cosθ + (n×v)·sinθ)
        Vector bitangent = faceNorm.clone().crossProduct(tangent).normalize();
        double step = 2 * Math.PI / INITIAL_BRANCHES;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int b = 0; b < INITIAL_BRANCHES; b++) {
            double base  = step * b;
            double jitter = (rng.nextDouble() * 35 + 5) * Math.PI / 180.0;
            if (rng.nextBoolean()) jitter = -jitter;
            double angle = base + jitter;
            Vector dir = tangent.clone().multiply(Math.cos(angle))
                    .add(bitangent.clone().multiply(Math.sin(angle)));
            bfsQueue.add(packNode(sx, sy, sz,
                    (float)dir.getX(), (float)dir.getY(), (float)dir.getZ()));
        }

        blockMode = true;
        blockTick = 0;

        if (caster instanceof JPlayer jp) {
            jp.player.getWorld().playSound(jp.player.getLocation(),
                    Sound.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.6f, 1.8f);
        }
        return true;
    }

    private void tickBlockMode() {
        blockTick++;

        // BFS 탐색 (방향성 균열 브랜칭)
        for (int n = 0; n < BFS_PER_TICK && !bfsQueue.isEmpty() && surface.size() < maxSurface; n++) {
            long[] node = bfsQueue.pollFirst();
            int cx = (int)node[0], cy = (int)node[1], cz = (int)node[2];
            float dx = Float.intBitsToFloat((int)node[3]);
            float dy = Float.intBitsToFloat((int)node[4]);
            float dz = Float.intBitsToFloat((int)node[5]);

            ThreadLocalRandom rng = ThreadLocalRandom.current();

            // SKIP_PROB: 이번 틱 전이 건너뜀, 다음 틱에 재시도 (균열 길이 자연 편차)
            if (rng.nextFloat() < SKIP_PROB) { bfsQueue.addLast(node); continue; }

            // 1. 현재 방향에 가장 정렬된 유효 이웃 탐색 (균열 진행)
            int bestIdx = -1;
            float bestDot = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 26; i++) {
                int[] nb = NB26[i];
                int nx = cx + nb[0], ny = cy + nb[1], nz = cz + nb[2];
                Block bnb = expWorld.getBlockAt(nx, ny, nz);
                if (!bnb.isEmpty() && !bnb.isLiquid()) continue;
                if (!hasSolidFaceNeighbor(nx, ny, nz)) continue;
                float len = (float)Math.sqrt(nb[0]*nb[0] + nb[1]*nb[1] + nb[2]*nb[2]);
                float dot = (nb[0]*dx + nb[1]*dy + nb[2]*dz) / len;
                if (dot > bestDot) { bestDot = dot; bestIdx = i; }
            }

            boolean branching = surface.size() < maxSurface && rng.nextFloat() < BRANCH_PROB;

            if (bestIdx >= 0) {
                int[] nb = NB26[bestIdx];
                int nx = cx + nb[0], ny = cy + nb[1], nz = cz + nb[2];
                surface.add(new long[]{nx, ny, nz});

                // 이웃 방향으로 살짝 끌어당겨 방향 갱신
                float len = (float)Math.sqrt(nb[0]*nb[0] + nb[1]*nb[1] + nb[2]*nb[2]);
                float ndx = dx + nb[0]/len * 0.3f, ndy = dy + nb[1]/len * 0.3f, ndz = dz + nb[2]/len * 0.3f;
                float nlen = (float)Math.sqrt(ndx*ndx + ndy*ndy + ndz*ndz);
                if (nlen > 0.001f) { ndx /= nlen; ndy /= nlen; ndz /= nlen; }

                // 브랜치 발생 시 50% 확률로 직진 방향도 ±30도 드리프트
                if (branching && rng.nextBoolean()) {
                    float theta = (float)((rng.nextDouble() * 30.0) * Math.PI / 180.0);
                    if (rng.nextBoolean()) theta = -theta;
                    float fnx = expFaceNorm[0], fny = expFaceNorm[1], fnz = expFaceNorm[2];
                    // Rodrigues (dir ⊥ faceNorm): v_rot = v·cosθ + (n×v)·sinθ
                    float crsX = fny*ndz - fnz*ndy, crsY = fnz*ndx - fnx*ndz, crsZ = fnx*ndy - fny*ndx;
                    float cosT = (float)Math.cos(theta), sinT = (float)Math.sin(theta);
                    ndx = ndx*cosT + crsX*sinT;
                    ndy = ndy*cosT + crsY*sinT;
                    ndz = ndz*cosT + crsZ*sinT;
                    float rlen = (float)Math.sqrt(ndx*ndx + ndy*ndy + ndz*ndz);
                    if (rlen > 0.001f) { ndx /= rlen; ndy /= rlen; ndz /= rlen; }
                }

                bfsQueue.addLast(packNode(nx, ny, nz, ndx, ndy, ndz));
            }

            // 2. 브랜치 (BRANCH_PROB 확률로 랜덤 방향 새 균열 생성)
            if (branching) {
                int start = rng.nextInt(26);
                for (int i = 0; i < 26; i++) {
                    int[] nb = NB26[(start + i) % 26];
                    int nx = cx + nb[0], ny = cy + nb[1], nz = cz + nb[2];
                    long key = blockKey(nx, ny, nz);
                    Block bnb2 = expWorld.getBlockAt(nx, ny, nz);
                    if (!bnb2.isEmpty() && !bnb2.isLiquid()) continue;
                    if (!hasSolidFaceNeighbor(nx, ny, nz)) continue;
                    surface.add(new long[]{nx, ny, nz});
                    float len = (float)Math.sqrt(nb[0]*nb[0] + nb[1]*nb[1] + nb[2]*nb[2]);
                    bfsQueue.addLast(packNode(nx, ny, nz, nb[0]/len, nb[1]/len, nb[2]/len));
                    break;
                }
            }
        }

        // maxSurface 도달 시 큐 잔여 항목 제거 (미제거 시 isEmpty() 가 영원히 false)
        if (surface.size() >= maxSurface) bfsQueue.clear();

        // 효과 페이즈 (0.5초 딜레이 후)
        if (blockTick >= EFFECT_DELAY) {
            for (int n = 0; n < EFFECT_PER_TICK && effectIdx < surface.size(); n++, effectIdx++) {
                applyExpansionEffect(surface.get(effectIdx));
            }
            if (effectIdx >= surface.size() && bfsQueue.isEmpty()) end();
        }
    }

    private void applyExpansionEffect(long[] pos) {
        int x = (int)pos[0], y = (int)pos[1], z = (int)pos[2];

        // 인접 솔리드 블록 파괴 (충전량 vs 경도 기반 확률)
        for (int i = 0; i < 6; i++) {
            Block b = expWorld.getBlockAt(x + DX6[i], y + DY6[i], z + DZ6[i]);
            if (b.isEmpty() || b.isLiquid()) continue;
            float h = b.getType().getHardness();
            if (h < 0) continue;
            double prob = Math.min(1.0, storedPower * 0.5 / Math.max(0.1, h));
            if (ThreadLocalRandom.current().nextDouble() < prob) b.setType(Material.AIR);
            if(Math.random()<0.1){
                expWorld.createExplosion(caster.entity, new Location(expWorld,x + DX6[i], y + DY6[i], z + DZ6[i]),1,false);
            }
        }

        // 주변 2블럭 내 엔티티 넉백 + 데미지
        Location center = new Location(expWorld, x + 0.5, y + 0.5, z + 0.5);
        for (Entity e : center.getNearbyEntities(2.0, 2.0, 2.0)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (e.equals(caster.getLivingEntity())) continue;

            Vector dir = e.getLocation().add(0, e.getHeight() / 2.0, 0)
                    .toVector().subtract(center.toVector());
            if (dir.length() > 0.01) {
                e.setVelocity(dir.normalize().multiply(0.34 + Math.pow(storedPower,0.14) * 0.4));
            }
            applyExpansionDamage(living);
        }

        // 파티클
        //expWorld.spawnParticle(Particle.SMALL_FLAME, center, 3, 0.2, 0.2, 0.2, 0.01);
    }

    private void applyExpansionDamage(LivingEntity living) {
        double output = storedPower * 0.04;
        JEntity targetJE = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        if (targetJE != null) {
            targetJE.receiveDamage(
                    DamageInfo.skillHit(caster, DamageType.CURSED, output, "hachi_expansion"));
        } else {
            JEntityManager.skillDamageInProgress.add(living.getUniqueId());
            try {
                living.damage(DamageInfo.outputToDamage(output), caster.getLivingEntity());
            } finally {
                JEntityManager.skillDamageInProgress.remove(living.getUniqueId());
            }
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    private boolean hasSolidFaceNeighbor(int x, int y, int z) {
        for (int i = 0; i < 6; i++) {
            Block b = expWorld.getBlockAt(x + DX6[i], y + DY6[i], z + DZ6[i]);
            if (!b.isEmpty() && !b.isLiquid()) return true;
        }
        return false;
    }

    /** BFS 큐 노드 패킹: {x, y, z, dx_bits, dy_bits, dz_bits} */
    private static long[] packNode(int x, int y, int z, float dx, float dy, float dz) {
        return new long[]{x, y, z,
                Float.floatToRawIntBits(dx),
                Float.floatToRawIntBits(dy),
                Float.floatToRawIntBits(dz)};
    }

    /** 법선 벡터에 수직인 임의 방향 반환 */
    private static Vector randomPerpendicular(Vector normal) {
        Vector arbitrary = Math.abs(normal.getX()) < 0.9
                ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        return arbitrary.subtract(normal.clone().multiply(arbitrary.dot(normal))).normalize();
    }

    private static long blockKey(int x, int y, int z) {
        return ((long)(x & 0x3FFFFFF) << 38) | ((long)(y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    // ── HUD ───────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        double cap = chargeBufferMax > 0 ? chargeBufferMax : 1;
        return switch (getPhase()) {
            case CHARGING -> (float) Math.min(1.0, chargeBuffer / cap);
            case ACTIVE   -> blockMode
                    ? (float) Math.min(1.0, (double) effectIdx / Math.max(1, surface.size()))
                    : (float) Math.max(0.0, 1.0 - (double) timeoutTick / CONTACT_TIMEOUT);
            case ENDED    -> 0.0f;
        };
    }

    @Override
    public byte getSlotGaugeState() {
        return switch (phase) {
            case CHARGING -> PacketIds.SlotGaugeState.CHARGING;
            case ACTIVE   -> PacketIds.SlotGaugeState.ACTIVE;
            case ENDED    -> PacketIds.SlotGaugeState.NONE;
        };
    }

    // ── MizushiTechnique.defend() 호환 ────────────────────────────────────

    public double getPower()            { return storedPower; }
    public void reducePower(double amt) { storedPower = Math.max(0, storedPower - amt); }
}
