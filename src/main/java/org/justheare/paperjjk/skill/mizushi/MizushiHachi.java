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
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private static final int BFS_PER_TICK    = 15;  // 틱당 BFS 탐색 수
    private static final int EFFECT_PER_TICK = 4;   // 틱당 효과 처리 수
    private static final int EFFECT_DELAY    = 10;  // 효과 시작까지 딜레이 (0.5초)

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
    private final Deque<long[]> bfsQueue    = new ArrayDeque<>();
    private final Set<Long>     bfsVisited  = new HashSet<>();
    private final List<long[]>  surface     = new ArrayList<>();
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

        chargeBufferMax = caster.cursedEnergy.getMaxOutput(1.0) * 40.0;

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
        if (!startAir.isEmpty()) return false;

        expWorld    = block.getWorld();
        maxSurface  = Math.max(3, (int)(storedPower * 5));

        int sx = startAir.getX(), sy = startAir.getY(), sz = startAir.getZ();
        bfsVisited.add(blockKey(sx, sy, sz));
        bfsQueue.add(new long[]{sx, sy, sz});
        if (hasSolidFaceNeighbor(sx, sy, sz)) surface.add(new long[]{sx, sy, sz});

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

        // BFS 탐색
        for (int n = 0; n < BFS_PER_TICK && !bfsQueue.isEmpty() && surface.size() < maxSurface; n++) {
            long[] pos = bfsQueue.pollFirst();
            int cx = (int)pos[0], cy = (int)pos[1], cz = (int)pos[2];

            int start = ThreadLocalRandom.current().nextInt(26);
            for (int i = 0; i < 26 && surface.size() < maxSurface; i++) {
                int[] nb = NB26[(start + i) % 26];
                int nx = cx + nb[0], ny = cy + nb[1], nz = cz + nb[2];
                long key = blockKey(nx, ny, nz);
                if (!bfsVisited.add(key)) continue;
                Block b = expWorld.getBlockAt(nx, ny, nz);
                if (b.isEmpty() && hasSolidFaceNeighbor(nx, ny, nz)) {
                    surface.add(new long[]{nx, ny, nz});
                    bfsQueue.add(new long[]{nx, ny, nz});
                }
            }
        }

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
                e.setVelocity(dir.normalize().multiply(0.5 + storedPower * 0.02));
            }
            applyExpansionDamage(living);
        }

        // 파티클
        expWorld.spawnParticle(Particle.SMALL_FLAME, center, 3, 0.2, 0.2, 0.2, 0.01);
    }

    private void applyExpansionDamage(LivingEntity living) {
        double output = storedPower * 3;
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
