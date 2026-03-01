package org.justheare.paperjjk;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageType;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.technique.Technique;
import org.justheare.paperjjk.technique.TechniqueFactory;

import java.util.UUID;

/**
 * 플레이어 접속/퇴장 이벤트 처리.
 * JPlayer 생성 및 JEntityManager 등록/해제.
 */
public class JEvent implements Listener {

    // ── 데미지 이벤트 ─────────────────────────────────────────────────────

    private static final int ENV_DAMAGE_COOLDOWN = 10; // 0.5초 (JEntity용 틱)
    private static final long ENV_COOLDOWN_MS    = 500L; // 0.5초 (비-JEntity용 ms)

    /**
     * JEntityManager에 없는 일반 몹용 경량 쿨다운.
     * 틱 시스템 없이 타임스탬프로 관리.
     * key: entity UUID → (cause name → last hit time ms)
     */
    private final Map<UUID, Map<String, Long>> mobEnvCooldowns = new HashMap<>();

    /** 쿨다운이 적용되는 환경 데미지 소스 */
    private static final Set<DamageCause> COOLDOWN_CAUSES = Set.of(
            DamageCause.LIGHTNING,
            DamageCause.LAVA,
            DamageCause.FIRE,
            DamageCause.FIRE_TICK,
            DamageCause.CONTACT,
            DamageCause.SUFFOCATION,
            DamageCause.HOT_FLOOR
    );

