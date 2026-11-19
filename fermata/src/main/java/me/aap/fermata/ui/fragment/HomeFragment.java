package me.aap.fermata.ui.fragment;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.addon.AddonManager;
import me.aap.fermata.ui.activity.MainActivityDelegate;

/**
 * MOCar's car home screen: a few big tiles for the main destinations - YouTube, Stremio, IPTV and
 * the music library. Tiles of the add-ons, that are disabled, are not shown. What's playing is
 * shown, as everywhere, by the control panel at the bottom.
 */
public class HomeFragment extends MainActivityFragment {

	private record Tile(@IdRes int fragmentId, @StringRes int label, @DrawableRes int icon,
											@ColorInt int color, boolean addon) {}

	private static final Tile[] TILES = {
			new Tile(R.id.youtube_fragment, R.string.home_youtube, R.drawable.youtube,
					0xFFC62828, true),
			new Tile(R.id.stremio_fragment, R.string.home_stremio, R.drawable.stremio,
					0xFF5E35B1, true),
			new Tile(R.id.tv_fragment, R.string.home_iptv, R.drawable.tv, 0xFF1565C0, true),
			new Tile(R.id.folders_fragment, R.string.home_music, R.drawable.music, 0xFF2E7D32, false),
	};

	@Override
	public int getFragmentId() {
		return R.id.home_fragment;
	}

	@Override
	public CharSequence getTitle() {
		return getString(R.string.home_title);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
													 @Nullable Bundle savedInstanceState) {
		Context ctx = inflater.getContext();
		List<Tile> tiles = new ArrayList<>(TILES.length);
		AddonManager amgr = AddonManager.get();
		for (Tile t : TILES) {
			if (!t.addon || amgr.hasAddon(t.fragmentId)) tiles.add(t);
		}

		// Landscape (the usual car screen): all tiles in a row; portrait: two columns
		boolean landscape = ctx.getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE;
		int cols = landscape ? Math.max(1, tiles.size()) : Math.min(2, Math.max(1, tiles.size()));
		int pad = dp(ctx, 12);

		GridLayout grid = new GridLayout(ctx);
		grid.setColumnCount(cols);
		grid.setUseDefaultMargins(false);
		grid.setPadding(pad, pad, pad, pad);

		for (int i = 0; i < tiles.size(); i++) {
			GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
					GridLayout.spec(i / cols, 1f), GridLayout.spec(i % cols, 1f));
			lp.width = 0;
			lp.height = 0;
			lp.setMargins(pad / 2, pad / 2, pad / 2, pad / 2);
			grid.addView(createTile(ctx, tiles.get(i)), lp);
		}

		grid.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		return grid;
	}

	private View createTile(Context ctx, Tile t) {
		LinearLayout tile = new LinearLayout(ctx);
		tile.setOrientation(LinearLayout.VERTICAL);
		tile.setGravity(Gravity.CENTER);
		tile.setClickable(true);
		tile.setFocusable(true);
		tile.setContentDescription(ctx.getString(t.label));

		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(ctx, 20));
		bg.setColor(new ColorStateList(
				new int[][]{{android.R.attr.state_pressed}, {android.R.attr.state_focused}, {}},
				new int[]{darker(t.color), darker(t.color), t.color}));
		tile.setBackground(bg);

		ImageView icon = new ImageView(ctx);
		icon.setImageResource(t.icon);
		icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
		int iconSize = dp(ctx, 72);
		tile.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

		TextView label = new TextView(ctx);
		label.setText(t.label);
		label.setTextColor(Color.WHITE);
		label.setTypeface(Typeface.DEFAULT_BOLD);
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
		label.setGravity(Gravity.CENTER);
		label.setMaxLines(1);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(ctx, 12);
		tile.addView(label, lp);

		tile.setOnClickListener(v -> MainActivityDelegate.getActivityDelegate(v.getContext())
				.onSuccess(a -> a.showFragment(t.fragmentId)));
		return tile;
	}

	@ColorInt
	private static int darker(@ColorInt int c) {
		return Color.rgb((int) (Color.red(c) * 0.75f), (int) (Color.green(c) * 0.75f),
				(int) (Color.blue(c) * 0.75f));
	}

	private static int dp(Context ctx, int v) {
		return Math.round(v * ctx.getResources().getDisplayMetrics().density);
	}
}
