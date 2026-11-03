package kr.server.enhance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 플레이어 한 명의 강화 창. 장비를 창에 옮기지 않고 인벤토리 칸을 가리키기만 해서 아이템 복제와 유실을 막는다.
 * 창의 모든 클릭은 취소되므로 창이 열려 있는 동안 가리키는 칸의 아이템은 바뀌지 않는다.
 */
final class EnhanceMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int TARGET_SLOT = 11;
    private static final int PROTECTION_SLOT = 13;
    private static final int BUTTON_SLOT = 15;
    private static final int BATCH_5_SLOT = 16;
    private static final int BATCH_10_SLOT = 17;
    private static final long BATCH_STEP_TICKS = 6;
    /** 연타로 결과를 보기 전에 다시 강화되는 일을 막는 간격. */
    private static final long CLICK_COOLDOWN_MILLIS = 400;
    private static final int ROW_START = 9;
    private static final int ROW_CENTER = 13;
    private static final int ROW_END = 17;
    private static final long FRAME_TICKS = 4;
    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));

    private final Plugin plugin;
    private final EnhanceItems items;
    private final EnhanceData data;
    private final VaultHook vault;
    private final StarEffects effects;
    private final Player player;
    private final Inventory inventory;

    private int slot = -1;
    private long nextClick;
    private boolean useProtection = true;
    private BukkitTask animation;
    private int frame;

    private BukkitTask batch;
    private int batchTotal;
    private int batchDone;
    private String batchBefore;
    private final Map<EnhanceData.Outcome, Integer> batchCounts = new EnumMap<>(EnhanceData.Outcome.class);

    EnhanceMenu(Plugin plugin, EnhanceItems items, VaultHook vault, StarEffects effects, Player player) {
        this.plugin = plugin;
        this.items = items;
        this.data = items.data();
        this.vault = vault;
        this.effects = effects;
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
        if (animation == null && batch == null
                && EnhanceItems.isEquipment(player.getInventory().getItem(clickedSlot))) {
            slot = clickedSlot;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            render();
        }
    }

    void clickTop(int rawSlot, ClickType click) {
        if (animation != null || (click != ClickType.LEFT && click != ClickType.RIGHT)
                || System.currentTimeMillis() < nextClick) {
            return;
        }
        if (batch != null) {
            finishBatch();
            render();
            return;
        }
        if (rawSlot == PROTECTION_SLOT) {
            useProtection = !useProtection;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
            render();
            return;
        }
        int count = switch (rawSlot) {
            case BUTTON_SLOT -> 1;
            case BATCH_5_SLOT -> 5;
            case BATCH_10_SLOT -> 10;
            default -> 0;
        };
        if (count > 0) {
            nextClick = System.currentTimeMillis() + CLICK_COOLDOWN_MILLIS;
            if (count == 1) {
                attempt();
            } else {
                startBatch(count);
            }
        }
    }

    /** 연속 강화. 한 번씩 돈을 내고 시도하므로 도중에 멈추면 남은 횟수의 돈은 빠지지 않는다. */
    private void startBatch(int count) {
        ItemStack item = target();
        if (item == null || items.nextStage(item) != Stage.ENHANCE) {
            return;
        }
        batchTotal = count;
        batchDone = 0;
        batchBefore = items.describe(item);
        batchCounts.clear();
        batch = Bukkit.getScheduler().runTaskTimer(plugin, this::batchStep, 0L, BATCH_STEP_TICKS);
    }

    private void batchStep() {
        ItemStack item = target();
        EnhanceData.Outcome outcome = item == null ? null : enhance(item, true);
        if (outcome == null) {
            finishBatch();
            render();
            return;
        }
        batchDone++;
        batchCounts.merge(outcome, 1, Integer::sum);
        // 파괴가 나오면 귀속 저주나 파괴 방지권으로 남았어도 멈춘다. 돈과 방지권을 한꺼번에 쓰지 않게 하기 위해서다.
        ItemStack after = target();
        if (outcome == EnhanceData.Outcome.DESTROY || batchDone >= batchTotal
                || after == null || items.nextStage(after) != Stage.ENHANCE) {
            finishBatch();
        }
        render();
    }

    private void finishBatch() {
        batch.cancel();
        batch = null;
        if (batchDone > 0) {
            ItemStack item = target();
            player.sendMessage(EnhancePlugin.color(rawMessage("batch-result")
                    .replace("%count%", Integer.toString(batchDone))
                    .replace("%success%", batchCount(EnhanceData.Outcome.SUCCESS))
                    .replace("%fail%", batchCount(EnhanceData.Outcome.FAIL))
                    .replace("%drop%", batchCount(EnhanceData.Outcome.DROP))
                    .replace("%before%", batchBefore)
                    .replace("%after%", item != null ? items.describe(item) : rawMessage("batch-destroyed"))));
        }
    }

    private String batchCount(EnhanceData.Outcome outcome) {
        return Integer.toString(batchCounts.getOrDefault(outcome, 0));
    }

    private void attempt() {
        ItemStack item = target();
        Stage stage = item == null ? null : items.nextStage(item);
        if (stage == Stage.ENHANCE) {
            enhance(item, false);
        } else if (stage == Stage.STAR) {
            startStarAnimation();
        }
        render();
    }

    /** 왼쪽과 오른쪽에서 별이 가운데로 모인 뒤에 별을 준다. 주문서는 끝날 때 쓰므로 도중에 창을 닫으면 그대로 남는다. */
    private void startStarAnimation() {
        if (items.countScrolls(player) == 0) {
            refuse(EnhancePlugin.color(rawMessage("no-scroll").replace("%scroll%", scrollName())));
            return;
        }
        frame = 0;
        renderFrame();
        animation = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            frame++;
            if (ROW_START + frame >= ROW_CENTER) {
                finishStarAnimation();
            } else {
                renderFrame();
            }
        }, FRAME_TICKS, FRAME_TICKS);
    }

    private void renderFrame() {
        ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, pane);
        }
        ItemStack star = icon(Material.NETHER_STAR, "&e★", List.of());
        inventory.setItem(ROW_START + frame, star);
        inventory.setItem(ROW_END - frame, star);
        ItemStack item = target();
        if (item != null) {
            inventory.setItem(ROW_CENTER, item.clone());
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.8f + frame * 0.3f);
    }

    private void finishStarAnimation() {
        cancelAnimation();
        ItemStack item = target();
        if (item != null && items.nextStage(item) == Stage.STAR && items.consumeScroll(player)) {
            gainStar(item);
        }
        player.closeInventory();
    }

    private void cancelAnimation() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
    }

    void onClose() {
        cancelAnimation();
        if (batch != null) {
            finishBatch();
        }
    }

    private void refuse(Component message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
    }

    /** 별을 얻으면 0강으로 돌아가서 다시 최대 강화를 노린다. */
    private void gainStar(ItemStack item) {
        String before = items.describe(item);
        int stars = items.stars(item) + 1;
        items.apply(item, 0, stars);
        player.sendMessage(result("star", before, item));
        String effect = items.lastEffectText(item);
        if (effect != null) {
            player.sendMessage(EnhancePlugin.color(rawMessage("star-effect").replace("%effect%", effect)));
        }
        player.showTitle(Title.title(
                EnhancePlugin.color(rawMessage("star-title").replace("%stars%", Integer.toString(stars))),
                effect != null ? EnhancePlugin.color(rawMessage("star-subtitle").replace("%effect%", effect))
                        : Component.empty(),
                TITLE_TIMES));
        effects.play(player, stars);
    }

    /** 한 번 시도하고 결과를 돌려준다. 돈을 내지 못하면 null. quiet면 성공, 실패, 하락의 채팅을 생략한다. */
    private EnhanceData.Outcome enhance(ItemStack item, boolean quiet) {
        int level = items.level(item);
        int stars = items.stars(item);
        EnhanceData.Chance chance = data.chance(level, stars);
        if (!pay(chance.cost())) {
            return null;
        }
        String before = items.describe(item);
        EnhanceData.Outcome outcome = chance.roll(ThreadLocalRandom.current().nextDouble(100));
        switch (outcome) {
            case SUCCESS -> {
                items.apply(item, level + 1, stars);
                announce("success", before, item, quiet);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            case FAIL -> {
                announce("fail", before, item, quiet);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1f);
            }
            case DROP -> {
                items.apply(item, Math.max(0, level - 1), stars);
                announce("drop", before, item, quiet);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_BREAK, 0.8f, 1f);
            }
            case DESTROY -> destroy(item, before);
        }
        return outcome;
    }

    private void announce(String key, String before, ItemStack item, boolean quiet) {
        if (!quiet) {
            player.sendMessage(result(key, before, item));
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
        if (useProtection && items.consumeProtection(player)) {
            player.sendMessage(EnhancePlugin.color(rawMessage("destroy-protected")
                    .replace("%left%", Integer.toString(items.countProtections(player)))));
            player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
            return;
        }
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
        if (animation != null) {
            return;
        }
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, pane);
        }
        ItemStack item = target();
        inventory.setItem(TARGET_SLOT, item != null ? item.clone() : icon(Material.BARRIER, "&c강화할 장비 없음",
                List.of("&7아래 인벤토리에서 장비를 클릭해 주세요.", "&7입고 있는 갑옷은 벗어야 고를 수 있습니다.")));
        inventory.setItem(BUTTON_SLOT, button(item));
        if (batch != null) {
            inventory.setItem(BATCH_5_SLOT, batchProgressIcon());
            inventory.setItem(BATCH_10_SLOT, batchProgressIcon());
        } else if (item != null && items.nextStage(item) == Stage.ENHANCE) {
            inventory.setItem(BATCH_5_SLOT, batchButton(item, 5));
            inventory.setItem(BATCH_10_SLOT, batchButton(item, 10));
        }
        if (item != null && items.nextStage(item) == Stage.ENHANCE) {
            inventory.setItem(PROTECTION_SLOT, protectionButton());
        }
    }

    private String protectionName() {
        String name = data.protection().name();
        return name != null ? name : "protection";
    }

    private ItemStack protectionButton() {
        ItemStack button = icon(data.protection().material(),
                protectionName() + (useProtection ? " &a사용" : " &7사용 안 함"), List.of(
                        "&7파괴가 나오면 1장을 써서 장비를 지킵니다.",
                        "&7강화와 별은 그대로 유지됩니다.",
                        "",
                        "&7가진 개수: &f" + items.countProtections(player) + "개",
                        "&e클릭하면 바꿉니다."));
        button.setAmount(Math.clamp(items.countProtections(player), 1, button.getMaxStackSize()));
        return button;
    }

    private ItemStack batchProgressIcon() {
        return icon(Material.CLOCK, "&e강화 중... " + batchDone + "/" + batchTotal, List.of("&7클릭하거나 창을 닫으면 멈춥니다."));
    }

    private ItemStack batchButton(ItemStack item, int count) {
        double cost = data.chance(items.level(item), items.stars(item)).cost();
        ItemStack button = icon(Material.ANVIL, "&a" + count + "연 강화", List.of(
                "&7한 번씩 차례로 최대 " + count + "번 강화합니다.",
                "&7+" + data.maxLevel() + " 도달, 파괴, 돈 부족 시 멈춥니다.",
                "",
                "&6다음 1회 금액 &f" + vault.format(cost),
                "&7단계가 오르면 금액도 오릅니다.",
                "&e클릭하면 시작합니다."));
        button.setAmount(count);
        return button;
    }

    private ItemStack button(ItemStack item) {
        if (item == null) {
            return icon(Material.GRAY_DYE, "&7장비를 먼저 골라 주세요", List.of());
        }
        Stage stage = items.nextStage(item);
        if (stage == null) {
            return icon(Material.NETHER_STAR, "&6더 강화할 수 없습니다", List.of());
        }
        return stage == Stage.ENHANCE
                ? icon(Material.ANVIL, "&a강화하기", enhanceLore(item))
                : icon(Material.NETHER_STAR, "&e스타캐치", starLore(item));
    }

    private List<String> enhanceLore(ItemStack item) {
        int level = items.level(item);
        EnhanceData.Chance chance = data.chance(level, items.stars(item));
        double fail = 100 - chance.success() - chance.drop() - chance.destroy();
        List<String> lore = new ArrayList<>();
        lore.add("&f+" + level + " &7-> &e+" + (level + 1));
        lore.add("");
        lore.add("&a성공 " + EnhanceItems.number(chance.success()) + "%");
        lore.add("&7실패 " + EnhanceItems.number(fail) + "%");
        lore.add("&c단계 하락 " + EnhanceItems.number(chance.drop()) + "%");
        lore.add("&4파괴 " + EnhanceItems.number(chance.destroy()) + "%");
        if (chance.destroy() > 0) {
            if (useProtection && items.countProtections(player) > 0) {
                lore.add("&b파괴가 나오면 " + protectionName() + "&b을 씁니다.");
            } else {
                lore.add(items.isBound(item) ? "&8귀속 저주로 파괴되지 않지만, 강화와 별이 초기화됩니다." : "&8파괴되면 장비가 사라집니다.");
            }
        }
        lore.add("");
        if (vault.isAvailable()) {
            lore.add("&6필요한 금액 &f" + vault.format(chance.cost()));
            lore.add("&7가진 돈 &f" + vault.format(vault.balance(player)));
        } else {
            lore.add("&c지금은 강화할 수 없습니다.");
        }
        lore.add("&e클릭하면 강화합니다.");
        return lore;
    }

    private List<String> starLore(ItemStack item) {
        int stars = items.stars(item);
        List<String> lore = new ArrayList<>();
        lore.add("&f별 " + stars + "개 &7-> &e별 " + (stars + 1) + "개");
        lore.add("&7강화는 &f+0&7으로 돌아갑니다.");
        int poolSize = data.pool(Category.of(item.getType())).size();
        if (poolSize > 0) {
            lore.add("&b" + poolSize + "가지 효과 중 하나를 무작위로 얻습니다.");
        }
        lore.add("");
        lore.add("&7가진 " + scrollName() + "&7: &f" + items.countScrolls(player) + "개");
        lore.add("&e클릭하면 사용합니다.");
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
