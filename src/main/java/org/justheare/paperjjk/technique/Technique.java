package org.justheare.paperjjk.technique;

import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DefenceResult;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillSlot;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 생득술식 추상 기반. 기존 naturaltech String 분기를 대체.
 * 각 술식은 이 클래스를 상속하여 고유 동작을 구현.
 */
public abstract class Technique {

    protected final JEntity owner;

    protected Technique(JEntity owner) {
        this.owner = owner;
    }

    // ── 핵심 동작 ─────────────────────────────────────────────────────────

    /**
     * 타격 시 효과 (passive) — 대상이 JEntity 인 경우.
     * DamagePipeline 이 공격 발생 시 호출.
     */
    public abstract void onAttack(JEntity target, DamageInfo damageInfo);

    /**
     * 타격 시 효과 (passive) — 대상이 일반 몹(비-JEntity) 인 경우.
     * JEvent 에서 vanilla EntityDamageByEntityEvent 발생 시 호출.
     * 기본 구현은 아무것도 하지 않음.
     */
    public void onAttackMob(LivingEntity mob) {}

    /**
     * 피격 시 방어 처리.
     * DamagePipeline Phase 2 에서 호출.
     */
    public abstract DefenceResult defend(DamageInfo incoming);

    /**
     * 생득 영역 생성.
     * jjk id build 로 위치가 사전 설정된 경우에만 유효.
     */
    public abstract InnateTerritory createTerritory();

    /**
     * 영역전개 생성.
     */
    public abstract DomainExpansion createDomain();

    /**
     * 술식반전 버전 반환 (ao → aka 등).
     * 술식반전 불가한 술식은 null 반환.
     */
    @Nullable
    public Technique getReversed() { return null; }

    /**
     * 영역전개 필중 대상이 되는가.
     * 일반 주술사/주령: true, PhysicalGifted: false
     */
    public boolean isDomainTarget() { return true; }

    /**
     * 이 술식의 기본 키 배치.
     * SkillKeyMap.initializeForTechnique() 에서 사용.
     */
    public abstract Map<SkillKey, SkillSlot> getDefaultBindings();

    /** 플레이어에게 보여줄 술식 이름 */
    public abstract String getDisplayName();

    /** TechniqueFactory 에서 사용하는 ID 문자열 (infinity, mizushi 등) */
    public abstract String getKey();
}
