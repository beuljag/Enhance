package kr.server.enhance;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * MythicMobs 아이템 확인. MythicMobs가 없어도 플러그인이 동작하도록 리플렉션으로 부른다.
 * 모델 무기(예: forged_로 시작하는 아이템)는 재료가 활, 쇠뇌, 폭죽 별이어도 공격력 기반으로 피해를 계산하므로
 * 강화에서는 근접 무기처럼 공격력을 올려 준다.
 */
final class MythicHook {

    private static Object itemManager;
    private static Method typeMethod;
    private static List<String> weaponPrefixes = List.of();

    private MythicHook() {
    }

    static void load(Plugin plugin) {
        itemManager = null;
        typeMethod = null;
        weaponPrefixes = plugin.getConfig().getStringList("mythic.weapon-prefixes").stream()
                .map(prefix -> prefix.toLowerCase(Locale.ROOT)).toList();
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            return;
        }
        try {
            Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object instance = mythic.getMethod("inst").invoke(null);
            Object manager = mythic.getMethod("getItemManager").invoke(instance);
            typeMethod = manager.getClass().getMethod("getMythicTypeFromItem", ItemStack.class);
            itemManager = manager;
            plugin.getLogger().info("MythicMobs 모델 무기 강화를 켰습니다. 대상: " + weaponPrefixes);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("MythicMobs 아이템을 확인할 수 없어 모델 무기 강화를 끕니다: " + e);
        }
    }

    /** MythicMobs 아이템 ID. MythicMobs 아이템이 아니면 null. */
    static String type(ItemStack item) {
        if (itemManager == null || item == null || item.getType().isAir()) {
            return null;
        }
        try {
            Object type = typeMethod.invoke(itemManager, item);
            return type instanceof String name && !name.isEmpty() ? name : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 공격력 기반으로 피해를 주는 MythicMobs 모델 무기인지. */
    static boolean isWeapon(ItemStack item) {
        String type = type(item);
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return weaponPrefixes.stream().anyMatch(lower::startsWith);
    }
}
