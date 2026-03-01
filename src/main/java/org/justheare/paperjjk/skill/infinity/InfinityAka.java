package org.justheare.paperjjk.skill.infinity;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.scheduler.WorkScheduler;
import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 무한(Infinity) — 적(赤, Aka) 스킬.
 *
 * ao 가 끌어당기는 힘이라면 aka 는 발산하는 힘.
 * 충전 후 발사체로 날아가며 경로상 엔티티를 밀어내고,
 * 블록에 충돌 시 폭발 + 파워 감소하며 뚫고 진행.
 *
 * V 키 연타 가능 (rechargeable=false → 새 인스턴스 생성).
 *
 * 충전 흐름:
 *   - 충전 중: 3블록 앞에 붉은 파티클 + AKA_START/SYNC (클라이언트 왜곡 효과)
 *   - 발사(키 뗌): 시선 방향으로 발사, 폭죽 사운드만
 *   - 발동 중: 틱당 5블록 이동, 블록 폭발+관통, 엔티티 밀어내기/데미지
 */
public class InfinityAka extends ActiveSkill {

    // ── 상수 ──────────────────────────────────────────────────────────────

    private static final double PER_TICK_CHARGE       = 5.0;
    private static final double POWER_PER_CHARGE_TICK = 1.0;
    private static final double VISUAL_RANGE          = 1000.0;
    private static final int    SYNC_INTERVAL         = 5;
    private static final int    CHARGE_SOUND_INTERVAL = 5;

    /** 충전 중 aka 시작점의 플레이어로부터 거리 (ao 와 동일) */
    private static final double CHARGE_DISTANCE = 3.0;

    /** 틱당 이동 단계 수 (1단계 = 1블록 → 틱당 5블록) */
    private static final int    STEPS_PER_TICK = 3;

    /** 블록 충돌 시 파워 감소율 */
    private static final double POWER_DECAY_SOLID  = 0.3;
    private static final double POWER_DECAY_LIQUID = 0.7;

    /** 최대 이동 거리 (블록) */
    private static final double MAX_TRAVEL = 200.0;

    // ── 상태 ──────────────────────────────────────────────────────────────

    private Location akaLocation;
    private Vector   direction;

    private double remainingPower    = 0;
    private double traveledDistance  = 0;
    private int    chargeDurationTicks = 0;
    private int    chargeSoundTick   = 0;
    private int    syncTick          = 0;
    private int    activeTick        = 0;
    private boolean akaPacketActive  = false;
    private boolean murasakiTriggered = false;

    /** 충돌로 잡힌 엔티티: UUID → 잔여 추진 틱 */
    private final Map<UUID, Integer> trackedEntities = new HashMap<>();

    private final String uniqueId;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public InfinityAka(JEntity caster) {
        super(caster, PER_TICK_CHARGE);
        this.uniqueId = "AKA_" + System.nanoTime();
        if (caster instanceof JPlayer jp) {
            akaLocation = gazeLocation(jp.player);
        }
    }

    private Location gazeLocation(Player p) {
        return p.getEyeLocation().add(
                p.getEyeLocation().getDirection().multiply(CHARGE_DISTANCE));
    }

    // ── 생명주기 ──────────────────────────────────────────────────────────

    @Override
    protected void onChargingTick() {
        if (!(caster instanceof JPlayer jp)) return;
        Player p = jp.player;

        chargeDurationTicks++;
        chargeSoundTick++;

        // 충전 중 위치 = 플레이어 눈에서 3블록 앞 (ao와 동일하게 gaze 추적)
        akaLocation = gazeLocation(p);

        // 첫 충전 틱: AKA_START → 클라이언트 왜곡 효과 시작
        if (!akaPacketActive) {
            JPacketSender.broadcastInfinityAkaStart(
                    akaLocation, powerToStrength(1.0), uniqueId, VISUAL_RANGE);
            akaPacketActive = true;
        }

        // 붉은 DUST 파티클
        double cp = Math.max(1.0, chargeDurationTicks * POWER_PER_CHARGE_TICK);
        spawnChargingParticles(cp);

        // AKA_SYNC: 충전 중 파워 미리보기 → 클라이언트 왜곡 강도 업데이트
        if (++syncTick % SYNC_INTERVAL == 0) {
            double previewPower = Math.min(100.0, cp);
            JPacketSender.broadcastInfinityAkaSync(
                    akaLocation, powerToStrength(previewPower), uniqueId, VISUAL_RANGE);
            syncTick = 0;
        }

        // 충전 사운드
        if (chargeSoundTick % CHARGE_SOUND_INTERVAL == 0) {
            float vol   = (float) (cp / 100.0);
            float pitch = (float) (cp / 100.0 * 1.5 + 0.5);
            p.getWorld().playSound(p.getLocation(),
                    Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, vol, pitch);
        }
    }

