package org.justheare.paperjjk.entity;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.barrier.DomainManager;
import org.justheare.paperjjk.technique.InfinityTechnique;
import org.justheare.paperjjk.technique.MizushiTechnique;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.cursed.ChargingRequest;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.network.PacketIds;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.skill.SkillKey;
import org.justheare.paperjjk.skill.SkillKeyMap;
import org.justheare.paperjjk.skill.SkillSlot;
import org.justheare.paperjjk.status.StatusEffectType;
import org.justheare.paperjjk.status.TimedStatusEffect;
import org.justheare.paperjjk.technique.Technique;

import org.bukkit.Location;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 플레이어 주술사. JEntity 구현체.
 * 기존 Jplayer 를 대체.
 */
public class JPlayer extends JEntity {

    public final Player player;

    /** 키 → 스킬 매핑 */
    public final SkillKeyMap skillKeyMap;

    /** CE_UPDATE 전송 주기 카운터 (5틱마다 전송) */
    private int ceSyncTick = 0;

    /** 신체강화 이전 ratio (변화 감지용) */
    private float lastBodyReinRatio = -1f;

    /** 신체강화 키(Z) 홀드 중 여부 */
    private boolean bodyReinKeyHeld = false;

    /** 신체강화 키 홀드 중인 모드 */
    private BodyReinMode bodyReinKeyMode = BodyReinMode.NONE;

    /**
     * 공기의 면을 포착하는 능력 보유 여부.
     * false: 지상에서만 대쉬 가능.
     * true : 공중에서도 대쉬 가능하나 속도 30%.
     */
    public boolean canGraspAirSurface = false;

    /** 생득 영역 (jjk id build 로 사전 설정) */
    @Nullable
    public InnateTerritory innateTerritory;

    /** 현재 전개 중인 영역전개 */
    @Nullable
    public DomainExpansion activeDomain;

    /** 일반 영역전개 반경 (5~50, 기본 30) */
    public int normalDomainRange    = 30;
    /** 결없영 반경 (5~200, 기본 200) */
    public int noBarrierDomainRange = 200;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public JPlayer(Player player, double maxCursedEnergy, boolean canReverseOutput) {
        super(player, maxCursedEnergy, canReverseOutput, 0.01);
        this.player = player;
        this.skillKeyMap = new SkillKeyMap(this);
    }

    // ── 술식 설정 ─────────────────────────────────────────────────────────

    /**
     * 생득술식 부여 및 초기화.
     * 기존 Jobject.setvalues() 대체.
     */
    public void setTechnique(Technique technique) {
        if (this.technique != null) return; // 이미 있으면 무시

        this.technique = technique;

        // 키 배치 초기화
        skillKeyMap.initializeForTechnique(technique);

        // 주력 효율 레벨 설정
        cursedEnergy.setEfficiencyLevel(
                org.justheare.paperjjk.technique.TechniqueFactory.defaultEfficiencyLevel(technique.getKey()));

        // 최대 체력 갱신
        updateMaxHealth();

        // 신체강화 최대값 갱신 (최대 방출량에 비례)
        syncBodyReinMax();

        player.sendMessage(technique.getDisplayName());
    }

    /**
     * 기존 술식을 무시하고 강제로 새 술식 부여.
     * /jjk basic 커맨드에서 사용.
     */
    public void forceTechnique(Technique technique) {
        this.technique = technique;
        skillKeyMap.initializeForTechnique(technique);
        cursedEnergy.setEfficiencyLevel(
                org.justheare.paperjjk.technique.TechniqueFactory.defaultEfficiencyLevel(technique.getKey()));
        updateMaxHealth();
        syncBodyReinMax();
        player.sendMessage(technique.getDisplayName());
    }

    // ── 신체강화 키 처리 ──────────────────────────────────────────────────

    /**
     * 신체강화 키 홀드/릴리즈 처리.
     * JPacketHandler 에서 BODY_REIN_KEY 수신 시 호출.
     */
    public void handleBodyReinKey(boolean held, BodyReinMode mode) {
        bodyReinKeyHeld = held;
        bodyReinKeyMode = held ? mode : BodyReinMode.NONE;
        if (held) {
            bodyReinforcement.startCharging(mode);
        }
    }

    /**
     * 신체강화 CE 소비를 분배 시스템에 포함.
     * 틱당 요청량 = bodyReinforcement.max * 5% (20틱에 가득 참).
     */
    @Override
    protected void addAdditionalChargeRequests(List<ChargingRequest> requests) {
        if (bodyReinKeyHeld && bodyReinforcement.getMax() > 0) {
            bodyReinforcement.startCharging(bodyReinKeyMode); // mode가 NONE으로 리셋된 경우 재설정
            double perTick = bodyReinforcement.getMax() * 0.05;
            requests.add(new ChargingRequest(bodyReinforcement::addCharge, perTick));
        }
    }

    // ── 영역전개 ──────────────────────────────────────────────────────────

