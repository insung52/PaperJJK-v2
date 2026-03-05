package org.justheare.paperjjk.skill.mizushi;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;

/**
 * 어주자 팔(Hachi) 핵심 타격 — 모듈형 유틸리티.
 *
 * 데미지 공식 (getCurrent 기준):
 *   raw    = casterCE^0.15 - max(0, targetCE^0.05 - 5)
 *   output = max(0.1, raw + power / 10)
 *
 * power 의미:
 *   - 스킬 사용 시 : 현재 hachi 충전량 (0 ~ 100)
 *   - 영역 필중 시 : 시전자 현재CE / 최대CE * 100 (0 ~ 100 정규화)
 *
 * 사용처:
 *   - MizushiHachi.processEntities()     → apply()
 *   - MizushiInnateTerritory.applyHachi() → applyDomain()
 */
public class HachiStrike {

    private HachiStrike() {}

    // ── 공통 데미지 계산 ──────────────────────────────────────────────────

    private static double calcOutput(JEntity attacker, double targetCE, double power) {
        double casterCE = attacker.cursedEnergy.getCurrent();
        double raw;
        if (Math.pow(targetCE, 0.2) < 5) {
            raw = Math.pow(casterCE, 0.15);
        } else {
            raw = Math.pow(casterCE, 0.15) - Math.pow(targetCE, 0.05);
        }
        return Math.max(0.1, raw + power / 10.0);
    }

    // ── 스킬 타격 (blockable) ─────────────────────────────────────────────

    /**
     * MizushiHachi 스킬에서 직접 호출.
     *
     * @param power 현재 hachi 충전량 (0 ~ 100)
     */
    public static void apply(JEntity attacker, LivingEntity target,
                             Location hitLoc, double power) {
        JEntity targetJE = JEntityManager.instance != null
                ? JEntityManager.instance.get(target.getUniqueId()) : null;
        double targetCE = targetJE != null ? targetJE.cursedEnergy.getCurrent() : 0;
        double output = calcOutput(attacker, targetCE, power);

        DamageInfo.setnodamagetick(target);
        if (targetJE != null) {
            targetJE.receiveDamage(DamageInfo.skillHit(
                    attacker, DamageType.CURSED, output * 100, "mizushi_hachi"));
        } else {
            target.damage(DamageInfo.outputToDamage(output * 100));
        }
        broadcastEffect(hitLoc);
    }

    // ── 영역 필중 (unblockable, domainSureHit) ───────────────────────────

    /**
     * MizushiInnateTerritory 영역 필중에서 호출.
     *
     * @param power 시전자 현재CE / 최대CE * 100 (0 ~ 100 정규화)
     */
    public static void applyDomain(JEntity attacker, JEntity target, double power) {
        double targetCE = target.cursedEnergy.getCurrent();
        double output = calcOutput(attacker, targetCE, power);

        LivingEntity living = target.getLivingEntity();
        DamageInfo.setnodamagetick(living);
        target.receiveDamage(DamageInfo.domainSureHit(
                attacker, output * 100, "mizushi_domain_hachi"));

        Location hitLoc = living.getLocation().add(0, living.getHeight() / 2.0, 0);
        broadcastEffect(hitLoc);
    }

    // ── 영역 필중 (unblockable, 바닐라 몹) ──────────────────────────────

    /**
     * 영역 필중: 비-JEntity(바닐라 몹)에게 팔 데미지 + 이펙트.
     * CE 없으므로 targetCE=0 으로 계산.
     */
    public static void applyDomainVanilla(JEntity attacker, LivingEntity target, double power) {
        double output = calcOutput(attacker, 0, power);
        DamageInfo.setnodamagetick(target);
        target.damage(DamageInfo.outputToDamage(output * 100));
        Location hitLoc = target.getLocation().add(0, target.getHeight() / 2.0, 0);
        broadcastEffect(hitLoc);
    }

    // ── 이펙트 ────────────────────────────────────────────────────────────

    private static void broadcastEffect(Location loc) {
        JPacketSender.broadcastHachiSlash(loc, 64.0);
    }
}
