package kr.server.enhance;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 플레이어 한 명의 강화 창. 장비를 창에 옮기지 않고 인벤토리 칸을 가리키기만 해서 아이템 복제와 유실을 막는다.
 * 창의 모든 클릭은 취소되므로 창이 열려 있는 동안 가리키는 칸의 아이템은 바뀌지 않는다.
 */
final class EnhanceMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int TARGET_SLOT = 11;
    private static final int BUTTON_SLOT = 15;
    /** 연타로 결과를 보기 전에 다시 강화되는 일을 막는 간격. */
    private static final long CLICK_COOLDOWN_MILLIS = 400;

    private final Plugin plugin;
    private final EnhanceItems items;
    private final EnhanceData data;
    private final VaultHook vault;
    private final Player player;
    private final Inventory inventory;

    private int slot = -1;
    private long nextClick;

    EnhanceMenu(Plugin plugin, EnhanceItems items, VaultHook vault, Player player) {
        this.plugin = plugin;
        this.items = items;
        this.data = items.data();
        this.vault = vault;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, SIZE, EnhancePlugin.color("&8장비 강화"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void open() {
        int held = player.getInventory().getHeldItemSlot();
        if (EnhanceItems.isEquipment(player.getInventory().getItem(held))) {
            slot = held;
        }
        render();
        player.openInventory(inventory);
    }

    private ItemStack target() {
        if (slot < 0) {
            return null;
        }
        ItemStack item = player.getInventory().getItem(slot);
        return EnhanceItems.isEquipment(item) ? item : null;
    }

    /** 아래쪽 플레이어 인벤토리를 클릭하면 그 장비를 강화 대상으로 고른다. */
    void clickInventory(int clickedSlot) {
        if (EnhanceItems.isEquipment(player.getInventory().getItem(clickedSlot))) {
            slot = clickedSlot;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            render();
        }
    }

    void clickTop(int rawSlot, ClickType click) {
        if ((click == ClickType.LEFT || click == ClickType.RIGHT) && rawSlot == BUTTON_SLOT
                && System.currentTimeMillis() >= nextClick) {
            nextClick = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
            attempt();
        }
    }

    private void attempt() {
        ItemStack item = target();
        Stage stage = item == null ? null : items.nextStage(item);
        if (stage == Stage.ENHANCE) {
            enhance(item);
        } else if (stage == Stage.STAR) {
            gainStar(item);
        }
        render();
    }

    private void refuse(Component message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
    }

    /** 별을 얻으면 0강으로 돌아가서 다시 최대 강화를 노린다. */
    private void gainStar(ItemStack item) {
        if (!items.consumeScroll(player)) {
            refuse(EnhancePlugin.color(rawMessage("no-scroll").replace("%scroll%", scrollName())));
            return;
        }
        String before = items.describe(item);
        items.apply(item, 0, items.stars(item) + 1);
        player.sendMessage(result("star", before, item));
        String effect = items.lastEffectText(item);
        if (effect != null) {
            player.sendMessage(EnhancePlugin.color(rawMessage("star-effect").replace("%effect%", effect)));
        }
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    private void enhance(ItemStack item) {
        int level = items.level(item);
        int stars = items.stars(item);
        EnhanceData.Chance chance = data.chance(level);
        if (!pay(chance.cost())) {
            return;
        }
        String before = items.describe(item);
        switch (chance.roll(ThreadLocalRandom.current().nextDouble(100))) {
            case SUCCESS -> {
                items.apply(item, level + 1, stars);
                player.sendMessage(result("success", before, item));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            case FAIL -> {
                player.sendMessage(result("fail", before, item));
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1f);
            }
            case DROP -> {
                items.apply(item, Math.max(0, level - 1), stars);
                player.sendMessage(result("drop", before, item));
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.8f, 1f);
            }
            case DESTROY -> destroy(item, before);
        }
    }

    private boolean pay(double cost) {
        if (!vault.isAvailable()) {
            refuse(EnhancePlugin.color(rawMessage("no-economy")));
            return false;
        }
        if (cost > 0 && !vault.withdraw(player, cost)) {
            refuse(EnhancePlugin.color(rawMessage("no-money").replace("%cost%", vault.format(cost))));
            return false;
        }
        return true;
    }

    private void destroy(ItemStack item, String before) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.7f);
        if (items.isBound(item)) {
            items.apply(item, 0, 0);
            player.sendMessage(EnhancePlugin.color(rawMessage("destroy-bound").replace("%before%", before)));
        } else {
            player.getInventory().setItem(slot, null);
            slot = -1;
            player.sendMessage(EnhancePlugin.color(rawMessage("destroy")));
        }
    }

    private Component result(String key, String before, ItemStack item) {
        return EnhancePlugin.color(rawMessage(key).replace("%before%", before)
                .replace("%after%", items.describe(item)));
    }

    private String rawMessage(String key) {
        return plugin.getConfig().getString("messages." + key, key);
    }

    private String scrollName() {
        String name = data.scroll().name();
        return name != null ? name : "scroll";
    }

    private void render() {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, pane);
        }
        ItemStack item = target();
        inventory.setItem(TARGET_SLOT, item != null ? item.clone() : icon(Material.BARRIER, "&c강화할 장비 없음",
                List.of("&7아래 인벤토리에서 장비를 클릭하세요.", "&7입고 있는 갑옷은 벗어야 고를 수 있습니다.")));
        inventory.setItem(BUTTON_SLOT, button(item));
    }

    private ItemStack button(ItemStack item) {
        if (item == null) {
            return icon(Material.GRAY_DYE, "&7장비를 먼저 고르세요", List.of());
        }
        Stage stage = items.nextStage(item);
        if (stage == null) {
            return icon(Material.NETHER_STAR, "&6최고 단계입니다", List.of());
        }
        return stage == Stage.ENHANCE
                ? icon(Material.ANVIL, "&a강화하기", enhanceLore(item))
                : icon(Material.NETHER_STAR, "&e스타캐치", starLore(item));
    }

    private List<String> enhanceLore(ItemStack item) {
        int level = items.level(item);
        EnhanceData.Chance chance = data.chance(level);
        double fail = 100 - chance.success() - chance.drop() - chance.destroy();
        List<String> lore = new ArrayList<>();
        lore.add("&f+" + level + " &7-> &e+" + (level + 1));
        lore.add("");
        lore.add("&a성공 " + EnhanceItems.number(chance.success()) + "%");
        lore.add("&7실패(유지) " + EnhanceItems.number(fail) + "%");
        lore.add("&c하락 " + EnhanceItems.number(chance.drop()) + "%");
        lore.add("&4파괴 " + EnhanceItems.number(chance.destroy()) + "%");
        if (chance.destroy() > 0) {
            lore.add(items.isBound(item) ? "&8귀속 저주: 파괴되면 0강, 별 0개가 됩니다." : "&8파괴되면 장비가 사라집니다.");
        }
        lore.add("");
        if (vault.isAvailable()) {
            lore.add("&6비용 &f" + vault.format(chance.cost()));
            lore.add("&7보유 &f" + vault.format(vault.balance(player)));
        } else {
            lore.add("&cVault 경제 플러그인이 없습니다.");
        }
        lore.add("&e클릭해서 강화");
        return lore;
    }

    private List<String> starLore(ItemStack item) {
        int stars = items.stars(item);
        List<String> lore = new ArrayList<>();
        lore.add("&f별 " + stars + "개 &7-> &e별 " + (stars + 1) + "개");
        lore.add("&7강화 수치는 &f+0&7으로 돌아갑니다.");
        int poolSize = data.pool(Category.of(item.getType())).size();
        if (poolSize > 0) {
            lore.add("&b부가 효과 " + poolSize + "가지 중 무작위 1개를 얻습니다.");
        }
        lore.add("");
        lore.add("&f" + scrollName() + " &7보유: &f" + items.countScrolls(player) + "개");
        lore.add("&e클릭해서 사용");
        return lore;
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(EnhancePlugin.plain(name));
        meta.lore(lore.stream().map(EnhancePlugin::plain).toList());
        icon.setItemMeta(meta);
        return icon;
    }
}
