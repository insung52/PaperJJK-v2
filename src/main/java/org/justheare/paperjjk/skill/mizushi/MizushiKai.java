package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 어주자 스킬 1 — 해(kai).
 *
 * 바라보는 방향으로 투명한 참격을 날린다.
 * 진행 방향 = 시선 방향, 베는 방향(cut axis) = 충전 중 시점 이동 벡터 투영.
 * 충전량에 비례해 위력·너비 증가. 블록 파괴·엔티티 피격 시 파워 감소.
 */
public class MizushiKai extends ActiveSkill {

    private static final Particle.DustOptions DUST_HIT =
            new Particle.DustOptions(Color.fromRGB(60, 0, 0), 1f);
    private static final Particle.DustOptions DUST_CHARGE =
            new Particle.DustOptions(Color.RED, 0.3f);

    /** 100틱 충전 = 최대 파워 */
    private static final int    MAX_CHARGE_TICKS  = 100;
    private static final int    STEPS_PER_TICK    = 10;
    private static final double STEP_DIST         = 0.4;
    private static final int    MAX_ACTIVE_TICKS  = 18;

    // ── 충전 추적 (게이지용) ──────────────────────────────────────────────

    /** 충전 경과 틱 (getGaugePercent 에서 사용) */
    private int chargeTick = 0;

    // ── 발동 상태 ─────────────────────────────────────────────────────────

    private Location fireLocation;
    private Vector   fireDirection;
    private Vector   cutAxis;

    private float prevYaw;
    private float prevPitch;

    private double power;

    private Location currentPos;
    private final Set<UUID> hitEntities = new HashSet<>();

    private int activeTick = 0;
    private boolean soundPlayed = false;

