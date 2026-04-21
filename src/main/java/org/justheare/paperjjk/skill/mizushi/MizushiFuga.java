package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.barrier.DomainManager;
import org.justheare.paperjjk.barrier.MizushiDomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.List;

/**
 * 어주자 스킬 3 — 조(fuga/kamino).
 *
 * 충전 후, 명중 시 폭발하는 화염탄을 날린다.
 * 최소 충전 20틱: 키를 일찍 놔도 20틱이 채워질 때까지 발사 대기.
 * 속도가 느려 회피 가능, 범위 좁음.
 * "kai" + "hachi" 태그가 붙은 대상에게 큰 추가 피해.
 */
public class MizushiFuga extends ActiveSkill {

    /** 최소 충전 틱 (이 전에 키를 놔도 20틱 채울 때까지 발사 대기) */
    private static final int    MIN_CHARGE_TICKS  = 20;
    /** 게이지 100% 기준 충전 틱 */
    private static final int    MAX_CHARGE_TICKS  = 100;
    /** 발사 후 최대 비행 틱 */
    private static final int    MAX_FLIGHT_TICKS  = 100;
    private static final int    STEPS_PER_TICK    = 5;
    private static final double STEP_DIST         = 1.5;

    // ── 충전 추적 ─────────────────────────────────────────────────────────

    /** fuga가 결없영에 명중해 열압력탄이 트리거되었으면 true. onEnd()에서 STOP 패킷 생략. */
    private boolean thermobaricTriggered = false;

    /** 충전 시작 시 참격 억제 + 블럭 파괴 중단을 이미 보냈으면 true (중복 방지). */
    private boolean chargeSuppressApplied = false;

    /** 충전 중 누적 틱 (최소 충전 판정 + 게이지용) */
    private int chargeTick = 0;

    // ── 발사 대기 (발사 전 부족한 충전 틱 보충) ──────────────────────────

    /** ACTIVE 진입 후 발사 대기 추가 틱 */
    private int waitTick    = 0;
    private boolean launched = false;

    // ── 탄환 ──────────────────────────────────────────────────────────────

    private Location projectilePos;
    private Vector   projectileDir;
    private int      flightTick = 0;

    public MizushiFuga(JEntity caster) {
        super(caster, 4.0);
    }

    // ── 충전 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) return;
        Player p = jp.player;

        chargeTick++;

