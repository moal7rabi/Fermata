package me.aap.fermata.auto;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static me.aap.utils.async.Completed.failed;

import android.app.PendingIntent;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentController;
import androidx.fragment.app.FragmentHostCallback;
import androidx.fragment.app.FragmentManager;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.media.service.FermataMediaServiceConnection;
import me.aap.fermata.ui.activity.FermataActivity;
import me.aap.fermata.ui.activity.MainActivity;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.function.Function;
import me.aap.utils.function.Supplier;
import me.aap.utils.log.Log;
import me.aap.utils.ui.activity.ActivityDelegate;

/**
 * Hosts Fermata's UI in a {@link Presentation} on a private virtual display, which is rendered on the
 * full car screen by {@link FullScreenCarService}. Since Android 10, apps are not allowed to launch
 * activities on their own virtual displays, but may show windows there, so this host plays the role of
 * the activity, like {@link MainCarActivity} does for the Android Auto's projection. The host is
 * the context of the UI, so the views and fragments find their delegate by the context.
 */
final class FullScreenCarHost extends ContextThemeWrapper implements FermataActivity {
	private final Promise<MainActivityDelegate> delegate = new Promise<>();
	private final CarPresentation presentation;
	private final FragmentController fragments;
	private final Function<Context, ActivityDelegate> contextToDelegate;
	private final Intent intent = new Intent();
	private final int[] loc = new int[2];
	private FermataMediaServiceConnection connection;
	private boolean finishing;
	private boolean resumed;
	private boolean destroyed;

	FullScreenCarHost(Display display) {
		var app = FermataApplication.get();
		presentation = new CarPresentation(MainActivityDelegate.attachBaseContext(app), display);
		var ctx = presentation.getContext();
		MainActivityDelegate.setTheme(ctx, true);
		attachBaseContext(ctx);
		MainActivityDelegate.setTheme(this, true);
		fragments = FragmentController.createController(new Host(this));
		contextToDelegate = c -> delegate.peek();
	}

	FutureSupplier<MainActivityDelegate> create() {
		presentation.show();
		fragments.attachHost(null);
		fragments.dispatchCreate();

		FermataMediaServiceConnection.connect(this).onCompletion((c, err) -> {
			if (err != null) {
				Log.e(err, "Failed to connect to the media service");
				delegate.completeExceptionally(err);
				return;
			}
			if (destroyed) {
				c.disconnect();
				delegate.cancel();
				return;
			}

			connection = c;
			var d = new MainActivityDelegate(this, c.createBinder());
			ActivityDelegate.setContextToDelegate(contextToDelegate);
			delegate.complete(d);
			d.onActivityCreate(null);
			// The session activity must be a real activity, that can be opened on the phone
			var app = FermataApplication.get();
			d.getMediaSessionCallback().getSession().setSessionActivity(PendingIntent.getActivity(app, 0,
					new Intent(app, MainActivity.class), FLAG_IMMUTABLE));
			//noinspection deprecation
			fragments.dispatchActivityCreated();
			fragments.dispatchStart();
			if (resumed) resumeDelegate(d);
		});

		return delegate;
	}

	void resume() {
		if (destroyed || resumed) return;
		resumed = true;
		var d = delegate.peek();
		if (d != null) resumeDelegate(d);
	}

	private void resumeDelegate(MainActivityDelegate d) {
		fragments.dispatchResume();
		fragments.execPendingActions();
		d.onActivityResume();
	}

	void pause() {
		if (destroyed || !resumed) return;
		resumed = false;
		var d = delegate.peek();
		if (d == null) return;
		fragments.dispatchPause();
		d.getMediaSessionCallback().onCarScreenLeft();
	}

	void destroy() {
		if (destroyed) return;
		pause();
		destroyed = true;
		var d = delegate.peek();
		if (d != null) {
			d.onActivityDestroy();
			fragments.dispatchStop();
		}
		fragments.dispatchDestroy();
		if (ActivityDelegate.getContextToDelegate() == contextToDelegate) {
			ActivityDelegate.setContextToDelegate(null);
		}
		var c = connection;
		connection = null;
		if (c != null) c.disconnect();
		delegate.cancel();
		presentation.setOnDismissListener(null);
		try {
			presentation.dismiss();
		} catch (Exception err) {
			Log.d(err, "Failed to dismiss presentation");
		}
	}

	boolean isDestroyed() {
		return destroyed;
	}

	void setOnDismissListener(Runnable onDismiss) {
		presentation.setOnDismissListener(d -> {
			if (!destroyed) onDismiss.run();
		});
	}

