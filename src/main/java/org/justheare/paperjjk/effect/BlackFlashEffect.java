package org.justheare.paperjjk.effect;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.justheare.paperjjk.PaperJJK;
import org.justheare.paperjjk.damage.DamageInfo;

/**
 * 흑섬(Black Flash) 발동 시 멀티틱 시각·청각 이펙트.
 *
 * 타임라인 (틱 기준, 1틱 = 50ms):
 *   틱 0        — 소닉붐 사운드 + 히트 지점 강한 FLASH (흰색·붉은색)
 *   틱 0 ~ 8   — 공격자 주변 퍼지는 INSTANT_EFFECT (검은색·암적색)
 *   틱 1 ~ 4   — 히트 지점 잔상 FLASH (점점 작아짐)
 *   틱 5 ~ 10  — 공격자 위치 FLASH 점멸 (희미하게)
 */
public class BlackFlashEffect {

    private static final Particle.DustOptions DUST_BLACK =
            new Particle.DustOptions(Color.BLACK, 1.2f);
    private static final Particle.DustOptions DUST_DARK_RED =
            new Particle.DustOptions(Color.fromRGB(128, 0, 0), 1.2f);

    /**
     * 흑섬 이펙트 시작.
     *
     * @param attacker 공격자 (파티클 출처)
     * @param victim   피격자 (히트 지점)
     * @param bonus    흑섬 적용된 신체강화 보너스 (attackOutput 단위) — 폭발 파워 계산용
     */
    public static void trigger(LivingEntity attacker, LivingEntity victim, double bonus) {
        Location hitLoc    = victim.getLocation().add(0, victim.getHeight() / 2.0, 0);
        Location explosionLoc = victim.getLocation().add(0, 2, 0);
        // ── 폭발 (블록 파괴 없음, 데미지 없음 — 이펙트 전용) ─────────────
        // power = hpDamage / 10, 최대 3.0 (TNT 급)
        float hpDamage = (float) DamageInfo.outputToDamage(bonus);
        float power    = Math.max(0.3f, Math.min(3.0f, hpDamage / 10.0f));
        victim.setVelocity((attacker.getEyeLocation().getDirection().multiply(power*3.0+2)));
        explosionLoc.getWorld().createExplosion(explosionLoc, (float) (power*0.5), false, true, attacker);

        // ── 틱 0: 즉시 이펙트 ─────────────────────────────────────────────
        attacker.getWorld().playSound(hitLoc, Sound.ENTITY_WARDEN_SONIC_BOOM,
                SoundCategory.PLAYERS, 3.0f, 1.0f);

        spawnHitFlash(hitLoc, 10, 1.8);
        spawnAttackerEffect(attacker, 0);

        // ── 멀티틱 이펙트 ─────────────────────────────────────────────────
        new BukkitRunnable() {
            int tick = 1;

            @Override
            public void run() {
                if (!attacker.isValid() || tick > 10) {
                    cancel();
                    return;
                }

                Location aLoc = attacker.getLocation().add(0, attacker.getHeight() / 2.0, 0);

                // 틱 1~4: 히트 지점 잔상 FLASH (count·spread 점점 감소)
                if (tick <= 4) {
                    double spread = 1.8 - tick * 0.3;
                    int count    = Math.max(2, 8 - tick * 2);
                    spawnHitFlash(hitLoc, count, spread);
                }

                // 틱 0~8: 공격자 주변 퍼지는 INSTANT_EFFECT
                if (tick <= 8) {
                    spawnAttackerEffect(attacker, tick);
                }

                // 틱 5~10: 공격자 위치 희미한 FLASH 점멸 (짝수 틱만)
                if (tick >= 5 && tick % 2 == 0) {
                    attacker.getWorld().spawnParticle(
                            Particle.FLASH, aLoc,
                            2, 0.4, 0.4, 0.4, 5.0,
                            Color.fromARGB(80, 255, 50, 50));
                    attacker.getWorld().spawnParticle(
                            Particle.FLASH, aLoc,
                            1, 0.3, 0.3, 0.3, 3.0,
                            Color.fromARGB(80, 255, 255, 255));
                }

                tick++;
            }
        }.runTaskTimer(PaperJJK.instance, 1L, 1L);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    /** 히트 지점에 흰색·붉은색 FLASH 동시 스폰 */
    private static void spawnHitFlash(Location loc, int count, double spread) {
        loc.getWorld().spawnParticle(
                Particle.FLASH, loc, count,
                spread, spread, spread, 10.0,
                Color.fromARGB(128, 255, 255, 255));
        loc.getWorld().spawnParticle(
                Particle.FLASH, loc, count,
                spread, spread, spread, 10.0,
                Color.fromARGB(128, 255, 50, 50));
    }

    /**
     * 공격자 주변 퍼지는 검은색·암적색 INSTANT_EFFECT.
     * tick이 클수록 퍼짐 반경 증가, 밀도 감소.
     */
    private static void spawnAttackerEffect(LivingEntity attacker, int tick) {
        double spread = 0.3 + tick * 0.12;
        int count     = Math.max(5, 30 - tick * 3)/10;

        Location aLoc = attacker.getLocation().add(0, attacker.getHeight() / 2.0, 0);

        attacker.getWorld().spawnParticle(
                Particle.DUST, aLoc, count,
                spread, spread, spread, 0, DUST_BLACK);
        attacker.getWorld().spawnParticle(
                Particle.DUST, aLoc, count,
                spread, spread, spread, 0, DUST_DARK_RED);
    }
}
