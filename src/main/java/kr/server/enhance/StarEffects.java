package kr.server.enhance;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

/** 별을 얻었을 때 주변에도 보이는 연출: 플레이어 주위를 돌며 올라가는 별과 파티클. */
final class StarEffects {

    private static final int DURATION_TICKS = 30;
    private static final double START_RADIUS = 1.0;
    private static final double RISE_HEIGHT = 2.2;

    private final Plugin plugin;
    private final Set<ItemDisplay> displays = new HashSet<>();

    StarEffects(Plugin plugin) {
        this.plugin = plugin;
    }

    /** stars는 얻은 뒤의 별 개수. 별이 많을수록 파티클이 많아진다. */
    void play(Player player, int stars) {
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f);
        ItemDisplay star = player.getWorld().spawn(player.getLocation(), ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setItemStack(new ItemStack(Material.NETHER_STAR));
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTeleportDuration(1);
        });
        displays.add(star);

        new BukkitRunnable() {
            private int age;

            @Override
            public void run() {
                if (!player.isOnline() || age >= DURATION_TICKS) {
                    star.remove();
                    displays.remove(star);
                    cancel();
                    if (player.isOnline()) {
                        burst(player, stars);
                    }
                    return;
                }
                animate(player, star, age, stars);
                age++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void animate(Player player, ItemDisplay star, int age, int stars) {
        double progress = (double) age / DURATION_TICKS;
        double angle = age * 0.5;
        double radius = START_RADIUS * (1 - progress * 0.7);
        double height = progress * RISE_HEIGHT;
        Location base = player.getLocation();

        Location position = orbit(base, angle, radius, height);
        position.setYaw(age * 36f);
        star.teleport(position);

        World world = player.getWorld();
        int count = 1 + stars / 3;
        world.spawnParticle(Particle.END_ROD, orbit(base, angle, radius, height), count, 0.05, 0.05, 0.05, 0);
        world.spawnParticle(Particle.ENCHANT, orbit(base, angle + Math.PI, radius, height), count * 2, 0.1, 0.1, 0.1, 0.2);
        if (age % 6 == 0) {
            world.playSound(base, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, (float) (0.8 + progress));
        }
    }

    private Location orbit(Location base, double angle, double radius, double height) {
        return base.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    private void burst(Player player, int stars) {
        Location center = player.getLocation().add(0, 1.2, 0);
        World world = player.getWorld();
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 40 + stars * 8, 0.4, 0.6, 0.4, 0.4);
        world.spawnParticle(Particle.FIREWORK, center, 15 + stars * 3, 0.3, 0.4, 0.3, 0.15);
        world.playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f);
    }

    /** 플러그인이 꺼질 때 남은 별을 지운다. */
    void stopAll() {
        displays.forEach(ItemDisplay::remove);
        displays.clear();
    }
}
