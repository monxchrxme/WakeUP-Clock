package ru.wakeup.clock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

/** Test-only, non-seekable provider without persistent grants. Java keeps its separate process self-contained. */
public final class TestAudioProvider extends ContentProvider {
    private volatile boolean available = true;
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment()});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!available) throw new FileNotFoundException("Test source is no longer available");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if ("invalid".equals(uri.getLastPathSegment())) output.write("not an audio file".getBytes());
                    else try (InputStream input = getContext().getAssets().open(
                            "other-tone.wav".equals(uri.getLastPathSegment()) ? "other-tone.wav" :
                            "long-tone.wav".equals(uri.getLastPathSegment()) ? "long-tone.wav" :
                            "regular-tone.wav".equals(uri.getLastPathSegment()) ? "regular-tone.wav" : "custom-tone.wav")) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                } catch (Exception ignored) { /* A consumer may close the pipe after a failed prepare. */ }
            }, "test-audio-pipe").start();
            return pipe[0];
        } catch (Exception error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        available = !"disable".equals(method);
        return new Bundle();
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
