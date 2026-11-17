package app.relayview;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import ai.onnxruntime.*;

/** One CPU session and reusable native buffers per monitoring session. */
final class YoloModel implements AutoCloseable {
    static final int SIZE = 416;
    private static final int PLANE = SIZE * SIZE;
    private final FloatBuffer input = floats(3 * PLANE);
    private final FloatBuffer output = floats(3549 * 85);
    private final float[] planes = new float[3 * PLANE];
    private final OrtSession session;
    private final OnnxTensor inputTensor, outputTensor;
    private final Map<String, OnnxTensor> inputs, outputs;

    YoloModel(byte[] bytes) throws OrtException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = env.createSession(bytes, options);
        }
        inputTensor = OnnxTensor.createTensor(env, input, new long[]{1, 3, SIZE, SIZE});
        outputTensor = OnnxTensor.createTensor(env, output, new long[]{1, 3549, 85});
        inputs = Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor);
        outputs = Collections.singletonMap(session.getOutputNames().iterator().next(), outputTensor);
    }

    private static FloatBuffer floats(int count) {
        return ByteBuffer.allocateDirect(count * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    int detect(int[] argb) throws OrtException {
        for (int i = 0; i < PLANE; i++) {
            int pixel = argb[i];
            planes[i] = pixel & 255;
            planes[i + PLANE] = (pixel >>> 8) & 255;
            planes[i + 2 * PLANE] = (pixel >>> 16) & 255;
        }
        input.rewind();
        input.put(planes);
        input.rewind();
        try (OrtSession.Result ignored = session.run(inputs, outputs)) {
            return Presence.classify(output);
        }
    }

    @Override public void close() throws OrtException {
        inputTensor.close();
        outputTensor.close();
        session.close();
    }
}
