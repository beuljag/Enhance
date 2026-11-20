package kr.server.enhance;

import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

final class EnhanceListener implements Listener {

    private final EnhanceItems items;

    EnhanceListener(EnhanceItems items) {
        this.items = items;
    }

    /** 모델 무기는 손에 들 때 강화가 지워지므로, MythicMobs가 처리를 끝낸 뒤 다시 입힌다. */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        restoreLater(event.getPlayer(), event.getNewSlot());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        restoreLater(event.getPlayer(), event.getPlayer().getInventory().getHeldItemSlot());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreLater(event.getPlayer(), event.getPlayer().getInventory().getHeldItemSlot());
    }

    private void restoreLater(Player player, int slot) {
        Plugin plugin = JavaPlugin.getPlugin(EnhancePlugin.class);
        for (long delay : new long[] {3L, 10L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                ItemStack item = player.getInventory().getItem(slot);
                if (items.restore(item)) {
                    player.getInventory().setItem(slot, item);
                }
            }, delay);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnhanceMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory() == event.getInventory()) {
            menu.clickTop(event.getRawSlot(), event.getClick());
        } else {
            menu.clickInventory(event.getSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EnhanceMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EnhanceMenu menu) {
            menu.onClose();
        }
    }

    /** 화살에는 수정치가 적용되지 않으므로, 쏠 때의 활 수치를 화살에 적어 두었다가 맞을 때 더한다. */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        double bonus = items.projectileBonus(event.getBow());
        if (bonus > 0) {
            event.getProjectile().getPersistentDataContainer()
                    .set(items.bonusKey(), PersistentDataType.DOUBLE, bonus);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        double bonus = 0;
        if (event.getDamager() instanceof Trident trident) {
            bonus = items.projectileBonus(trident.getItemStack());
        } else if (event.getDamager() instanceof AbstractArrow arrow) {
            bonus = arrow.getPersistentDataContainer().getOrDefault(items.bonusKey(), PersistentDataType.DOUBLE, 0.0);
        }
        if (bonus > 0) {
            event.setDamage(event.getDamage() + bonus);
        }
    }
}