    @Override
    protected void onCharged() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;

        remainingPower = Math.max(1.0,
                Math.min(100.0, chargeDurationTicks * POWER_PER_CHARGE_TICK));
        chargeDurationTicks = 0;
        chargeSoundTick = 0;
        syncTick = 0;

        // 발사 방향 (akaLocation은 이미 gaze 위치에 있음)
        direction = p.getEyeLocation().getDirection().normalize();

        // AKA_SYNC 로 발사 시점 강도 업데이트 (START는 충전 시 이미 전송됨)
        JPacketSender.broadcastInfinityAkaSync(
                akaLocation, powerToStrength(remainingPower), uniqueId, VISUAL_RANGE);

        // 발사 사운드 — 폭죽 소리만
        p.getWorld().playSound(p.getLocation(),
                Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 3f, 1.0f);
    }

    @Override
    protected void onActiveTick() {
        if (!(caster instanceof JPlayer jp)) { end(); return; }
        Player p = jp.player;
        activeTick++;

        // 추적 중 엔티티 지속 추진
        processTrackedEntities();

        // 이동 (5단계 × 1블록)
        for (int step = 0; step < STEPS_PER_TICK; step++) {
            akaLocation = akaLocation.clone().add(direction.clone().multiply(1.0));
            traveledDistance++;

            // 블록 충돌: 폭발 + 파워 감소 후 관통 계속
            if (!akaLocation.getBlock().isEmpty()) {
                if (akaLocation.getBlock().isLiquid()) {
                    remainingPower *= POWER_DECAY_LIQUID;
                } else {
                    float explodeSize = (float) Math.pow(remainingPower, 0.5);
                    akaLocation.createExplosion(explodeSize, false, true);
                    remainingPower *= POWER_DECAY_SOLID;
                }
                if (remainingPower < 1) { end(); return; }
            }

            // 엔티티 충돌 체크
            checkEntityCollisions(p);

            // murasaki 트리거: 근처 ao 와 충돌 시 InfinityMurasaki 생성
            if (!murasakiTriggered && checkMurasakiTrigger(p)) {
                murasakiTriggered = true;
                return;
            }

            if (traveledDistance >= MAX_TRAVEL) { end(); return; }
        }

        // 발동 중 파티클
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED,
                (float) (Math.pow(remainingPower, 0.5) / 7));
        akaLocation.getWorld().spawnParticle(Particle.DUST, akaLocation, 1,
                0.02, 0.02, 0.02, 0.5, dust, true);

        // SYNC 주기
        if (++syncTick % SYNC_INTERVAL == 0) {
            JPacketSender.broadcastInfinityAkaSync(
                    akaLocation, powerToStrength(remainingPower), uniqueId, VISUAL_RANGE);
            syncTick = 0;
        }
    }

    @Override
    protected void onEnd() {
        if (akaPacketActive && akaLocation != null) {
            JPacketSender.broadcastInfinityAkaEnd(akaLocation, uniqueId, VISUAL_RANGE);
            akaPacketActive = false;
        }
    }

    // ── 엔티티 처리 ───────────────────────────────────────────────────────

    /** 추적 중 엔티티를 매 틱 forward 방향으로 밀고 4틱마다 데미지. */
    private void processTrackedEntities() {
        Iterator<Map.Entry<UUID, Integer>> it = trackedEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Entity entity = akaLocation.getWorld().getEntity(entry.getKey());
            if (entity == null || !entity.isValid()) { it.remove(); continue; }

            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) { it.remove(); continue; }



            if (activeTick % 4 == 0 && entity instanceof LivingEntity living) {
                entity.setVelocity(entity.getVelocity().add(
                        direction.clone().multiply(new Vector(1, 0.5, 1)).multiply(0.2)));
                applyAkaDamage(living, Math.pow(remainingPower, 0.3));
            }
        }
    }

    /** 최초 충돌: 강한 밀기 + 큰 데미지, 10틱 추적 시작 */
    private void checkEntityCollisions(Player user) {
        double searchRadius = 5 + Math.pow(remainingPower, 0.7);
        List<Entity> nearby = (List<Entity>) akaLocation.getNearbyEntities(
                searchRadius, searchRadius, searchRadius);

        for (Entity entity : nearby) {
            if (entity.equals(user)) continue;
            if (trackedEntities.containsKey(entity.getUniqueId())) continue;

            double hitRadius = 2 + Math.pow(remainingPower, 0.5)
                    + entity.getHeight() + entity.getWidth();
            if (entity.getLocation().distance(akaLocation) <= hitRadius) {
                trackedEntities.put(entity.getUniqueId(), 10);
                entity.setVelocity(entity.getVelocity().add(direction.clone().multiply(7)));
                if (entity instanceof LivingEntity living) {
                    applyAkaDamage(living, (10 + Math.pow(remainingPower, 1.0))*100);
                }
            }
        }
    }

    /**
     * 근처에 ACTIVE 상태의 InfinityAo (같은 시전자) 가 있으면 murasaki 발동.
     * 조건: ao 와의 거리 ≤ 10블록, 양쪽 파워 ≥ 10.
     * - passive 꺼짐 + 충돌 지점이 시전자로부터 10블록 이내 → 무제한 murasaki
     * - 그 외 → 일반 murasaki
     */
    private boolean checkMurasakiTrigger(Player user) {
        InfinityAo targetAo = null;
        for (ActiveSkill skill : caster.getActiveSkills()) {
            if (!(skill instanceof InfinityAo ao)) continue;
            if (!ao.isActive()) continue;
            Location aoLoc = ao.getAoLocation();
            if (aoLoc == null) continue;
            if (!aoLoc.getWorld().equals(akaLocation.getWorld())) continue;
            if (aoLoc.distance(akaLocation) > 10) continue;
            if (ao.getRemainingPower() < 10 || remainingPower < 10) continue;
            targetAo = ao;
            break;
        }
        if (targetAo == null) return false;

        // 패시브 활성화 여부 확인
        boolean passiveActive = false;
        for (ActiveSkill s : caster.getActiveSkills()) {
            if (s instanceof InfinityPassive && !s.isDone()) { passiveActive = true; break; }
        }
        boolean unlimitedMode = !passiveActive
                && user.getLocation().distance(akaLocation) < 10;

        double combinedPower = remainingPower + targetAo.getRemainingPower();

        InfinityMurasaki murasaki = new InfinityMurasaki(
                caster, akaLocation.clone(), direction.clone(), combinedPower, unlimitedMode);
        caster.addActiveSkill(murasaki);
        WorkScheduler.getInstance().register(murasaki);

        targetAo.end();
        end();
        return true;
    }

    private void applyAkaDamage(LivingEntity living, double output) {
        JEntity targetEntity = JEntityManager.instance != null
                ? JEntityManager.instance.get(living.getUniqueId()) : null;
        if (targetEntity != null) {
            targetEntity.receiveDamage(
                    DamageInfo.skillHit(caster, DamageType.CURSED, output, "infinity_aka"));
        } else {
            living.damage(DamageInfo.outputToDamage(output));
        }
    }

    // ── 파티클 ────────────────────────────────────────────────────────────

    private void spawnChargingParticles(double cp) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 0.2F);
        akaLocation.getWorld().spawnParticle(Particle.DUST, akaLocation,
                (int) Math.pow(cp, 0.8),
                Math.log(cp + 1) / 5, Math.log(cp + 1) / 10, Math.log(cp + 1) / 10,
                0, dust, true);
        dust = new Particle.DustOptions(Color.RED, (float) (Math.pow(cp, 0.5) / 10));
        akaLocation.getWorld().spawnParticle(Particle.DUST, akaLocation, 1,
                0.1, 0.1, 0.1, 0.5, dust, true);
    }

    // ── HUD ───────────────────────────────────────────────────────────────

    @Override
    public float getGaugePercent() {
        return switch (getPhase()) {
            case CHARGING -> (float) Math.min(1.0,
                    chargeDurationTicks * POWER_PER_CHARGE_TICK / 100.0);
            case ACTIVE   -> (float) Math.max(0.0, remainingPower / 100.0);
            case ENDED    -> 0.0f;
        };
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────

    private float powerToStrength(double power) {
        return (float) (power * 0.049 + 0.051);
    }
}
