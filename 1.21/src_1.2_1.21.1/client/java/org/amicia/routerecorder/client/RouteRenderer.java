package org.amicia.routerecorder.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.RenderLayer;

public class RouteRenderer {

    public static boolean isDrawing = false;
    public static boolean paused = false;

    public static void registerRenderer() {
        WorldRenderEvents.LAST.register((context) -> {
            if (isDrawing) { // Only render if drawing is active
                MatrixStack matrixStack = context.matrixStack();
                VertexConsumerProvider vertexConsumers = context.consumers();
                renderLine(matrixStack, vertexConsumers);
            }
        });
    }

    public static void clear() {
        RouterecorderClient.playerPositions.clear();
    }

    public static void startDrawing() {
        isDrawing = true; 
        paused = false;
    }

    public static void stopDrawing() {
        isDrawing = false;
        paused = false;
    }

    public static void pauseDrawing() {
        paused = true;
    }

    public static void resumeDrawing() {
        paused = false;
    }

    private static void renderLine(MatrixStack matrixStack, VertexConsumerProvider vertexConsumers) {
        if (RouterecorderClient.playerPositions.size() < 2) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Vec3d cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrixStack.peek();

        for (int i = 1; i < RouterecorderClient.playerPositions.size(); i++) {
            Vec3d start = RouterecorderClient.playerPositions.get(i - 1).subtract(cameraPos);
            Vec3d end = RouterecorderClient.playerPositions.get(i).subtract(cameraPos);

            float red = (RouterecorderClient.lineColor >> 16 & 0xFF) / 255.0f; // Extract red
            float green = (RouterecorderClient.lineColor >> 8 & 0xFF) / 255.0f; // Extract green
            float blue = (RouterecorderClient.lineColor & 0xFF) / 255.0f; // Extract blue
            float alpha = 1.0f; // Full opacity

            // Set the color for the start vertex
            buffer.vertex(entry.getPositionMatrix(), (float) start.x, (float) start.y, (float) start.z)
                    .color(red, green, blue, alpha) // Use the extracted color
                    .normal(0.0f, 1.0f, 0.0f);

            // Set the color for the end vertex
            buffer.vertex(entry.getPositionMatrix(), (float) end.x, (float) end.y, (float) end.z)
                    .color(red, green, blue, alpha) // Use the extracted color
                    .normal(0.0f, 1.0f, 0.0f);
        }

        RenderSystem.disableBlend();
    }
}
