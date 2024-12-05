package org.ledat.mCGPT;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;


public class MCGPT extends JavaPlugin implements Listener {
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Set<Player> activePlayers = new HashSet<>();
    private String apiKey;
    private FileConfiguration config;
    private final long TIME_FRAME_MS = TimeUnit.MINUTES.toMillis(1);
    private final int MAX_REQUESTS_PER_MINUTE = 60;
    private final int MAX_ACTIVE_USERS = 5;
    private final Set<UUID> activeUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<Long> requestTimestamps = new ConcurrentLinkedDeque<>();
    private final Map<UUID, Boolean> chatEnabled = new HashMap<>();
    private final Map<UUID, List<JsonObject>> chatHistories = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        apiKey = config.getString("openai-api-key");

        if (apiKey == null || apiKey.isEmpty()) {
            getLogger().severe("[Tuấn Anh] API Key chưa được cài đặt! hãy điền trong config.yml.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MCGPT has been enabled!");
    }

    @Override
    public void onDisable() {
        httpClient.dispatcher().executorService().shutdown();
        getLogger().info("MCGPT has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chatgpt")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Lệnh chỉ dành cho người chơi.");
                return true;
            }

            Player player = (Player) sender;
            if (!player.hasPermission("mcgpt.toggle")) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("no-permission")));
                return true;
            }

            if (activePlayers.contains(player)) {
                activePlayers.remove(player);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("toggle.disabled")));
            } else {
                activePlayers.add(player);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("toggle.enabled")));
            }

            return true;
        } else if (command.getName().equalsIgnoreCase("chatgptreload")) {
            if (!sender.hasPermission("mcgpt.reload")) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("no-permission")));
                return true;
            }

            reloadConfig();
            config = getConfig();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("message-reload")));
            return true;
        }

        return false;
    }


    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        // Kiểm tra nếu người chơi đã bật tính năng ChatGPT
        if (!activePlayers.contains(player)) return;

        // Hủy sự kiện chat để ngăn việc gửi tin nhắn đến mọi người chơi
        event.setCancelled(true);

        String playerMessage = event.getMessage();

        // Hiển thị tin nhắn cho chính người chơi
        List<String> formats = config.getStringList("format");
        String playerFormat = ChatColor.translateAlternateColorCodes('&', formats.get(0))
                .replace("%player%", player.getName())
                .replace("%message%", playerMessage);
        player.sendMessage(playerFormat);

        // Thông báo rằng AI đang phản hồi
        player.sendMessage(ChatColor.AQUA + "ChatGPT đang suy nghĩ...");

        // Xử lý phản hồi từ ChatGPT trong một tác vụ bất đồng bộ
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String aiResponse = getChatGPTResponse(player, playerMessage); // Thêm tham số player
                String aiFormat = ChatColor.translateAlternateColorCodes('&', formats.get(1))
                        .replace("%player%", player.getName())
                        .replace("%message%", aiResponse);

                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(aiFormat));
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(this, () -> player.sendMessage(ChatColor.RED + "Error: " + e.getMessage()));
            }
        });
    }
    private boolean toggleChatGPT(Player player) {
        UUID playerId = player.getUniqueId();
        if (chatEnabled.getOrDefault(playerId, false)) {
            chatEnabled.put(playerId, false);
            activeUsers.remove(playerId);
            return false;
        } else {
            if (activeUsers.size() >= MAX_ACTIVE_USERS) {
                player.sendMessage(ChatColor.RED + "Quá nhiều người sử dụng cùng lúc.");
                return false;
            }
            chatEnabled.put(playerId, true);
            activeUsers.add(playerId);
            return true;
        }
    }

    private synchronized boolean canSendRequest() {
        long now = System.currentTimeMillis();

        while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() > TIME_FRAME_MS) {
            requestTimestamps.poll();
        }

        if (requestTimestamps.size() < MAX_REQUESTS_PER_MINUTE) {
            requestTimestamps.add(now);
            return true;
        }

        return false;
    }

    private String getChatGPTResponse(Player player, String message) throws IOException {
        if (!canSendRequest()) {
            return "API rate limit exceeded. Please wait and try again later.";
        }

        UUID playerId = player.getUniqueId();
        List<JsonObject> chatHistory = chatHistories.computeIfAbsent(playerId, k -> new ArrayList<>());

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", message);
        chatHistory.add(userMessage);

        JsonArray messagesArray = new JsonArray();
        for (JsonObject chatMessage : chatHistory) {
            messagesArray.add(chatMessage);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "gpt-3.5-turbo");
        payload.add("messages", messagesArray);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 429) {
                return "API rate limit exceeded. Please wait and try again later.";
            }

            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            JsonObject responseBody = JsonParser.parseString(response.body().string()).getAsJsonObject();
            String aiMessage = responseBody.get("choices").getAsJsonArray().get(0).getAsJsonObject()
                    .get("message").getAsJsonObject().get("content").getAsString().trim();

            JsonObject aiResponse = new JsonObject();
            aiResponse.addProperty("role", "assistant");
            aiResponse.addProperty("content", aiMessage);
            chatHistory.add(aiResponse);

            return aiMessage;
        }
    }

}