    public MizushiKai(JEntity caster) {
        super(caster, 4.0);
    }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) return;
        Player p = jp.player;

        chargeTick++;
        prevYaw   = p.getEyeLocation().getYaw();
        prevPitch = p.getEyeLocation().getPitch();

        Location eye = p.getEyeLocation();
        for (int i = 0; i < 3; i++) {
            p.getWorld().spawnParticle(Particle.DUST,
                    eye.clone().add(
                            (Math.random() - 0.5) * 0.5,
                            (Math.random() - 0.5) * 0.5,
                            (Math.random() - 0.5) * 0.5),
                    1, 0, 0, 0, 0, DUST_CHARGE, true);
        }
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // chargedOutput: CE 누적량. 게이지 비율과 같은 방향으로 파워 환산
        power = Math.max(5, (double) chargeTick / MAX_CHARGE_TICKS * 100.0);

        fireLocation  = p.getEyeLocation().clone();
        fireDirection = fireLocation.getDirection().normalize();
        currentPos    = fireLocation.clone();

        // cut axis 계산
        Location nowLoc = p.getEyeLocation();
        float dyaw   = nowLoc.getYaw()   - prevYaw;
        float dpitch = nowLoc.getPitch() - prevPitch;

        if (Math.abs(dyaw) > 2f || Math.abs(dpitch) > 2f) {
            Location rightLoc = fireLocation.clone();
            rightLoc.setPitch(0);
            rightLoc.setYaw(fireLocation.getYaw() + 90);
            Vector right = rightLoc.getDirection().normalize();
            Vector head  = right.clone().crossProduct(fireDirection).normalize();
            cutAxis = right.clone().multiply(dyaw)
                          .add(head.clone().multiply(-dpitch))
                          .normalize();
        } else {
            cutAxis = randomPerpendicular(fireDirection);
        }
    }

    // ── 발동 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        if (power <= 0 || activeTick >= MAX_ACTIVE_TICKS) { end(); return; }
        activeTick++;

        double halfWidth = (power / 8.0) * ((double)(activeTick + 1) / 7.0) + 1.0;

        if (!soundPlayed) {
            soundPlayed = true;
            p.getWorld().playSound(currentPos, Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                    SoundCategory.PLAYERS, 3f, 0.8f);
        }

        for (int step = 0; step < STEPS_PER_TICK; step++) {
            currentPos.add(fireDirection.clone().multiply(STEP_DIST));
            if (power <= 0) break;

            for (double r = -halfWidth; r <= halfWidth; r += 0.5) {
                Location sliceLoc = currentPos.clone().add(cutAxis.clone().multiply(r));

                // 엔티티 피격
                List<Entity> nearby = (List<Entity>) sliceLoc.getNearbyEntities(0.9, 0.9, 0.9);
                nearby.remove(p);
                for (Entity e : nearby) {
                    if (hitEntities.contains(e.getUniqueId())) continue;
                    hitEntities.add(e.getUniqueId());
                    if (e instanceof LivingEntity living) {
                        applyKaiDamage(living);
                        living.addScoreboardTag("kai");
                        e.setVelocity(e.getVelocity().add(fireDirection.clone().multiply(0.1)));
                        power -= Math.max(1, power * 0.1);
                        p.getWorld().playSound(sliceLoc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK,
                                SoundCategory.PLAYERS, 4f, 0.6f);
                        if (power <= 0) break;
                    }
                }

                // 블록 파괴
                Block blk = sliceLoc.getBlock();
                if (!blk.isEmpty() && !blk.isLiquid()) {
                    float h = blk.getType().getHardness();
                    if (h >= 0 && h < power) {
                        queueBreak(sliceLoc);
                        power -= Math.pow(h, 1.3) / 5.0;
                        if (Math.random() > 0.9) {
                            p.getWorld().playSound(sliceLoc,
                                    Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,
                                    SoundCategory.PLAYERS, 0.2f, 0.6f);
                        }
                        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, sliceLoc,
                                1, 0, 0, 0, 0, null, true);
                        p.getWorld().spawnParticle(Particle.DUST, sliceLoc,
                                1, 0, 0, 0, 0, DUST_HIT, true);
                        p.getWorld().spawnParticle(Particle.BLOCK, sliceLoc,
                                5, 0.3, 0.3, 0.3, 1, blk.getBlockData(), false);
                    } else if (h >= 0) {
                        power -= Math.pow(h, 1.3) / 5.0;
                    }
                }
            }
        }
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────

    private void applyKaiDamage(LivingEntity living) {
        DamageInfo.setnodamagetick(living);
        JEntity target = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        double output = Math.pow(caster.cursedEnergy.getMax(), 0.11) + power - 3;
        if (target != null) {
            target.receiveDamage(DamageInfo.skillHit(caster, DamageType.CURSED,
                    output * 100, "mizushi_kai"));
        } else {
            living.damage(DamageInfo.outputToDamage(output * 100));
        }
    }

    private static Vector perpendicularTo(Vector v) {
        Vector ref = Math.abs(v.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        return v.clone().crossProduct(ref).normalize();
    }
    private static Vector randomPerpendicular(Vector v) {
        if (v.lengthSquared() == 0) {
            return new Vector(1, 0, 0);
        }

        Vector vNorm = v.clone().normalize();

        // 1️⃣ 기준 수직 벡터 하나 구하기
        Vector ref = Math.abs(vNorm.getY()) < 0.9
                ? new Vector(0, 1, 0)
                : new Vector(1, 0, 0);

        Vector a = vNorm.clone().crossProduct(ref).normalize();

        // 2️⃣ 두 번째 직교 벡터
        Vector b = vNorm.clone().crossProduct(a).normalize();

        // 3️⃣ 랜덤 각도
        double theta = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);

        // 4️⃣ 원 위에서 랜덤 방향
        return a.multiply(Math.cos(theta))
                .add(b.multiply(Math.sin(theta)))
                .normalize();
    }

    // ── HUD ──────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        return switch (getPhase()) {
            case CHARGING -> Math.min(1f, (float) chargeTick / MAX_CHARGE_TICKS);
            case ACTIVE   -> power > 0 ? (float)(power / 100.0) : 0f;
            case ENDED    -> 0f;
        };
    }
}
