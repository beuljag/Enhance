package kr.server.enhance;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** 강화할 수 있는 장비의 종류. 종류마다 오르는 능력치가 다르다. */
enum Category {
    MELEE("melee"),
    RANGED("ranged"),
    ARMOR("armor"),
    TOOL("tool");

    final String key;

    Category(String key) {
        this.key = key;
    }

    /**
     * 아이템의 종류를 돌려준다. 강화할 수 없는 아이템이면 null.
     * MythicMobs 모델 무기는 재료와 상관없이 근접 무기(공격력)로 본다. 모델 방어구는 재료 그대로 방어구다.
     */
    static Category of(ItemStack item) {
        if (item == null) {
            return null;
        }
        Category byMaterial = of(item.getType());
        if (byMaterial != ARMOR && MythicHook.isWeapon(item)) {
            return MELEE;
        }
        return byMaterial;
    }

    /** 재료만으로 본 장비의 종류. 강화할 수 없는 재료면 null. */
    static Category of(Material material) {
        String name = material.name();
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || material == Material.MACE
                || material == Material.TRIDENT) {
            return MELEE;
        }
        if (material == Material.BOW || material == Material.CROSSBOW) {
            return RANGED;
        }
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || material == Material.ELYTRA || material == Material.SHIELD) {
            return ARMOR;
        }
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) {
            return TOOL;
        }
        return null;
    }
}
