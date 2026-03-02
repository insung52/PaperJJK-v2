package org.justheare.paperjjk.innate;

import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.entity.JEntity;

/**
 * 복마어주자(Malevolent Shrine) 생득 영역.
 *
 * 주력(CE) 있는 대상 → '팔(Hachi)' 필중: CE 우열 기반 참격 피해.
 * 주력 없는 대상 또는 결없영(isOpen) 모드 → '해(Kai)' 필중: 순수 CE 기반 직선 참격.
 * 4틱 간격으로 데미지 적용 (틱당 연속 적용 방지).
 */
public class MizushiInnateTerritory extends InnateTerritory {

    private static final int DAMAGE_INTERVAL = 4; // 틱 간격

    private int damageTickCounter = 0;

    public MizushiInnateTerritory(JEntity owner) {
        super(owner);
    }

    @Override
    public void onActiveTick() {
        damageTickCounter++;
        for (JEntity target : capturedEntities) {
            if (target == owner) {
                applyCasterBuff(owner);
            } else {
                applySureHit(target);
            }
        }
    }

    @Override
    public void applyCasterBuff(JEntity caster) {
        // 복마어주자 내부 시전자 강화 — 현재 미사용 (향후 확장)
    }

    @Override
    public void applySureHit(JEntity target) {
        if (target == owner) return;
        if (damageTickCounter % DAMAGE_INTERVAL != 0) return;

        boolean hasCursedEnergy = target.cursedEnergy.getMax() > 0;
        if (hasCursedEnergy) {
            applyHachi(target);
        } else {
            applyKai(target);
        }
    }

    // ── 필중 효과 ─────────────────────────────────────────────────────────

    /**
     * 팔(Hachi) 필중: CE 우열 기반 참격 데미지.
     * 수식: casterCE^0.15 - max(0, targetCE^0.05 - 5)
     * 최소 데미지 0.1 보장.
     */
    private void applyHachi(JEntity target) {
        double casterCE = owner.cursedEnergy.getMax();
        double targetCE = target.cursedEnergy.getMax();

        double output;
        if (Math.pow(targetCE, 0.2) < 5) {
            output = Math.pow(casterCE, 0.15);
        } else {
            output = Math.pow(casterCE, 0.15) - Math.pow(targetCE, 0.05);
        }
        output = Math.max(0.1, output);

        DamageInfo.setnodamagetick(target.getLivingEntity());
        target.receiveDamage(DamageInfo.domainSureHit(owner, output * 100, "mizushi_domain_hachi"));
    }

    /**
     * 해(Kai) 필중: 순수 CE 기반 직선 참격 (방어 불가).
     * 주력 없는 대상 또는 결없영 모드 전용.
     */
    private void applyKai(JEntity target) {
        double output = Math.pow(owner.cursedEnergy.getMax(), 0.11);
        DamageInfo.setnodamagetick(target.getLivingEntity());
        target.receiveDamage(DamageInfo.domainSureHit(owner, output * 100, "mizushi_domain_kai"));
    }

    /**
     * 결없영 전용: 일반 LivingEntity(비-JEntity)에게도 해(Kai) 필중 적용.
     */
    public void applySureHitVanilla(LivingEntity mob) {
        if (damageTickCounter % DAMAGE_INTERVAL != 0) return;
        double output = Math.pow(owner.cursedEnergy.getMax(), 0.11);
        DamageInfo.setnodamagetick(mob);
        mob.damage(DamageInfo.outputToDamage(output * 100));
    }
}
