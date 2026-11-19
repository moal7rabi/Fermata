package me.aap.fermata.auto;

import static android.content.Context.DISPLAY_SERVICE;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.os.SystemClock.uptimeMillis;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static androidx.core.graphics.drawable.IconCompat.createWithResource;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Looper;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SessionInfo;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.utils.concurrent.HandlerExecutor;
import me.aap.utils.function.Cancellable;
import me.aap.utils.log.Log;

/**
 * Shows Fermata's own UI on the full car screen. Android Auto gives the full screen only to the
 * navigation apps, other apps are shown in the split screen next to the map. This service is
 * registered as a navigation app, the UI is rendered on a private virtual display, backed by the
 * car's surface. Unlike the screen mirroring, nothing is captured from the phone's screen, so no
 * screen capture or accessibility permissions are required, and the phone may stay locked.
 */
public class FullScreenCarService extends CarAppService {

	@NonNull
	@Override
	public Session onCreateSession(@NonNull SessionInfo sessionInfo) {
		return new Session() {
			@NonNull
			@Override
			public Screen onCreateScreen(@NonNull Intent intent) {
				return new CarScreen(getCarContext());
			}
		};
	}

	@NonNull
	@Override
	public HostValidator createHostValidator() {
		return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
	}

	private static final class CarScreen extends Screen
			implements SurfaceCallback, DefaultLifecycleObserver {
		private static final long SCROLL_UP_DELAY = 300;
		private final HandlerExecutor handler = FermataApplication.get().getHandler();
		@Nullable
		private SurfaceContainer sc;
		@Nullable
		private VirtualDisplay vd;
		@Nullable
		private FullScreenCarHost host;
		private int vdWidth;
		private int vdHeight;
		private int vdDpi;
		private boolean started;
		// Scrolling is emulated with a drag, starting at the screen center
		private float scrollX;
		private float scrollY;
		private long scrollDownTime;
		private long scrollMoveTime;
		@Nullable
		private Cancellable scrollUp;

		CarScreen(@NonNull CarContext ctx) {
			super(ctx);
			ctx.getCarService(AppManager.class).setSurfaceCallback(this);
			getLifecycle().addObserver(this);
			ctx.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					onBackButtonClick();
				}
			});
			Log.i("Full screen car screen created. API level: ", ctx.getCarAppApiLevel());
		}

		@NonNull
		@Override
		public Template onGetTemplate() {
			var ctx = getCarContext();
			var backButton = new Action.Builder().setIcon(
							new CarIcon.Builder(createWithResource(ctx, R.drawable.back)).build())
					.setOnClickListener(this::onBackButtonClick).build();
			return new NavigationTemplate.Builder()
					.setActionStrip(new ActionStrip.Builder().addAction(backButton).build())
					.setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build()).build();
		}

		@Override
		public void onStart(@NonNull LifecycleOwner owner) {
			started = true;
			if (host != null) host.resume();
		}

		@Override
		public void onStop(@NonNull LifecycleOwner owner) {
			started = false;
			if (host != null) host.pause();
		}

		@Override
		public void onDestroy(@NonNull LifecycleOwner owner) {
			release();
		}

		@Override
		public void onSurfaceAvailable(@NonNull SurfaceContainer sc) {
			if (!isMainThread()) {
				handler.post(() -> onSurfaceAvailable(sc));
				return;
			}
			var surface = sc.getSurface();
			if (surface == null) return;
			this.sc = sc;
			Log.i("Car surface available: ", sc.getWidth(), "x", sc.getHeight(), ", dpi=", sc.getDpi());

			if ((vd != null) && (host != null) && !host.isDestroyed() && (vdWidth == sc.getWidth()) &&
					(vdHeight == sc.getHeight()) && (vdDpi == sc.getDpi())) {
				vd.setSurface(surface);
				if (started) host.resume();
				return;
			}

			// A new size requires a new presentation, since it's dismissed on the display size change
			release();
			try {
				var dm = (DisplayManager) FermataApplication.get().getSystemService(DISPLAY_SERVICE);
				var name = getCarContext().getString(R.string.fullscreen_service_name);
				vd = dm.createVirtualDisplay(name, sc.getWidth(), sc.getHeight(), sc.getDpi(), surface,
						VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
				if (vd == null) throw new IllegalStateException("Failed to create virtual display");
				vdWidth = sc.getWidth();
				vdHeight = sc.getHeight();
				vdDpi = sc.getDpi();
				var h = host = new FullScreenCarHost(vd.getDisplay());
				h.setOnDismissListener(() -> {
					Log.i("Presentation dismissed, recreating");
					if (host == h) recreate();
				});
				h.create().onFailure(err -> {
					if (host == h) showError(err);
				});
				if (started) h.resume();
			} catch (Throwable err) {
				Log.e(err, "Failed to show Fermata on the car screen");
				showError(err);
			}
		}

		@Override
		public void onSurfaceDestroyed(@NonNull SurfaceContainer sc) {
			if (!isMainThread()) {
				handler.post(() -> onSurfaceDestroyed(sc));
				return;
			}
			if (this.sc != sc) return;
			Log.i("Car surface destroyed");
			this.sc = null;
			cancelScroll();
			if (host != null) host.pause();
			if (vd != null) vd.setSurface(null);
		}

		@Override
		public void onClick(float x, float y) {
			cancelScroll();
			var h = host;
			if (h != null) {
				h.tap(x, y);
			} else if (sc != null) {
				// The previous attempt has failed - retry on click
				var s = sc;
				onSurfaceAvailable(s);
			}
		}

		@Override
		public void onScroll(float distanceX, float distanceY) {
			var h = host;
			if (h == null) return;
			var time = uptimeMillis();
			scrollMoveTime = time;

			if (scrollUp == null) {
				scrollX = vdWidth / 2f;
				scrollY = vdHeight / 2f;
				if (!h.motionEvent(time, time, ACTION_DOWN, scrollX, scrollY)) return;
				scrollDownTime = time;
				scheduleScrollUp(SCROLL_UP_DELAY);
			}

			scrollX -= distanceX;
			scrollY -= distanceY;
			h.motionEvent(scrollDownTime, time, ACTION_MOVE, scrollX, scrollY);
		}

		@Override
		public void onFling(float velocityX, float velocityY) {
			var h = host;
			if ((h == null) || (scrollUp == null)) return;
			scrollUp.cancel();
			scrollUp = null;
			// Continue the drag with the fling velocity and release it, to let the views fling
			var time = uptimeMillis();
			for (int i = 1; i <= 3; i++) {
				scrollX += velocityX * 0.01f;
				scrollY += velocityY * 0.01f;
				h.motionEvent(scrollDownTime, time + i * 10L, ACTION_MOVE, scrollX, scrollY);
			}
			h.motionEvent(scrollDownTime, time + 30, ACTION_UP, scrollX, scrollY);
		}

		private void scheduleScrollUp(long delay) {
			scrollUp = handler.schedule(() -> {
				if (scrollUp == null) return;
				var time = uptimeMillis();
				var end = scrollMoveTime + SCROLL_UP_DELAY;
				if (end > time) {
					scheduleScrollUp(end - time);
				} else {
					scrollUp = null;
					var h = host;
					if (h != null) h.motionEvent(scrollDownTime, time, ACTION_UP, scrollX, scrollY);
				}
			}, delay);
		}

		private void cancelScroll() {
			var up = scrollUp;
			if (up == null) return;
			scrollUp = null;
			up.cancel();
			var h = host;
			if (h != null) h.motionEvent(scrollDownTime, uptimeMillis(), ACTION_UP, scrollX, scrollY);
		}

		private void onBackButtonClick() {
			var h = host;
			if ((h == null) || !h.back()) getCarContext().finishCarApp();
		}

		private void recreate() {
			var s = sc;
			release();
			if (s != null) onSurfaceAvailable(s);
		}

		private void release() {
			cancelScroll();
			var h = host;
			host = null;
			if (h != null) h.destroy();
			var d = vd;
			vd = null;
			if (d != null) d.release();
		}

		private void showError(Throwable err) {
			var s = sc;
			release();
			if (s != null) drawMsg(s, getCarContext().getString(R.string.err_failed_to_show_fullscreen) +
					"\n" + err.getMessage());
		}

		private static boolean isMainThread() {
			return Looper.myLooper() == Looper.getMainLooper();
		}

		private static void drawMsg(SurfaceContainer sc, String text) {
			try {
				var surface = sc.getSurface();
				if (surface == null) return;
				var c = surface.lockCanvas(null);
				var w = sc.getWidth();
				var h = sc.getHeight();
				var paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
				paint.setColor(Color.WHITE);
				paint.setTypeface(Typeface.DEFAULT);
				paint.setTextAlign(Paint.Align.CENTER);
				paint.setTextSize(h / 16f);
				var sl = StaticLayout.Builder.obtain(text, 0, text.length(), paint, w).setMaxLines(6)
						.setEllipsize(TextUtils.TruncateAt.END).setIncludePad(true).build();
				c.drawColor(Color.BLACK);
				c.translate(w / 2f, h / 2f - sl.getHeight() / 2f);
				sl.draw(c);
				surface.unlockCanvasAndPost(c);
			} catch (Exception ex) {
				Log.d(ex, "Failed to draw message on surface");
			}
		}
	}
}
