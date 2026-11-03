package kr.server.enhance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnhancePlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private EnhanceData data;

    static Component color(String text) {
        return LEGACY.deserialize(text);
    }

    /** 아이템 이름과 설명용. 기본 기울임을 없앤다. */
    static Component plain(String text) {
        return color(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new EnhanceData(this);
        data.load();
        EnhanceItems items = new EnhanceItems(this, data);

        getServer().getPluginManager().registerEvents(new EnhanceListener(items), this);

        EnhanceCommand command = new EnhanceCommand(this, data, items, new VaultHook(getLogger()));
        PluginCommand registered = getCommand("enhance");
        registered.setExecutor(command);
        registered.setTabCompleter(command);

        getLogger().info("강화 " + data.maxLevel() + "단계, 별 " + data.maxStars() + "개를 불러왔습니다.");
    }

    @Override
    public void onDisable() {
        // 리스너가 사라진 뒤에 창의 아이콘을 꺼내지 못하게 닫는다.
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof EnhanceMenu) {
                player.closeInventory();
            }
        }
    }

    void reload() {
        reloadConfig();
        data.load();
    }
}
