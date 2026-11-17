package app.relayview;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import ai.onnxruntime.OrtException;

final class SnapshotDetector implements AutoCloseable {
    private static final int SIZE = YoloModel.SIZE;
    private final YoloModel model;
    private final Bitmap canvasBitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(canvasBitmap);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] pixels = new int[SIZE * SIZE];

    SnapshotDetector(Context context) throws IOException, OrtException {
        try (InputStream asset = context.getAssets().open("yolox_nano.onnx");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(3659407)) {
            byte[] block = new byte[16384];
            int n;
            while ((n = asset.read(block)) != -1) bytes.write(block, 0, n);
            model = new YoloModel(bytes.toByteArray());
        }
    }

    synchronized int detect(byte[] jpeg) throws IOException, OrtException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Unreadable snapshot");
        int longest = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (longest / options.inSampleSize >= SIZE * 2) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (source == null) throw new IOException("Could not decode snapshot");
        try {
            float scale = (float) SIZE / Math.max(source.getWidth(), source.getHeight());
            Rect dst = new Rect(0, 0, Math.max(1, (int)(scale * source.getWidth())),
                    Math.max(1, (int)(scale * source.getHeight())));
            canvas.drawColor(0xff727272);
            canvas.drawBitmap(source, null, dst, paint);
        } finally {
            source.recycle();
        }
        canvasBitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        return model.detect(pixels);
    }

    @Override public void close() {
        canvasBitmap.recycle();
        try { model.close(); } catch (OrtException ignored) { }
    }
}
