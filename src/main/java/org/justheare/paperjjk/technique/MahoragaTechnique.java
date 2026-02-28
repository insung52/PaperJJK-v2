package org.justheare.paperjjk.technique;

import org.bukkit.ChatColor;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;

import java.util.Map;

/**
 * 마호라가(Mahoraga) 술식.
 * 적응 시스템: 피격 패턴을 학습하여 해당 공격에 대한 피해를 감소.
 */
public class MahoragaTechnique extends Technique {

    // skillId → 적응 레벨 (0.0 ~ 1.0, 높을수록 피해 감소)
    private final java.util.Map<String, Double> adaptationMap = new java.util.HashMap<>();

    private static final double ADAPT_INCREASE_PER_HIT = 0.1;
    private static final double MAX_ADAPT = 1.0;

    public MahoragaTechnique(JEntity owner) {
        super(owner);
    }

    // ── Technique 구현 ────────────────────────────────────────────────────

    @Override
    public void onAttack(JEntity target, DamageInfo damageInfo) {}

    @Override
    public DefenceResult defend(DamageInfo incoming) {
        // 적응된 공격 유형에 따라 피해 감소
        double adaptLevel = adaptationMap.getOrDefault(incoming.skillId, 0.0);
        if (adaptLevel <= 0) return DefenceResult.notBlocked(0);

        // 적응 후 피격 → 더 적응
        adapt(incoming.skillId);

        return DefenceResult.partialBlock(adaptLevel);
    }

    @Override
    public InnateTerritory createTerritory() { return null; }

    @Override
    public DomainExpansion createDomain() { return null; }

    @Override
    public Map<SkillKey, SkillSlot> getDefaultBindings() {
        return Map.of(
            SkillKey.X, new SkillSlot("mahoraga_wheel", false, false)
        );
    }

    @Override
    public String getDisplayName() {
        return ChatColor.DARK_PURPLE + "Mahoraga.";
    }

    @Override
    public String getKey() { return "mahoraga"; }

    // ── 적응 시스템 ───────────────────────────────────────────────────────

    /** 피격 시 해당 skillId 에 적응 */
    public void adapt(String skillId) {
        double current = adaptationMap.getOrDefault(skillId, 0.0);
        adaptationMap.put(skillId, Math.min(MAX_ADAPT, current + ADAPT_INCREASE_PER_HIT));
    }

    public double getAdaptLevel(String skillId) {
        return adaptationMap.getOrDefault(skillId, 0.0);
    }

    /** JData 저장/로드용 */
    public java.util.Map<String, Double> getAdaptationMap() { return adaptationMap; }
    public void loadAdaptationMap(java.util.Map<String, Double> data) {
        adaptationMap.clear();
        adaptationMap.putAll(data);
    }
}