	boolean back() {
		var d = delegate.peek();
		if (d == null) return false;
		d.onBackPressed();
		return true;
	}

	boolean tap(float x, float y) {
		var time = android.os.SystemClock.uptimeMillis();
		boolean down = motionEvent(time, time, ACTION_DOWN, x, y);
		boolean up = motionEvent(time, time + 10, ACTION_UP, x, y);
		return down || up;
	}

	/**
	 * Dispatches a touch event with the coordinates of the car screen (the same as of the display).
	 */
	boolean motionEvent(long downTime, long eventTime, int action, float x, float y) {
		if (destroyed || (delegate.peek() == null)) return false;
		var decor = presentation.getWindow().getDecorView();
		decor.getLocationOnScreen(loc);
		var e = MotionEvent.obtain(downTime, eventTime, action, x - loc[0], y - loc[1], 0);
		e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
		try {
			return presentation.dispatchTouchEvent(e);
		} catch (Exception err) {
			Log.e(err, "Failed to dispatch event ", e);
			return false;
		} finally {
			e.recycle();
		}
	}

	@NonNull
	@Override
	public FutureSupplier<MainActivityDelegate> getActivityDelegate() {
		return delegate;
	}

	@Override
	public Window getWindow() {
		return presentation.getWindow();
	}

	@Override
	public View getCurrentFocus() {
		return presentation.getCurrentFocus();
	}

	@NonNull
	@Override
	public FragmentManager getSupportFragmentManager() {
		return fragments.getSupportFragmentManager();
	}

	@Override
	public void setContentView(int layoutResID) {
		// Inflated with this context, not the presentation's one
		presentation.setContentView(LayoutInflater.from(this).inflate(layoutResID, null));
	}

	@Override
	public <T extends View> T findViewById(int id) {
		return presentation.findViewById(id);
	}

	@Override
	public void recreate() {
		// Not supported, the same as in the car activity
	}

	@Override
	public void finish() {
		if (finishing) return;
		finishing = true;
		var d = delegate.peek();
		if (d != null) d.onActivityFinish();
	}

	@Override
	public boolean isFinishing() {
		return finishing || destroyed;
	}

	@Override
	public void startActivity(Intent intent) {
		startActivity(intent, null);
	}

	@Override
	public void startActivity(Intent intent, @Nullable Bundle options) {
		// There is no activity on the car display, so the activity is started on the phone
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		getApplicationContext().startActivity(intent, options);
	}

	@Override
	public FutureSupplier<Intent> startActivityForResult(Supplier<Intent> intent) {
		return failed(new UnsupportedOperationException());
	}

	@Override
	public FutureSupplier<int[]> checkPermissions(String... perms) {
		return failed(new UnsupportedOperationException());
	}

	@Override
	public Intent getIntent() {
		return intent;
	}

	@Override
	public boolean isCarActivity() {
		return true;
	}

	@Override
	public void setRequestedOrientation(int requestedOrientation) {
		// The orientation is defined by the car screen
	}

	@Nullable
	@Override
	public EditText startInput(TextWatcher w) {
		return null;
	}

	private final class CarPresentation extends Presentation {

		CarPresentation(Context outerContext, Display display) {
			super(outerContext, display);
			setCanceledOnTouchOutside(false);
		}

		@Override
		@SuppressWarnings("deprecation")
		public void onBackPressed() {
			// Must not dismiss the presentation
			back();
		}

		@Override
		public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
			var d = delegate.peek();
			return (d != null) ? d.onKeyDown(keyCode, event, super::onKeyDown) :
					super.onKeyDown(keyCode, event);
		}

		@Override
		public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
			var d = delegate.peek();
			return (d != null) ? d.onKeyUp(keyCode, event, super::onKeyUp) :
					super.onKeyUp(keyCode, event);
		}
	}

	private final class Host extends FragmentHostCallback<FullScreenCarHost> {

		Host(Context ctx) {
			super(ctx, new Handler(Looper.getMainLooper()), 0);
		}

		@Override
		public FullScreenCarHost onGetHost() {
			return FullScreenCarHost.this;
		}

		@NonNull
		@Override
		public LayoutInflater onGetLayoutInflater() {
			return LayoutInflater.from(getContext()).cloneInContext(getContext());
		}

		@Nullable
		@Override
		public View onFindViewById(int id) {
			return presentation.findViewById(id);
		}

		@Override
		public boolean onHasView() {
			var w = presentation.getWindow();
			return (w != null) && (w.peekDecorView() != null);
		}
	}
}
