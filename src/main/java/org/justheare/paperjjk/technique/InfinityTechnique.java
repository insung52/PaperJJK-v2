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
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.InfinityInnateTerritory;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;
import org.justheare.paperjjk.skill.infinity.InfinityPassive;

import java.util.Map;

/**
 * 무하한(Infinity) 생득술식.
 *
 * 방어: sureHit=false 인 공격은 무한이 차단.
 * 타격 시: 상대를 끌어당기는 추가 효과 (ao passive).
 * 술식반전: aka (밀어내는 힘)
 */
public class InfinityTechnique extends Technique {

    private static final Particle.DustOptions DUST_INFINITY =
            new Particle.DustOptions(Color.fromRGB(80, 30, 220), 0.7f);

    /** 신체강화 소모 비율 */
    private static final double BURN_RATIO     = 0.30;
    /** 술식 미타버린 상태에서의 추가 피해 배율 */
    private static final double TECHNIQUE_MULT = 3.0;

    /** 육안 보유 여부 — 육안이 없으면 passive 주력 소모 높음 */
    private final boolean hasSixEyes;

    public InfinityTechnique(JEntity owner, boolean hasSixEyes) {
        super(owner);
        this.hasSixEyes = hasSixEyes;
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
            spawnInfinityHitEffect(target.entity);
        }
    }

    @Override
    public void onAttackMob(LivingEntity mob) {
        double consumed = consumeBodyRein();
        if (consumed <= 0) return;

        boolean burned = owner.isTechniqueBlocked();
        double bonus = burned ? consumed : consumed * TECHNIQUE_MULT;
        DamageInfo.setnodamagetick(mob);
        mob.damage(DamageInfo.outputToDamage(bonus), owner.getLivingEntity());
        if (!burned) spawnInfinityHitEffect(mob);
    }

    private double consumeBodyRein() {
        double current = owner.bodyReinforcement.getCurrent();
        if (current <= 0) return 0;
        double consumed = current * BURN_RATIO;
        owner.bodyReinforcement.consume(consumed);
        return consumed;
    }

    private void spawnInfinityHitEffect(org.bukkit.entity.Entity targetEntity) {
        Location center = targetEntity.getLocation().add(0, targetEntity.getHeight() / 2, 0);
        World world = center.getWorld();
        if (world == null) return;
        if (Math.random() < 0.3) {
            double dist = owner.getLivingEntity().getEyeLocation().distance(targetEntity.getLocation());
            owner.getLivingEntity().getWorld().spawnParticle(
                    Particle.FLASH,
                    owner.getLivingEntity().getEyeLocation().add(
                            owner.getLivingEntity().getEyeLocation().getDirection().multiply(dist * 0.7)),
                    1, 1.0, 1.0, 1.0, 1.0,
                    Color.fromARGB(10, 0, 0, 255)
            );
        }
        // 시전자 방향으로 약하게 당기기 (무한의 인력)
        if (targetEntity instanceof LivingEntity living) {
            Vector pull = owner.entity.getLocation().toVector().subtract(center.toVector());
            if (pull.length() > 0.01) {
                living.setVelocity(living.getVelocity().add(pull.normalize().multiply(0.25)));
            }
        }
    }

    @Override
    public DefenceResult defend(DamageInfo incoming) {
        // 패시브 활성화 중 + 필중 아님 → 완전 차단
        if (!incoming.sureHit && incoming.canBeBlocked && isPassiveActive()) {
            return DefenceResult.fullyBlocked();
        }
        return DefenceResult.notBlocked(0);
    }

    /** X 슬롯의 InfinityPassive 가 실행 중인지 확인 */
    private boolean isPassiveActive() {
        for (ActiveSkill skill : owner.getActiveSkills()) {
            if (skill instanceof InfinityPassive && !skill.isDone()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public InnateTerritory createTerritory() {
        return new InfinityInnateTerritory(owner);
    }

    @Override
    public DomainExpansion createDomain() {
        InnateTerritory territory = owner instanceof org.justheare.paperjjk.entity.JPlayer jp
                ? jp.innateTerritory : null;
        if (territory == null) return null;
        return new org.justheare.paperjjk.barrier.InfinityDomainExpansion(owner, territory);
    }

    @Override
    public Technique getReversed() {
        // 무하한의 술식반전은 aka (밀어내는 힘)
        // 별도 Technique 가 아니라 스킬 레벨에서 처리 (aka 스킬이 RCT 스킬)
        return null;
    }

    @Override
    public Map<SkillKey, SkillSlot> getDefaultBindings() {
        // 영역전개는 R 키 전용 (별도 패킷). x/c/v/b 는 스킬 4슬롯.
        return Map.of(
            SkillKey.X, new SkillSlot("infinity_passive", true,  true),  // 무한 패시브 (재충전 가능)
            SkillKey.C, new SkillSlot("infinity_ao",      true,  true),  // ao 충전/재충전
            SkillKey.V, new SkillSlot("infinity_aka",     true,  false), // aka 발사
            SkillKey.B, new SkillSlot("infinity_ao",      true,  true)   // ao 3번째 슬롯
        );
    }

    @Override
    public String getDisplayName() {
        return ChatColor.LIGHT_PURPLE + "the Infinity.";
    }

    @Override
    public String getKey() { return "infinity"; }

    // ── 조회 ──────────────────────────────────────────────────────────────

    public boolean hasSixEyes() { return hasSixEyes; }
}
