package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import org.joml.*;
import java.lang.reflect.*;
import java.nio.FloatBuffer;
import java.util.*;

/** Runs production drawLayer with native bind/draw/unbind replaced by counters in the fixture agent. */
public final class FlightShaderBatchChecks {
    private static Shader active;
    private static int draws, unbinds;
    private static boolean failDraw;
    private static final List<Vector3f> offsets = new ArrayList<>();
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static <T> T fixture(Class<T> type) throws Exception {
        Method m = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks").getDeclaredMethod("fixture", Class.class);
        m.setAccessible(true); return type.cast(m.invoke(null, type));
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static final class Failure extends RuntimeException {}
    public static void recordBind() {}
    public static void recordUnbind() { unbinds++; }
    public static void recordDraw() {
        require(active != null && active.live, "every section is drawn with an applied shader");
        draws++;
        if (active.CHUNK_OFFSET != null) {
            FloatBuffer b = active.CHUNK_OFFSET.getFloatBuffer(); offsets.add(new Vector3f(b.get(0), b.get(1), b.get(2)));
        }
        if (failDraw) { failDraw = false; throw new Failure(); }
    }
    public static class Offset extends Uniform {
        int uploads;
        public Offset() { super("ChunkOffset", 6, 3, null); }
        @Override public void upload() { uploads++; }
    }
    public static class Shader extends ShaderInstance {
        int preparations, applies, clears;
        boolean live, failApply, failClear;
        Matrix4f lastView;
        public Shader() throws java.io.IOException { super(null, "fixture", null); }
        @Override public void setDefaultUniforms(VertexFormat.Mode mode, Matrix4f view, Matrix4f projection, Window window) {
            preparations++; lastView = view;
            require(RenderSystem.getShaderFogStart() == (CHUNK_OFFSET == null ? 64 : 64f / ClientView.GIANT), "fog start uses the correct coordinate scale");
            require(RenderSystem.getShaderFogEnd() == (CHUNK_OFFSET == null ? 320 : 320f / ClientView.GIANT), "fog end uses the correct coordinate scale");
        }
        @Override public void apply() { applies++; live = true; active = this; if (failApply) throw new Failure(); }
        @Override public void clear() { clears++; live = false; if (failClear) throw new Failure(); }
    }
    private static Shader shader(boolean withOffset) throws Exception {
        Shader shader = fixture(Shader.class);
        if (withOffset) {
            Offset offset = fixture(Offset.class);
            field(Uniform.class, "floatValues").set(offset,
                    java.nio.ByteBuffer.allocateDirect(12).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer());
            offset.set(3f, 4f, 5f); field(ShaderInstance.class, "CHUNK_OFFSET").set(shader, offset);
        }
        field(RenderSystem.class, "shader").set(null, shader);
        active = shader; draws = 0; unbinds = 0; offsets.clear();
        RenderSystem.setShaderFogStart(64); RenderSystem.setShaderFogEnd(320);
        return shader;
    }
    private static VertexBuffer buffer(boolean clipped) throws Exception {
        VertexBuffer b = clipped ? fixture(ClippedVertexBuffer.class) : fixture(VertexBuffer.class);
        field(VertexBuffer.class, "format").set(b, fixture(VertexFormat.class));
        field(VertexBuffer.class, "mode").set(b, VertexFormat.Mode.QUADS);
        return b;
    }
    @SuppressWarnings("unchecked") private static void frame(RenderType layer, int count, int clippedAt, boolean splitMatrix) throws Exception {
        List<Object> frames = (List<Object>) field(FlightView.class, "frame").get(null); frames.clear();
        Class<?> section = Class.forName(FlightView.class.getName() + "$Section");
        Constructor<?> sectionCtor = section.getDeclaredConstructors()[0]; sectionCtor.setAccessible(true);
        Class<?> draw = Class.forName(FlightView.class.getName() + "$DrawFrame");
        Constructor<?> drawCtor = draw.getDeclaredConstructors()[0]; drawCtor.setAccessible(true);
        Matrix4f common = new Matrix4f().scale(32);
        FlightFog.Frame fog = FlightFog.frame(common);
        for (int i = 0; i < count; i++) {
            Object s = sectionCtor.newInstance(new BlockPos(i * 16, 0, 0), Map.of(layer, buffer(i == clippedAt)), null);
            FlightFog.Frame placed = fog.section(i * 16, 0, 0);
            if (splitMatrix && i == count - 1) placed = new FlightFog.Frame(new Matrix4f(common).rotateY(.25f), placed.chunkOffset());
            frames.add(drawCtor.newInstance(s, new Matrix4f(common).translate(i * 16, 0, 0), placed));
        }
    }
    private static void restored(Shader shader) {
        require(!shader.live && RenderSystem.getShaderFogStart() == 64 && RenderSystem.getShaderFogEnd() == 320,
                "shader and fog state restored after the layer");
        if (shader.CHUNK_OFFSET != null) {
            FloatBuffer b = shader.CHUNK_OFFSET.getFloatBuffer();
            require(b.get(0) == 3 && b.get(1) == 4 && b.get(2) == 5, "previous ChunkOffset restored");
        }
        require(unbinds == 1, "vertex buffer unbound even after failure");
    }
    public static void main(String[] args) throws Exception {
        field(RenderSystem.class, "renderThread").set(null, Thread.currentThread());
        field(FlightView.class, "drawing").setBoolean(null, true);
        RenderType layer = (RenderType) fixture(Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType"));
        Matrix4f projection = new Matrix4f();
        Shader shader = shader(true); frame(layer, 2000, -1, false); FlightView.drawLayer(layer, projection);
        require(draws == 2000 && shader.preparations == 1 && shader.applies == 1 && shader.clears == 1,
                "2000 sections share one shader preparation");
        require(((Offset) shader.CHUNK_OFFSET).uploads == 2000, "every section uploads its own offset");
        for (int i = 0; i < offsets.size(); i++) require(offsets.get(i).equals(new Vector3f(i * 16, 0, 0)), "section offset reaches the draw call");
        restored(shader);

        shader = shader(true); frame(layer, 3, 1, false); FlightView.drawLayer(layer, projection);
        require(draws == 3 && shader.preparations == 3 && shader.clears == 3, "clipped section uses full drawing and the following batch rebinds its shader");
        restored(shader);
        shader = shader(true); frame(layer, 3, -1, true); FlightView.drawLayer(layer, projection);
        require(draws == 3 && shader.preparations == 2, "different model-view matrix starts another batch"); restored(shader);
        shader = shader(false); frame(layer, 3, -1, false); FlightView.drawLayer(layer, projection);
        require(draws == 3 && shader.preparations == 3 && shader.lastView.getTranslation(new Vector3f()).x == 1024,
                "shader without ChunkOffset retains full per-section model matrices"); restored(shader);

        for (String failure : List.of("draw", "apply", "clear")) {
            shader = shader(true); frame(layer, 1, -1, false);
            failDraw = failure.equals("draw"); shader.failApply = failure.equals("apply"); shader.failClear = failure.equals("clear");
            try { FlightView.drawLayer(layer, projection); throw new AssertionError("expected fixture failure"); }
            catch (Failure expected) { restored(shader); }
        }
        field(RenderSystem.class, "shader").set(null, null); draws = 0;
        FlightView.drawLayer(layer, projection); require(draws == 0, "missing shader does not crash or draw invalid geometry");
        FlightCloudCacheChecks.run();
        System.out.println("PASS: 2000 flight sections, per-section offsets, clipped/custom-shader fallback, matrix changes and cleanup after draw/apply/clear failures");
    }
}
