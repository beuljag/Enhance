package kr.server.enhance;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Logger;

/**
 * Vault 경제 연동. Vault가 없는 서버에서도 로드되도록 API를 리플렉션으로 호출한다.
 * 경제 플러그인은 나중에 등록될 수 있으므로 쓸 때마다 찾는다.
 */
final class VaultHook {

    private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

    private final Logger logger;

    VaultHook(Logger logger) {
        this.logger = logger;
    }

    private Class<?> economyType() throws ClassNotFoundException {
        return Class.forName(ECONOMY);
    }

    private Object economy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }
        try {
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyType());
            return registration == null ? null : registration.getProvider();
        } catch (ClassNotFoundException | LinkageError e) {
            logger.warning("Vault API 호출 실패: " + e);
            return null;
        }
    }

    boolean isAvailable() {
        return economy() != null;
    }

    double balance(OfflinePlayer player) {
        Object economy = economy();
        if (economy == null) {
            return 0;
        }
        try {
            return (double) economyType().getMethod("getBalance", OfflinePlayer.class).invoke(economy, player);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("Vault API 호출 실패: " + e);
            return 0;
        }
    }

    /** 돈을 뺀다. 잔액이 모자라거나 실패하면 false이고 돈은 그대로다. */
    boolean withdraw(OfflinePlayer player, double amount) {
        Object economy = economy();
        if (economy == null || balance(player) < amount) {
            return false;
        }
        try {
            Object response = economyType().getMethod("withdrawPlayer", OfflinePlayer.class, double.class)
                    .invoke(economy, player, amount);
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("Vault API 호출 실패: " + e);
            return false;
        }
    }

    /** 경제 플러그인의 화폐 표기로 바꾼다. */
    String format(double amount) {
        Object economy = economy();
        if (economy != null) {
            try {
                return (String) economyType().getMethod("format", double.class).invoke(economy, amount);
            } catch (ReflectiveOperationException | LinkageError e) {
                logger.warning("Vault API 호출 실패: " + e);
            }
        }
        return EnhanceItems.number(amount);
    }
}