    /**
     * 환경 데미지 (용암, 불, 선인장 등) 처리.
     * 지정된 소스만 소스별 독립 쿨다운 적용, 나머지는 쿨다운 없이 연속 데미지.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        // EntityDamageByEntityEvent는 별도 핸들러에서 처리
        if (event instanceof EntityDamageByEntityEvent) return;

        // suppress 체크를 먼저 — pipeline echo 이벤트는 setnodamagetick 없이 그냥 통과
        JEntity earlyVictim = JEntityManager.instance.get(event.getEntity().getUniqueId());
        if (earlyVictim != null && earlyVictim.suppressDamageEvent) {
            PaperJJK.logDamage("[DBG-ENV] suppress=true → clear and return (cause=" + event.getCause() + ")");
            earlyVictim.suppressDamageEvent = false;
            return;
        }

        // 실제 외부 이벤트: noDamageTicks 리셋
        if (event.getEntity() instanceof LivingEntity living) {
            DamageInfo.setnodamagetick(living);
        }

        DamageCause cause = event.getCause();

        // [DBG-ENV] 모든 환경 데미지 이벤트 로그
        if (COOLDOWN_CAUSES.contains(cause)) {
            PaperJJK.logDamage("[DBG-ENV] cause=" + cause
                    + " entity=" + event.getEntity().getName()
                    + " cancelled=" + event.isCancelled()
                    + " dmg=" + event.getDamage());
        }

        JEntity victim = earlyVictim;
        if (victim == null) {
            // 일반 몹: COOLDOWN_CAUSES만 타임스탬프 기반 쿨다운 적용
            if (!COOLDOWN_CAUSES.contains(cause)) return;
            UUID uid = event.getEntity().getUniqueId();
            long now = System.currentTimeMillis();
            Map<String, Long> mobMap = mobEnvCooldowns.computeIfAbsent(uid, k -> new HashMap<>());
            long lastHit = mobMap.getOrDefault(cause.name(), 0L);
            if (now - lastHit < ENV_COOLDOWN_MS) {
                PaperJJK.logDamage("[DBG-ENV] mob cooldown active → cancel (cause=" + cause + ")");
                event.setCancelled(true);
            } else {
                mobMap.put(cause.name(), now);
                PaperJJK.logDamage("[DBG-ENV] mob cooldown passed → vanilla dmg (cause=" + cause + ")");
            }
            return;
        }

        // 항상 캔슬 — 이후 Pipeline을 통해서만 데미지 적용
        event.setCancelled(true);

        // 쿨다운 적용 소스는 체크, 그 외는 바로 통과
        if (COOLDOWN_CAUSES.contains(cause)) {
            boolean passed = victim.tryEnvDamage(cause.name(), ENV_DAMAGE_COOLDOWN);
            PaperJJK.logDamage("[DBG-ENV] cooldown check → passed=" + passed);
            if (!passed) return;
        }

        double attackOutput = DamageInfo.damageToOutput(event.getDamage());
        PaperJJK.logDamage("[DBG-ENV] routing to pipeline, output=" + attackOutput);
        // canBeBlocked=true → Infinity 등이 정상 차단 가능
        DamageInfo info = new DamageInfo(null, DamageType.PHYSICAL, attackOutput,
                cause.name(), false, true);
        victim.receiveDamage(info);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damagee = event.getEntity();

        // suppress 체크를 먼저 — pipeline echo 이벤트는 setnodamagetick 없이 그냥 통과
        JEntity victim = JEntityManager.instance.get(damagee.getUniqueId());
        if (victim != null && victim.suppressDamageEvent) {
            victim.suppressDamageEvent = false;
            return;
        }

        // 실제 외부 이벤트: noDamageTicks 리셋
        if (damagee instanceof LivingEntity living) {
            DamageInfo.setnodamagetick(living);
        }

        if (victim == null) return;

        // 외부 물리 타격 → DamagePipeline으로 라우팅
        event.setCancelled(true);

        Entity damager = event.getDamager();
        JEntity attacker = (damager instanceof LivingEntity le)
                ? JEntityManager.instance.get(le.getUniqueId()) : null;

        double attackOutput = DamageInfo.damageToOutput(event.getDamage());
        DamageInfo info = DamageInfo.physicalHit(attacker, attackOutput);
        victim.receiveDamage(info);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        mobEnvCooldowns.remove(event.getEntity().getUniqueId());
    }

    // ── 플레이어 접속/퇴장 ────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 이미 등록된 경우 (드문 edge case — 강제 리로그 등)
        if (JEntityManager.instance.get(uuid) != null) {
            PaperJJK.log("[JEvent] Already registered: " + player.getName() + ", skipping.");
            return;
        }

        // 저장된 데이터 가져오기 (없으면 null = 신규)
        JData.PlayerSaveData saved = JData.consumePending(uuid);

        JPlayer jp;
        if (saved != null && !saved.techniqueName.isEmpty()) {
            // 기존 플레이어 — 저장 데이터 복원
            jp = new JPlayer(player, saved.maxCE, saved.canReverseOutput);
            jp.cursedEnergy.setCurrent(saved.currentCE);
            jp.blackFlash.setLifeTimeCount(saved.blackFlashLifeTimeCount);

            // 술식 복원
            Technique technique = TechniqueFactory.create(saved.techniqueName, jp);
            if (technique != null) {
                jp.setTechnique(technique); // 내부에서 efficiencyLevel을 기본값으로 설정
                // 저장된 efficiencyLevel 이 있으면 기본값을 override
                if (saved.efficiencyLevel >= 0) {
                    jp.cursedEnergy.setEfficiencyLevel(saved.efficiencyLevel);
                }
                // Mahoraga 적응 데이터 복원
                if (technique instanceof org.justheare.paperjjk.technique.MahoragaTechnique mt
                        && saved.mahoragaAdaptMap != null) {
                    mt.loadAdaptationMap(saved.mahoragaAdaptMap);
                }
            }
            jp.canGraspAirSurface = saved.canGraspAirSurface;

            event.setJoinMessage("§6sorcerer joined");
            PaperJJK.log("[JEvent] Restored: " + player.getName()
                    + " tech=" + saved.techniqueName
                    + " CE=" + (int) saved.currentCE + "/" + (int) saved.maxCE);
        } else {
            // 신규 플레이어 — 일반인으로 생성
            jp = new JPlayer(player, 200.0, false);
            event.setJoinMessage("§anew sorcerer joined");
            PaperJJK.log("[JEvent] New player: " + player.getName());
        }

        JEntityManager.instance.register(jp);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        JPlayer jp = JEntityManager.instance.getPlayer(uuid);
        if (jp != null) {
            // 실행 중인 스킬 전부 종료
            for (var skill : jp.getActiveSkills()) {
                skill.end();
            }
            // 데이터 저장
            JData.save(jp);
        }

        JEntityManager.instance.unregister(uuid);
        PaperJJK.log("[JEvent] " + player.getName() + " quit, saved and unregistered.");
    }
}
