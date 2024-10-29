package org.amicia.routerecorder.client;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.TextColor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.DoubleArgumentType;


public class RouterecorderClient implements ClientModInitializer {

    public static List<Vec3d> playerPositions = new ArrayList<>();
    private static double lastX = 0.0;
    private static double lastY = 0.0;
    private static double lastZ = 0.0;
    private static double RECORDING_THRESHOLD = 0.5; // Minimum distance to record a new position
    private final static int DECIMAL_PLACES = 3; // Number of decimal places to round

    public static int lineColor = 0xFF1AFF; // Default color

    // Color presets
    private static final int COLOR_RED = 0xFF0000;
    private static final int COLOR_GREEN = 0x00FF00;
    private static final int COLOR_BLUE = 0x0000FF;
    private static final int COLOR_PURPLE = 0x6600AB;
    private static final int COLOR_PINK = 0xFF1AFF;
    private static final int COLOR_BLACK = 0x000000;
    private static final int COLOR_WHITE = 0xFFFFFF;
    private static final int COLOR_YELLOW = 0xFFFF00;
    private static final int COLOR_ORANGE = 0xFF8000;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && RouteRenderer.isDrawing && !RouteRenderer.paused) {
                // Only record position if drawing is active and not paused
                Vec3d playerPos = client.player.getPos();
                if (shouldRecordPosition(playerPos)) {
                    playerPositions.add(roundPosition(playerPos));
                    lastX = playerPos.x;
                    lastY = playerPos.y;
                    lastZ = playerPos.z;
                }
            }
        });

        // Register custom renderer
        RouteRenderer.registerRenderer();

        // Register commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("route")
                .then(ClientCommandManager.literal("record").executes(context -> {
                    playerPositions.clear();
                    RouteRenderer.startDrawing();
                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Started recording route!"));
                    lastX = 0.0; // Reset last position
                    lastY = 0.0;
                    lastZ = 0.0;
                    return 1;
                }))
                .then(ClientCommandManager.literal("pause").executes(context -> {
                    RouteRenderer.pauseDrawing();
                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route drawing paused!"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("resume").executes(context -> {
                    RouteRenderer.resumeDrawing();
                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route drawing resumed!"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("save")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    saveRouteToFile(filename);
                                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route saved to " + filename + "!"));
                                    RouteRenderer.stopDrawing();
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("load")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    loadRouteFromFile(filename);
                                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route loaded from " + filename + "!"));
                                    RouteRenderer.startDrawing(); // Start drawing the loaded route
                                    RouteRenderer.pauseDrawing();

                                    // Check if connected to the specific server
                                    if (isConnectedToWynncraft()) {
                                        // Retrieve the first line of the file for /compass command
                                        String firstLine = getFirstLineFromFile(filename);
                                        if (firstLine != null) {
                                            MinecraftClient.getInstance().player.networkHandler.sendChatCommand("compass " + firstLine);
                                        }
                                    }

                                    return 1;
                                })))
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    RouteRenderer.clear();
                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route cleared!"));
                    RouteRenderer.stopDrawing();
                    return 1;
                }))
                .then(ClientCommandManager.literal("linecolour")
                        .then(ClientCommandManager.argument("colour", StringArgumentType.string())
                                .executes(context -> {
                                    String colorName = StringArgumentType.getString(context, "colour").toLowerCase();
                                    setLineColor(colorName);
                                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Line color set to " + colorName + "!"));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("positiondistance")
                        .then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0.0))
                                .executes(context -> {
                                    double newThreshold = DoubleArgumentType.getDouble(context, "value");
                                    RECORDING_THRESHOLD = newThreshold;
                                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Position distance threshold set to " + newThreshold));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("list").executes(this::listSavedRoutes))
                .then(ClientCommandManager.literal("undo").executes(context -> {
                    undoLastPositions(context);
                    return 1;
                }))
                .then(ClientCommandManager.literal("delete")
                        .then(ClientCommandManager.argument("filename", StringArgumentType.string())
                                .executes(context -> {
                                    String filename = StringArgumentType.getString(context, "filename");
                                    deleteRouteFile(filename);
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("help").executes(context -> {
                    // Helper method to safely parse TextColor
                    TextColor commandColor = parseTextColor("#00FF00"); // Green
                    TextColor argumentColor = parseTextColor("#FFD700"); // Gold
                    TextColor descriptionColor = parseTextColor("#FFFFFF"); // White

                    // Add each command with color formatting
                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Available Commands:")
                            .styled(style -> style.withColor(parseTextColor("#00FFFF")))); // Cyan header

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route record")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Starts recording route.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route pause")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Pauses the recording.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route resume")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Resumes the recording.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route undo")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Removes the last 10 recorded positions.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route save ")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal("<filename>")
                                    .styled(style -> style.withColor(argumentColor)))
                            .append(Text.literal(" - Saves the route with the specified file name.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route load ")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal("<filename>")
                                    .styled(style -> style.withColor(argumentColor)))
                            .append(Text.literal(" - Loads the route with the specified file name.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route clear")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Clears the route & active recording.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route list")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Lists saved routes.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route linecolour ")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal("<colour>")
                                    .styled(style -> style.withColor(argumentColor)))
                            .append(Text.literal(" - Changes the line color. Options: red, green, blue, purple, pink, black, white, yellow, orange")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route positiondistance ")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal("<value>")
                                    .styled(style -> style.withColor(argumentColor)))
                            .append(Text.literal(" - Sets distance between recorded positions.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("   /route help")
                            .styled(style -> style.withColor(commandColor))
                            .append(Text.literal(" - Shows this menu.")
                                    .styled(style -> style.withColor(descriptionColor))));

                    return 1;
                }))
        ));
    }

    // Helper method to parse TextColor safely
    private TextColor parseTextColor(String hex) {
        // Convert hex string to RGB and create a TextColor
        return TextColor.fromRgb(Integer.parseInt(hex.replace("#", ""), 16));
    }
    private boolean isConnectedToWynncraft() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.getCurrentServerEntry() != null && "play.wynncraft.com".equals(client.getCurrentServerEntry().address);
    }

    private String getFirstLineFromFile(String filename) {
        File appDataDir = MinecraftClient.getInstance().runDirectory;
        File routeDirectory = new File(appDataDir, "RouteRecorder");
        File routeFile = new File(routeDirectory, filename + ".txt");

        if (!routeFile.exists()) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route " + filename + " does not exist."));
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(routeFile))) {
            return reader.readLine(); // Read first line of savefile
        } catch (IOException e) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Error loading route: " + e.getMessage()));
            return null;
        }
    }

    private void undoLastPositions(CommandContext<FabricClientCommandSource> context) {
        int positionsToRemove = Math.min(10, playerPositions.size());
        for (int i = 0; i < positionsToRemove; i++) {
            playerPositions.remove(playerPositions.size() - 1); // Remove the last position
        }
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Undid the last " + positionsToRemove + " positions."));
    }

    private void deleteRouteFile(String filename) {
        // Get Minecraft app data directory
        File appDataDir = MinecraftClient.getInstance().runDirectory;
        File routeDirectory = new File(appDataDir, "RouteRecorder");
        File routeFile = new File(routeDirectory, filename + ".txt");

        if (routeFile.exists() && routeFile.isFile()) {
            if (routeFile.delete()) {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route " + filename + " deleted successfully."));
            } else {
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Failed to delete route " + filename + "."));
            }
        } else {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route " + filename + " does not exist."));
        }
    }

    private void setLineColor(String colorName) {
        switch (colorName) {
            case "red":
                lineColor = COLOR_RED;
                break;
            case "green":
                lineColor = COLOR_GREEN;
                break;
            case "blue":
                lineColor = COLOR_BLUE;
                break;
            case "purple":
                lineColor = COLOR_PURPLE;
                break;
            case "pink":
                lineColor = COLOR_PINK;
                break;
            case "black":
                lineColor = COLOR_BLACK;
                break;
            case "white":
                lineColor = COLOR_WHITE;
                break;
            case "yellow":
                lineColor = COLOR_YELLOW;
                break;
            case "orange":
                lineColor = COLOR_ORANGE;
                break;
            default:
                String invalidColorMessage = "Invalid color name! Available options: red, green, blue, purple, pink, black, white, yellow, orange";
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(invalidColorMessage));
        }
    }

    private void saveRouteToFile(String filename) {
        // Get the Minecraft app data directory
        File appDataDir = MinecraftClient.getInstance().runDirectory;
        File routeDirectory = new File(appDataDir, "RouteRecorder");

        if (!routeDirectory.exists()) {
            routeDirectory.mkdirs(); // Create directory if it doesn't exist
        }

        File routeFile = new File(routeDirectory, filename + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(routeFile))) {
            for (Vec3d position : playerPositions) {
                writer.write(position.x + "," + position.y + "," + position.z);
                writer.newLine();
            }
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route saved to " + routeFile.getAbsolutePath()));
        } catch (IOException e) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Error saving route: " + e.getMessage()));
        }
    }

    private void loadRouteFromFile(String filename) {
        // Get Minecraft app data directory
        File appDataDir = MinecraftClient.getInstance().runDirectory;
        File routeDirectory = new File(appDataDir, "RouteRecorder");
        File routeFile = new File(routeDirectory, filename + ".txt");

        if (!routeFile.exists()) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Route " + filename + " does not exist."));
            return;
        }

        playerPositions.clear(); // Clear existing positions before loading savefile

        try (BufferedReader reader = new BufferedReader(new FileReader(routeFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] coordinates = line.split(",");
                if (coordinates.length == 3) {
                    double x = Double.parseDouble(coordinates[0]);
                    double y = Double.parseDouble(coordinates[1]);
                    double z = Double.parseDouble(coordinates[2]);
                    playerPositions.add(new Vec3d(x, y, z));
                }
            }
        } catch (IOException e) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("Error loading route: " + e.getMessage()));
        }
    }

    private int listSavedRoutes(CommandContext<FabricClientCommandSource> context) {
        // Get Minecraft app data directory
        File appDataDir = MinecraftClient.getInstance().runDirectory;
        File routeDirectory = new File(appDataDir, "RouteRecorder");
        File[] routeFiles = routeDirectory.listFiles((dir, name) -> name.endsWith(".txt"));

        if (routeFiles != null && routeFiles.length > 0) {
            StringBuilder message = new StringBuilder("Saved routes: ");
            for (File file : routeFiles) {
                message.append(file.getName().replace(".txt", "")).append(", ");
            }
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal(message.substring(0, message.length() - 2))); // Remove last comma
        } else {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.literal("No saved routes found."));
        }
        return 1;
    }


    private Vec3d roundPosition(Vec3d position) {
        return new Vec3d(
                roundToDecimalPlaces(position.x, DECIMAL_PLACES),
                roundToDecimalPlaces(position.y, DECIMAL_PLACES),
                roundToDecimalPlaces(position.z, DECIMAL_PLACES)
        );
    }

    private double roundToDecimalPlaces(double value, int decimalPlaces) {
        double scale = Math.pow(10, decimalPlaces);
        return Math.round(value * scale) / scale;
    }

    private boolean shouldRecordPosition(Vec3d playerPos) {
        double distance = Math.sqrt(Math.pow(playerPos.x - lastX, 2) + Math.pow(playerPos.y - lastY, 2) + Math.pow(playerPos.z - lastZ, 2));
        return distance >= RECORDING_THRESHOLD;
    }
}