package org.justheare.paperjjk.technique;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.MizushiInnateTerritory;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;
import org.justheare.paperjjk.skill.mizushi.MizushiHachi;

import java.util.Map;

/**
 * 어주자(Mizushi) 생득술식.
 *
 * 타격 시: 참격 추가 피해.
 * 스킬: 해(kai), 팔(hachi), 조(fuga)
 * 영역전개: 복마어주자
 */
public class MizushiTechnique extends Technique {

    private static final Particle.DustOptions DUST_SLASH =
            new Particle.DustOptions(Color.fromRGB(160, 0, 0), 1.0f);

    /** 신체강화 소모 비율 */
    private static final double BURN_RATIO     = 0.30;
    /** 술식 미타버린 상태에서의 추가 피해 배율 */
    private static final double TECHNIQUE_MULT = 2.5;

    public MizushiTechnique(JEntity owner) {
        super(owner);
    }

    // ── Technique 구현 ────────────────────────────────────────────────────

    @Override
    public void onAttack(JEntity target, DamageInfo damageInfo) {
        double consumed = consumeBodyRein();
        if (consumed <= 0) return;

        if (owner.isTechniqueBlocked()) {
            damageInfo.attackOutput += consumed;
        } else {
            damageInfo.attackOutput += consumed * TECHNIQUE_MULT;
            spawnMizushiHitEffect(target.entity);
        }
    }

    @Override
    public void onAttackMob(LivingEntity mob) {
        double consumed = consumeBodyRein();
        if (consumed <= 0) return;

        boolean burned = owner.isTechniqueBlocked();
        double bonus = burned ? consumed : consumed * TECHNIQUE_MULT;
        DamageInfo.setnodamagetick(mob);
        mob.damage(DamageInfo.outputToDamage(bonus));
        if (!burned) spawnMizushiHitEffect(mob);
    }

    private double consumeBodyRein() {
        double current = owner.bodyReinforcement.getCurrent();
        if (current <= 0) return 0;
        double consumed = current * BURN_RATIO;
        owner.bodyReinforcement.consume(consumed);
        return consumed;
    }

    private void spawnMizushiHitEffect(org.bukkit.entity.Entity targetEntity) {
        Location center = targetEntity.getLocation().add(0, targetEntity.getHeight() / 2, 0);
        World world = center.getWorld();
        if (world == null) return;

        // 시전자→대상 방향의 수직 벡터 = 참격 축
        Vector toTarget = center.toVector().subtract(owner.entity.getLocation().toVector());
        Vector slashAxis;
        if (toTarget.length() > 0.01) {
            Vector ref = Math.abs(toTarget.normalize().getY()) < 0.9
                    ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
            slashAxis = toTarget.normalize().crossProduct(ref).normalize();
        } else {
            slashAxis = new Vector(1, 0, 0);
        }

        // 참격 라인 파티클
        for (double r = -0.9; r <= 0.9; r += 0.18) {
            Location sliceLoc = center.clone().add(slashAxis.clone().multiply(r));
            world.spawnParticle(Particle.DUST, sliceLoc, 1, 0.03, 0.03, 0.03, 0, DUST_SLASH, true);
            world.spawnParticle(Particle.ELECTRIC_SPARK, sliceLoc, 1, 0, 0, 0, 0, null, true);
        }

        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                SoundCategory.PLAYERS, 2f, 1.2f);
    }

    /** 팔 파워 1당 흡수 가능한 attackOutput 양 */
    private static final double ABSORPTION_RATIO = 10.0;

    @Override
    public DefenceResult defend(DamageInfo incoming) {
        // sureHit(영역 필중 등)은 팔로 막을 수 없음
        if (!incoming.canBeBlocked) return DefenceResult.notBlocked(0);

        MizushiHachi hachi = getActiveHachi();
        if (hachi == null) return DefenceResult.notBlocked(0);

        double powerCost = incoming.attackOutput / ABSORPTION_RATIO;
        double available  = hachi.getPower();

        if (available >= powerCost) {
            // 파워가 충분 → 완전 흡수, 파워 차감
            hachi.reducePower(powerCost);
            return DefenceResult.fullyBlocked();
        } else {
            // 파워 부족 → 남은 파워만큼만 부분 차단, 파워 소진
            hachi.reducePower(available);
            return DefenceResult.partialBlock(available / powerCost);
        }
    }

    private MizushiHachi getActiveHachi() {
        for (ActiveSkill skill : owner.getActiveSkills()) {
            if (skill instanceof MizushiHachi h && !h.isDone()) return h;
        }
        return null;
    }

    @Override
    public InnateTerritory createTerritory() {
        return new MizushiInnateTerritory(owner);
    }

    @Override
    public DomainExpansion createDomain() {
        if (!(owner instanceof org.justheare.paperjjk.entity.JPlayer jp)) return null;
        InnateTerritory territory = jp.innateTerritory;
        if (territory == null) return null;
        // g + r → 결계 없는 영역전개, 플레이어가 t + r 로 설정한 반경 사용
        return new org.justheare.paperjjk.barrier.MizushiDomainExpansion(
                owner, territory, true, jp.noBarrierDomainRange);
    }

    @Override
    public Map<SkillKey, SkillSlot> getDefaultBindings() {
        return Map.of(
            SkillKey.X, new SkillSlot("mizushi_kai",    true,  false),
            SkillKey.C, new SkillSlot("mizushi_hachi",  true,  true),   // rechargeable
            SkillKey.V, new SkillSlot("mizushi_fuga",   true,  false),
            SkillKey.B, new SkillSlot("mizushi_domain", false, false)
        );
    }

    @Override
    public String getDisplayName() {
        return ChatColor.RED + "the Mizushi.";
    }

    @Override
    public String getKey() { return "mizushi"; }
}
