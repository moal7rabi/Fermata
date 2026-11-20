package me.aap.fermata.auto;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import me.aap.utils.log.Log;

/**
 * "Install with MOCar": installs an APK, opened with this app, through the system's package
 * installer, marked as installed by Google Play, the same way the app's updater does. Android Auto
 * only shows apps, that were requested by the package installer and installed by Google Play, so an
 * APK, installed this way (e.g. a newer MOCar build), is shown in Android Auto.
 */
public class ApkInstallActivity extends Activity {
	private static final String PLAY_STORE = "com.android.vending";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Uri src = getSource(getIntent());

		if (src == null) {
			Toast.makeText(this, "No APK file to install", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		Toast.makeText(this, "Preparing the installation...", Toast.LENGTH_SHORT).show();
		var app = getApplicationContext();

		new Thread(() -> {
			try {
				// Copied to the app's cache, shared with the installer by the FileProvider
				File dir = new File(app.getCacheDir(), "updates");
				//noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
				File f = new File(dir, "install.apk");

				try (InputStream in = getContentResolver().openInputStream(src);
						 OutputStream out = new FileOutputStream(f)) {
					if (in == null) throw new IllegalStateException("Unable to read " + src);
					byte[] buf = new byte[65536];
					for (int n = in.read(buf); n != -1; n = in.read(buf)) out.write(buf, 0, n);
				}

				Uri u = FileProvider.getUriForFile(app, app.getPackageName() + ".FileProvider", f);
				@SuppressWarnings("deprecation")
				Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
				i.setData(u);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
				i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
				i.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY_STORE);
				runOnUiThread(() -> {
					try {
						startActivity(i);
					} catch (Exception err) {
						fail(err);
						return;
					}
					finish();
				});
			} catch (Exception err) {
				runOnUiThread(() -> fail(err));
			}
		}).start();
	}

	private void fail(Exception err) {
		Log.e(err, "APK installation failed");
		Toast.makeText(this, "Installation failed: " + err.getLocalizedMessage(), Toast.LENGTH_LONG)
				.show();
		finish();
	}

	private static Uri getSource(Intent i) {
		if (i == null) return null;
		if (Intent.ACTION_SEND.equals(i.getAction())) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				return i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
			}
			//noinspection deprecation
			return i.getParcelableExtra(Intent.EXTRA_STREAM);
		}
		return i.getData();
	}
}