    public boolean expandDomain() {
        if (technique == null || isTechniqueBlocked()) return false;
        if (innateTerritory == null || !innateTerritory.isReady()) {
            player.sendMessage("생득 영역이 설정되지 않았습니다. (jjk id build)");
            return false;
        }
        if (activeDomain != null) {
            player.sendMessage("이미 영역전개 중입니다.");
            return false;
        }
        if (!cursedEnergy.consume(50000)) {
            player.sendMessage("주력이 부족합니다.");
            return false;
        }

        activeDomain = technique.createDomain();
        if (activeDomain == null) {
            // 소모된 CE 환불
            cursedEnergy.setCurrent(cursedEnergy.getCurrent() + 50000);
            return false;
        }

        DomainManager.instance.register(activeDomain);

        // DOMAIN_VISUAL START 브로드캐스트
        Location center = player.getLocation();
        int domainType = getDomainType();
        JPacketSender.broadcastDomainVisualStart(center, player.getUniqueId(),
                domainType, center, (float) activeDomain.getRange(), activeDomain.isOpen(),
                DomainManager.BROADCAST_RANGE);

        return true;
    }

    /**
     * 영역전개를 수동으로 붕괴시킨다.
     * onTick()에서 DONE 페이즈 감지 후 finalizeCollapse()로 정리됨.
     */
    public void collapseDomain() {
        if (activeDomain == null) return;
        activeDomain.collapse(); // CLOSING 페이즈 진입, 엔티티 귀환
    }

    /**
     * 영역전개 DONE 페이즈 감지 시 호출 — 최종 정리 및 술식 타버림 처리.
     */
    private void finalizeCollapse() {
        if (activeDomain == null) return;
        DomainManager.instance.unregister(activeDomain);
        activeDomain = null;
        status.add(new TimedStatusEffect(StatusEffectType.BURNED_TECHNIQUE, 20 * 30));
    }

