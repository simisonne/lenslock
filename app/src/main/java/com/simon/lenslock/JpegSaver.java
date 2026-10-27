package com.simon.lenslock;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Centre crops for software zoom (the pinned camera ignores zoom requests),
 * rotates to portrait and writes into DCIM/Camera, the same folder the
 * GrapheneOS camera uses.
 */
final class JpegSaver {

    private static final String TAG = "LensLock";

    private JpegSaver() {
    }

    static Uri save(Context ctx, byte[] jpeg, int rotateDegrees, float zoom) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) throw new IOException("could not decode jpeg");

        Bitmap work = bmp;
        try {
            if (zoom > 1.02f) {
                int w = Math.max(1, Math.round(bmp.getWidth() / zoom));
                int h = Math.max(1, Math.round(bmp.getHeight() / zoom));
                int x = (bmp.getWidth() - w) / 2;
                int y = (bmp.getHeight() - h) / 2;
                Bitmap cropped = Bitmap.createBitmap(bmp, x, y, w, h);
                if (cropped != bmp) {
                    work = cropped;
                    bmp.recycle();
                }
            }
            if (rotateDegrees % 360 != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotateDegrees);
                Bitmap rotated = Bitmap.createBitmap(work, 0, 0, work.getWidth(), work.getHeight(), m, true);
                if (rotated != work) {
                    work.recycle();
                    work = rotated;
                }
            }

            String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());

            Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("media store insert returned null");

            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("no output stream for " + uri);
                work.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            Log.i(TAG, "saved " + name + " " + work.getWidth() + "x" + work.getHeight() + " zoom=" + zoom + " -> " + uri);
            return uri;
        } finally {
            if (work != null && !work.isRecycled()) work.recycle();
        }
    }
}
