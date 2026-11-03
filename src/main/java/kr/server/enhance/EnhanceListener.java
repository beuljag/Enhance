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
import org.bukkit.persistence.PersistentDataType;

final class EnhanceListener implements Listener {

    private final EnhanceItems items;

    EnhanceListener(EnhanceItems items) {
        this.items = items;
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
