package me.aap.fermata.auto;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import me.aap.utils.log.Log;
import rikka.shizuku.Shizuku;

/**
 * "Install with MOCar": installs an APK, opened with this app, through the system's package
 * installer, marked as installed by Google Play. Android Auto only shows apps, that were requested
 * by the package installer and installed by Google Play.
 * <p>
 * Since Android 14 the package installer accepts the installer name only from a sender, that is
 * allowed to install packages, so the request is sent by the shell, using Shizuku. Without Shizuku
 * the request is sent by this app, which works on older Android versions.
 */
public class ApkInstallActivity extends Activity {
	private static final String PLAY_STORE = "com.android.vending";
	private static final String APK_MIME = "application/vnd.android.package-archive";
	private static final String[] KNOWN_INSTALLERS = {"com.google.android.packageinstaller",
			"com.android.packageinstaller", "com.samsung.android.packageinstaller"};
	private static final int SHIZUKU_REQ = 7531;
	private Shizuku.OnRequestPermissionResultListener permListener;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Uri src = getSource(getIntent());

		if (src == null) {
			toast("No APK file to install");
			finish();
			return;
		}

		toast("Preparing the installation...");
		Context app = getApplicationContext();

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
				runOnUiThread(() -> install(u));
			} catch (Exception err) {
				runOnUiThread(() -> fail(err));
			}
		}).start();
	}

	@Override
	protected void onDestroy() {
		if (permListener != null) Shizuku.removeRequestPermissionResultListener(permListener);
		super.onDestroy();
	}

	private void install(Uri u) {
		grantToInstallers(u);

		if (!isShizukuRunning()) {
			toast("Shizuku is not running - Android Auto may not show the app");
			installDirect(u);
			return;
		}

		if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
			installWithShizuku(u);
			return;
		}

		permListener = (code, result) -> {
			if (code != SHIZUKU_REQ) return;
			Shizuku.removeRequestPermissionResultListener(permListener);
			permListener = null;
			if (result == PackageManager.PERMISSION_GRANTED) {
				installWithShizuku(u);
			} else {
				toast("Shizuku permission denied - Android Auto may not show the app");
				installDirect(u);
			}
		};
		Shizuku.addRequestPermissionResultListener(permListener);
		Shizuku.requestPermission(SHIZUKU_REQ);
	}

	private void installWithShizuku(Uri u) {
		String[] cmd = {"am", "start", "-a", "android.intent.action.INSTALL_PACKAGE", "-d",
				u.toString(), "-t", APK_MIME, "--ez", "android.intent.extra.NOT_UNKNOWN_SOURCE", "true",
				"--es", "android.intent.extra.INSTALLER_PACKAGE_NAME", PLAY_STORE, "-f", "0x10000000"};

		new Thread(() -> {
			try {
				// newProcess is not public in the API 13, but it's the documented way to run shell commands
				Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class,
						String.class);
				m.setAccessible(true);
				Process p = (Process) m.invoke(null, cmd, null, null);
				String out = read(p.getInputStream());
				String err = read(p.getErrorStream());
				int rc = p.waitFor();
				Log.i("am start: rc=", rc, ", out=", out, ", err=", err);

				if ((rc != 0) || out.contains("Error") || err.contains("Error")) {
					throw new IllegalStateException(err.isEmpty() ? out : err);
				}

				runOnUiThread(this::finish);
			} catch (Exception ex) {
				Log.e(ex, "Shizuku installation failed");
				runOnUiThread(() -> {
					toast("Shizuku installation failed: " + ex.getLocalizedMessage());
					installDirect(u);
				});
			}
		}).start();
	}

	private void installDirect(Uri u) {
		@SuppressWarnings("deprecation")
		Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
		i.setDataAndType(u, APK_MIME);
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
		i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
		i.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY_STORE);

		try {
			startActivity(i);
		} catch (Exception err) {
			fail(err);
			return;
		}

		finish();
	}

	/**
	 * The installer, started by the shell, reads the APK with the permission, granted in advance.
	 */
	private void grantToInstallers(Uri u) {
		int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
		@SuppressWarnings("deprecation")
		Intent probe = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(u, APK_MIME);

		try {
			for (ResolveInfo ri : getPackageManager().queryIntentActivities(probe, 0)) {
				grantUriPermission(ri.activityInfo.packageName, u, flags);
			}
		} catch (Exception err) {
			Log.d(err, "Failed to query installers");
		}

		for (String pkg : KNOWN_INSTALLERS) {
			try {
				grantUriPermission(pkg, u, flags);
			} catch (Exception ignore) {
			}
		}
	}

	private static boolean isShizukuRunning() {
		try {
			return Shizuku.pingBinder() && !Shizuku.isPreV11();
		} catch (Throwable err) {
			return false;
		}
	}

	private void fail(Exception err) {
		Log.e(err, "APK installation failed");
		toast("Installation failed: " + err.getLocalizedMessage());
		finish();
	}

	private void toast(String msg) {
		Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
	}

	private static String read(InputStream in) throws Exception {
		try (in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			for (int n = in.read(buf); n != -1; n = in.read(buf)) out.write(buf, 0, n);
			return out.toString().trim();
		}
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