        // 충전 첫 틱: 참격 억제 + 블럭 파괴 중단
        if (!chargeSuppressApplied) {
            chargeSuppressApplied = true;
            MizushiDomainExpansion openDomain = getCasterOpenDomain();
            if (openDomain != null) {
                JPacketSender.broadcastMizushiFugaCharge(p.getLocation(),
                        PacketIds.MizushiFugaAction.START,
                        DomainManager.BROADCAST_RANGE);
                openDomain.pauseBlockDestruction();
            }
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 1));

        Location eye = p.getEyeLocation();
        Location rightLoc = eye.clone();
        rightLoc.setPitch(0);
        rightLoc.setYaw(eye.getYaw() + 90);
        Vector right = rightLoc.getDirection().normalize();
        Vector head  = right.clone().crossProduct(eye.getDirection()).normalize();
        Vector rr    = eye.getDirection().clone().rotateAroundAxis(head, Math.PI / 2);
        Vector nVec  = head.clone().rotateAroundAxis(eye.getDirection(), -Math.PI / 6);
        Vector lVec  = eye.getDirection().clone().rotateAroundAxis(head, Math.PI / 6);

        if (Math.random() > 0.8) {
            p.getWorld().playSound(eye.clone().add(lVec), Sound.BLOCK_FIRE_AMBIENT,
                    SoundCategory.AMBIENT, 2f, 1f);
        }
        double power = Math.max(5, (double) chargeTick / MAX_CHARGE_TICKS * 100.0);
        for (double r = -5; r <= 5; r += 0.3) {
            if (Math.random() * 100 <= power) {
                p.getWorld().spawnParticle(Particle.FLAME,
                        eye.clone()
                           .add(rr.clone().multiply(-0.7))
                           .add(eye.getDirection().multiply(r / 2))
                           .add(head.clone().multiply(-0.6)),
                        1, 0.01, 0.01, 0.01, 0.001, null, true);
                p.getWorld().spawnParticle(Particle.FLAME,
                        eye.clone()
                           .add(lVec.clone().multiply(2.5))
                           .add(nVec.clone().multiply(r))
                           .add(lVec.clone().multiply(-Math.pow(r, 2) / 10)),
                        1, 0.04, 0.04, 0.04, 0.02, null, true);
            }
        }
    }

    // ── 충전 완료 ─────────────────────────────────────────────────────────

    @Override
    protected void onCharged() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        // 발사 위치 고정 (키 뗀 순간)
        projectilePos = jp.player.getEyeLocation().clone();
    }

    // ── 발동 중 ───────────────────────────────────────────────────────────

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        // 최소 충전 20틱 미달이면 대기 (충전 파티클 유지)
        if (chargeTick + waitTick < MIN_CHARGE_TICKS) {
            waitTick++;
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 1));
            // 충전 중 이펙트 유지 (발사 위치 계속 갱신)
            projectilePos = p.getEyeLocation().clone();
            return;
        }

        // 발사 (최초 1회)
        if (!launched) {
            launched = true;
            p.getWorld().playSound(projectilePos, Sound.ITEM_FIRECHARGE_USE,
                    SoundCategory.PLAYERS, 3f, 0.5f);
            projectileDir = projectilePos.getDirection().normalize().multiply(STEP_DIST);
        }

        // 비행
        flightTick++;
        if (flightTick >= MAX_FLIGHT_TICKS) { end(); return; }

        // 결없영 진입 체크 (비행 중 매 틱, 루프 밖에서 1회만 조회)
        MizushiDomainExpansion openDomain = getCasterOpenDomain();

        for (int step = 0; step < STEPS_PER_TICK; step++) {
            projectileDir.add(new Vector(0, -0.009, 0));
            projectilePos.add(projectileDir);

            double power = Math.max(5, (double) chargeTick / MAX_CHARGE_TICKS * 100.0);
            p.getWorld().spawnParticle(Particle.FLAME, projectilePos,
                    (int)(power / 10.0 + 1), 1, 1, 1, 0, null, true);

            // 결없영 범위 내 진입 즉시 폭발 (고정 중심 기준, 3블록 여유)
            if (openDomain != null) {
                Location domCenter = openDomain.getDomainCenter();
                if (domCenter != null
                        && domCenter.getWorld() == projectilePos.getWorld()
                        && domCenter.distance(projectilePos) <= openDomain.getRange() + 3.0) {
                    thermobaricTriggered = true;
                    openDomain.triggerFugaExplosion(projectilePos);
                    end();
                    return;
                }
            }

            if (projectilePos.getBlock().isSolid()) {
                hit(p);
                return;
            }
            List<Entity> nearby = (List<Entity>) projectilePos.getNearbyEntities(1, 1, 1);
            nearby.remove(p);
            if (!nearby.isEmpty()) {
                hit(p);
                return;
            }
        }
    }

    // ── 명중 처리 ─────────────────────────────────────────────────────────

    private void hit(Player casterPlayer) {
        // 결없영 내부 명중 시 열압력탄 폭발 (서버 로직은 MizushiDomainExpansion.triggerFugaExplosion 에서 처리)
        MizushiDomainExpansion openDomain = getCasterOpenDomain();
        if (openDomain != null) {
            Location domCenter = openDomain.getCaster().entity.getLocation();
            double distToDomain = domCenter.distance(projectilePos);
            if (distToDomain <= openDomain.getRange()) {
                thermobaricTriggered = true;
                openDomain.triggerFugaExplosion(projectilePos);
                end();
                return;
            }
        }

        double power = Math.max(5, (double) chargeTick / MAX_CHARGE_TICKS * 100.0);
        float explodeSize = (float)(power / 25.0 + 2);

        projectilePos.createExplosion(casterPlayer, explodeSize, true, true);

        double searchR = Math.pow(power, 0.3) + 1;
        List<Entity> targets = (List<Entity>) projectilePos.getNearbyEntities(searchR, searchR, searchR);
        targets.remove(casterPlayer);

        for (Entity e : targets) {
            if (e instanceof LivingEntity living) {
                boolean hasKai   = e.getScoreboardTags().contains("kai");
                boolean hasHachi = e.getScoreboardTags().contains("hachi");

                if (hasKai && hasHachi) {
                    DamageInfo.setnodamagetick(living);
                    applyFugaDamage(living, power * 4.0 / Math.max(1, targets.size()), true);
                    e.setFireTicks(4444);
                    projectilePos.getWorld().spawnParticle(Particle.FLAME, projectilePos,
                            (int)(power / 3 + 30), 1, 1, 1, 1, null, true);
                    projectilePos.getWorld().spawnParticle(Particle.LAVA, projectilePos,
                            (int)(power / 3 + 30), 1, 1, 1, 1, null, true);
                    projectilePos.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER,
                            projectilePos, 1, 0, 0, 0, 0, null, false);
                } else {
                    e.setFireTicks(40);
                }
            }
        }

        projectilePos.getWorld().playSound(projectilePos, Sound.ENTITY_GENERIC_EXPLODE,
                SoundCategory.PLAYERS, 4f, 0.7f);
        end();
    }

    private void applyFugaDamage(LivingEntity living, double output, boolean sureHit) {
        JEntity target = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        if (target != null) {
            target.receiveDamage(new DamageInfo(caster, DamageType.CURSED,
                    output * 100, "mizushi_fuga", sureHit, !sureHit));
        } else {
            living.damage(DamageInfo.outputToDamage(output * 100), caster.getLivingEntity());
        }
    }

    @Override
    protected void onEnd() {
        // 열압력탄이 트리거되지 않았다면 참격 복원 + 블럭 파괴 재개 (fuga 미스/만료)
        if (!thermobaricTriggered) {
            MizushiDomainExpansion openDomain = getCasterOpenDomain();
            if (openDomain != null) {
                openDomain.resumeBlockDestruction();
                if (caster instanceof JPlayer jp) {
                    JPacketSender.broadcastMizushiFugaCharge(jp.player.getLocation(),
                            PacketIds.MizushiFugaAction.STOP,
                            DomainManager.BROADCAST_RANGE);
                }
            }
        }
    }

    /** 시전자의 활성 결없영(isOpen=true) MizushiDomainExpansion 을 반환. 없으면 null. */
    private MizushiDomainExpansion getCasterOpenDomain() {
        if (DomainManager.instance == null) return null;
        for (var d : DomainManager.instance.getActiveDomains()) {
            if (d instanceof MizushiDomainExpansion mde
                    && mde.isOpen()
                    && mde.getCaster() == caster) {
                return mde;
            }
        }
        return null;
    }

    // ── HUD ──────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        return switch (getPhase()) {
            case CHARGING -> Math.min(1f, (float) chargeTick / MAX_CHARGE_TICKS);
            case ACTIVE -> {
                if (!launched) yield Math.min(1f, (float)(chargeTick + waitTick) / MIN_CHARGE_TICKS);
                yield 1f - (float) flightTick / MAX_FLIGHT_TICKS;
            }
            case ENDED -> 0f;
        };
    }
}