    private int getDomainType() {
        if (technique instanceof InfinityTechnique) return PacketIds.DomainType.INFINITY;
        if (technique instanceof MizushiTechnique)  return PacketIds.DomainType.MIZUSHI;
        return PacketIds.DomainType.OTHER;
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        // maxOutput이 현재 CE에 선형 비례하므로 매 틱 bodyReinMax 갱신
        syncBodyReinMax();
        if(!player.isOnGround()&&player.isSneaking()){
            player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 2, 0));
        }
        super.onTick();

        // CE_UPDATE 주기 전송 (5틱마다)
        if (++ceSyncTick >= 5) {
            ceSyncTick = 0;
            JPacketSender.sendCEUpdate(player, this);
        }

        // 슬롯 게이지 동기화 (매 틱)
        syncSlotGauges();

        // 신체강화 바 동기화 (ratio 가 1% 이상 바뀌었을 때)
        if (bodyReinforcement.getMax() > 0) {
            float ratio = (float)(bodyReinforcement.getCurrent() / bodyReinforcement.getMax());
            if (Math.abs(ratio - lastBodyReinRatio) >= 0.01f) {
                lastBodyReinRatio = ratio;
                JPacketSender.sendBodyReinUpdate(player, bodyReinforcement);
            }
        }

        // 신체강화 NORMAL — 속도·점프력 포션 효과 (매 틱 갱신, log2 스케일)
        // bodyReinCurrent 범위: Grade 3 풀≈40, Grade 1 풀≈100K, Mizushi 풀≈40M
        // log2(41)≈5.36, log2(100001)≈16.6, log2(40M)≈25.25
        // speedAmp = log2/5 : Grade 3=1(Speed II), Grade 1=3(Speed IV), Mizushi=5(Speed VI)
        // jumpAmp  = log2/6 : Grade 3=0(Jump I),  Grade 1=2(Jump III), Mizushi=4(Jump V)
        if (bodyReinforcement.getMode() == BodyReinMode.NORMAL && bodyReinforcement.isActive()) {
            double brCurrent = bodyReinforcement.getCurrent();
            if (brCurrent > 0) {
                double log2val = Math.log(brCurrent + 1) / Math.log(2);
                int speedAmp = (int)(log2val / 5);
                int jumpAmp  = (int)(log2val / 6);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 3, speedAmp, true, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 3, jumpAmp, true, false));
            }
        }

        // 반전술식 — isSneaking() 체크 (Player API 필요)
        if (reverseOutput != null && reverseOutput.isActive()) {
            if (!player.isSneaking()) {
                reverseOutput.stop();
            } else {
                applyReverseCurse();
            }
        }

        // 영역전개 틱
        if (activeDomain != null) {
            activeDomain.onTick();
            if (activeDomain.getDomainPhase() == DomainExpansion.DomainPhase.DONE) {
                finalizeCollapse();
            }
        }
    }

    /** 슬롯 X/C/V/B 의 게이지·자물쇠·레이블 상태를 클라이언트에 전송 */
    private void syncSlotGauges() {
        SkillKey[] keys = SkillKey.values(); // X, C, V, B
        byte[]    states = new byte[4];
        float[]   gauges = new float[4];
        boolean[] locked = new boolean[4];
        String[]  labels = new String[4];

        for (int i = 0; i < 4; i++) {
            labels[i] = "";
            SkillSlot slot = skillKeyMap.getSlot(keys[i]);
            if (slot != null && slot.isRunning()) {
                states[i] = slot.runningSkill.getSlotGaugeState();
                gauges[i] = slot.runningSkill.getGaugePercent();
                locked[i] = slot.runningSkill.isLocked();
                labels[i] = slot.runningSkill.getSlotLabel();
            }
            // else: NONE(0), 0, false, "" (기본값)
        }
        JPacketSender.sendSlotGaugeUpdate(player, states, gauges, locked, labels);
    }

    /** 반전술식 주력 소모 → 체력 회복 */
    private void applyReverseCurse() {
        if (reverseOutput == null) return;
        double outputAvail = cursedEnergy.getMaxOutput(getHealthPercent());
        double consumeAmount = outputAvail * 0.1;
        if (!cursedEnergy.consume(consumeAmount)) return;

        // 치료량 = log₂(소모CE + 1) × HEAL_LOG_SCALE
        double healAmount = Math.log(consumeAmount + 1) / Math.log(2)
                * reverseOutput.getHealLogScale();
        player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + healAmount));

        // 파티클 — 흰색 ENTITY_EFFECT 10개
        player.getWorld().spawnParticle(Particle.ENTITY_EFFECT,
                player.getLocation(), 10, 0.5, 0.5, 0.5, 0.5, Color.WHITE);

        // 소리 — 10틱(0.5초)마다 재생
        if (reverseOutput.getSoundTick() % 10 == 0) {
            player.getWorld().playSound(player, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.3F, 0.7F);
            player.getWorld().playSound(player, Sound.BLOCK_CONDUIT_AMBIENT, 0.3F, 1.0F);
        }
        reverseOutput.incrementSoundTick();

        // 구속효과 — Slowness I (20틱 지속, 매 틱 갱신)
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5, 2, true, false));
    }

    // ── JEntity 추상 메서드 구현 ──────────────────────────────────────────

    @Override
    public double getHealthPercent() {
        return player.getHealth() / player.getMaxHealth();
    }

    @Override
    public LivingEntity getLivingEntity() {
        return player;
    }

    // ── 편의 메서드 ───────────────────────────────────────────────────────

    /**
     * 최대 체력 = 20 + log(maxCursedEnergy) 비례.
     * 기존 jbasic() 대체.
     */
    public void updateMaxHealth() {
        double max = cursedEnergy.getMax();
        if (max <= 5) return; // 일반인/피지컬 기프티드는 기본 체력
        double newMax = 20 + Math.pow(max - 5, 0.3);
        player.setMaxHealth(newMax);
        player.setHealth(Math.min(player.getHealth(), newMax));
    }

    /**
     * 대쉬. Space 입력 시 호출.
     * 신체강화 주력 전량 소모 → 바라보는 방향으로 velocity 적용.
     *
     * onGround: 클라이언트에서 전달받은 값 (핑 오차 없음, 서버 isOnGround() 대체)
     *   - 지상: 정상 속도
     *   - 공중 + canGraspAirSurface=false: 대쉬 불가 (return)
     *   - 공중 + canGraspAirSurface=true : 속도 30% (공기의 면을 포착)
     *
     * Infinity: ao(무한) 활용 고속이동 → 속도 1.5배
     * 일반: 1.0 + log₂(bodyReinCurrent + 1) × 0.2
     */
    public void dash(boolean onGround) {
        // 공중에서 canGraspAirSurface 없으면 대쉬 불가
        if (player.getLocation().add(0,-1,0).getBlock().isPassable() && !canGraspAirSurface) return;
        double bodyReinCurrent = bodyReinforcement.getCurrent();
        double log2val   = Math.pow(bodyReinCurrent + 1,0.5);
        PaperJJK.log("what"+ log2val);
        double dashSpeed = 1.0 + log2val * 0.2;

        // Infinity 술식은 ao 특성으로 속도 1.5배
        if (technique instanceof InfinityTechnique) {
            dashSpeed *= 1.5;
        }

        // 공중 대쉬(공기의 면을 포착)는 속도 30%
        if (!onGround) {
            dashSpeed *= 0.3;
        }

        bodyReinforcement.consume(bodyReinCurrent); // 전량 소모

        Vector dir = player.getLocation().getDirection().normalize();
        if (dir.getY() < -0.5) {
            dir.setY(-0.5);
            dir.normalize();
        }
        player.setVelocity(player.getVelocity().add(dir.multiply(dashSpeed)));

        // 파티클 — CLOUD
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 15, 2, 2, 2, 0.3);

        // 소리 — EVOKER_CAST_SPELL
        player.getWorld().playSound(player, Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 3F, 1.8F);
    }

    /** 신체강화 최대값 = 현재 최대 방출량에 비례 */
    public void syncBodyReinMax() {
        double maxOutput = cursedEnergy.getMaxOutput(1.0);
        bodyReinforcement.setMax(maxOutput);
    }
}
