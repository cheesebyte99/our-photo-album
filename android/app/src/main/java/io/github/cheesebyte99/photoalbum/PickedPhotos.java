package io.github.cheesebyte99.photoalbum;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 고른 사진을 앱 안(캐시 폴더 picked/)으로 먼저 복사해 둔 뒤, 그 파일을 웹 화면에 넘긴다.
 *
 * 갤러리·구글 포토·클라우드 사진은 앱마다 주소(content://)를 읽는 방식이 달라
 * WebView 가 직접 읽다 실패하는 일이 있다. 앱 안에 복사본을 만들면 늘 같은 방식으로 읽힌다.
 * 또 웹 화면(크롬 엔진)은 HEIC/HEIF(삼성 「고효율 사진」, 아이폰 기본) 사진을 열지 못하므로
 * 그런 사진은 여기서 JPG 로 바꾸고, 찍은 날짜(EXIF)도 옮겨 적는다.
 */
final class PickedPhotos {

    /** 너무 큰 사진은 HEIC → JPG 로 바꿀 때 긴 변을 이만큼으로 줄인다(웹에서 어차피 더 줄여 올림). */
    private static final int MAX_SIDE = 4096;
    private static final String[] EXIF_COPY = {
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME,
    };

    private PickedPhotos() {
    }

    static File dir(Context c) {
        return new File(c.getCacheDir(), "picked");
    }

    /** 고를 때마다 지난번 복사본은 지운다. */
    static void clear(Context c) {
        File[] old = dir(c).listFiles();
        if (old == null) return;
        for (File f : old) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** 각 사진을 앱 안으로 복사(필요하면 JPG 로 변환)한 주소. 실패한 사진은 원래 주소를 그대로 둔다. */
    static Uri[] copyAll(Context c, Uri[] picked) {
        File folder = dir(c);
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        Uri[] out = new Uri[picked.length];
        for (int i = 0; i < picked.length; i++) {
            try {
                File f = copyOne(c, picked[i], folder, i);
                out[i] = FileProvider.getUriForFile(c, c.getPackageName() + ".files", f);
            } catch (Exception | OutOfMemoryError e) {
                out[i] = picked[i];
            }
        }
        return out;
    }

    private static File copyOne(Context c, Uri uri, File folder, int index) throws IOException {
        ContentResolver r = c.getContentResolver();
        String name = displayName(r, uri);
        String mime = r.getType(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        boolean heif = (mime != null && (mime.contains("heic") || mime.contains("heif")))
                || lower.endsWith(".heic") || lower.endsWith(".heif");

        String base = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.isEmpty()) base = "photo";
        if (heif && Build.VERSION.SDK_INT >= 28) {
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            File f = new File(folder, index + "_" + stem + ".jpg");
            heifToJpeg(r, uri, f);
            return f;
        }
        if (!base.contains(".") && mime != null && mime.startsWith("image/")) {
            base = base + "." + mime.substring(6).replace("jpeg", "jpg");
        }
        File f = new File(folder, index + "_" + base);
        try (InputStream in = r.openInputStream(uri); OutputStream o = new FileOutputStream(f)) {
            if (in == null) throw new IOException("no stream");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        if (f.length() == 0) throw new IOException("empty");
        return f;
    }

    private static void heifToJpeg(ContentResolver r, Uri uri, File out) throws IOException {
        if (Build.VERSION.SDK_INT < 28) throw new IOException("heif needs API 28");
        // ImageDecoder 는 EXIF 회전을 반영해 똑바로 세운 그림을 준다.
        Bitmap bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(r, uri), (decoder, info, src) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            int longSide = Math.max(w, h);
            if (longSide > MAX_SIDE) {
                float s = MAX_SIDE / (float) longSide;
                decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        try (OutputStream o = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, o)) throw new IOException("compress");
        } finally {
            bmp.recycle();
        }
        copyTakenDate(r, uri, out);
    }

    /** 찍은 날짜를 옮겨 적어, 웹 화면이 그날로 날짜를 맞출 수 있게 한다. */
    private static void copyTakenDate(ContentResolver r, Uri from, File to) {
        try (InputStream in = r.openInputStream(from)) {
            if (in == null) return;
            ExifInterface src = new ExifInterface(in);
            ExifInterface dst = new ExifInterface(to.getAbsolutePath());
            boolean any = false;
            for (String tag : EXIF_COPY) {
                String v = src.getAttribute(tag);
                if (v != null && !v.isEmpty()) {
                    dst.setAttribute(tag, v);
                    any = true;
                }
            }
            if (any) dst.saveAttributes();
        } catch (Exception ignored) {
            // 날짜를 못 옮겨도 사진은 그대로 쓴다
        }
    }

    private static String displayName(ContentResolver r, Uri uri) {
        try (Cursor cur = r.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
            // 이름을 못 읽으면 아래 기본 이름
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "photo";
    }
}
