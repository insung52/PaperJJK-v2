package org.justheare.paperjjk.technique;

import org.bukkit.ChatColor;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.InfinityInnateTerritory;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;

import java.util.Map;

/**
 * 무하한(Infinity) 생득술식.
 *
 * 방어: sureHit=false 인 공격은 무한이 차단.
 * 타격 시: 상대를 끌어당기는 추가 효과 (ao passive).
 * 술식반전: aka (밀어내는 힘)
 */
public class InfinityTechnique extends Technique {

    /** 육안 보유 여부 — 육안이 없으면 passive 주력 소모 높음 */
    private final boolean hasSixEyes;

    public InfinityTechnique(JEntity owner, boolean hasSixEyes) {
        super(owner);
        this.hasSixEyes = hasSixEyes;
    }

    // ── Technique 구현 ────────────────────────────────────────────────────

    @Override
    public void onAttack(JEntity target, DamageInfo damageInfo) {
        // 타격 시 효과: 일정 확률로 상대를 끌어당기는 파티클 + 추가 데미지
        // (기존 코드 참고: 0.3 확률로 FLASH 파티클)
        // 실제 이펙트는 스킬 구현체(InfinityPassive)에서 처리
    }

    @Override
    public DefenceResult defend(DamageInfo incoming) {
        // 필중(sureHit)이 아니면 무한이 완전 차단
        if (!incoming.sureHit && incoming.canBeBlocked) {
            return DefenceResult.fullyBlocked();
        }
        return DefenceResult.notBlocked(0);
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
        // 무량공처 (InfinityDomainExpansion) — 추후 구현
        return null;
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
            SkillKey.X, new SkillSlot("infinity_passive", false, false), // 무한 on/off
            SkillKey.C, new SkillSlot("infinity_ao",      true,  true),  // ao 충전/재충전
            SkillKey.V, new SkillSlot("infinity_ao",      true,  true),  // ao 2번째 슬롯
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
